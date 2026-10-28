package com.yawnandpawn.app.core.reset

import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.RequestCodeHighWaterMark
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.billing.InstallIdReset
import com.yawnandpawn.app.core.billing.KeptPayments
import com.yawnandpawn.app.core.billing.PriceRefreshJobs
import com.yawnandpawn.app.core.billing.PurchaseLedger
import com.yawnandpawn.app.core.crash.UnsentCrashReports
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.telemetry.AnalyticsDataReset
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.work.BackgroundWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The marker of an unfinished "Delete all data" (Story 5.9): a file in device-protected no-backup storage, so it is
 * never backed up and outlives the settings the deletion clears. It also counts the app starts that found it
 * ([countStart]), so a deletion that keeps failing (or crashing) gives up after a few starts. `FileDeletionMarker` in
 * production.
 */
interface DeletionMarker {
    /** True while the marker is there: a deletion started and has not finished. Never throws. */
    fun exists(): Boolean

    /**
     * Writes the marker. [resetAttempts] (a deletion the user starts) sets its start count to 0; otherwise (a resume) a
     * marker already there keeps its count. Writing it again is not an error.
     */
    suspend fun write(resetAttempts: Boolean): Outcome<Unit, DomainError>

    /** Removes the marker; nothing there is not an error. */
    suspend fun remove(): Outcome<Unit, DomainError>

    /**
     * Counts one more app start that found the marker and returns the count (1 on the first start after the deletion
     * began); 0 without a marker. Never throws: a count it cannot read is 0, one it cannot write is still returned.
     */
    fun countStart(): Int
}

/**
 * The stores "Delete all data" empties besides session history and purchases (Story 5.9). Every call is safe to repeat.
 * Nothing here touches `request_code_sequence`, the grant ledger or the per-device runtime preferences (`wake_runtime`,
 * the `reliability` notification-asked flag, `backup_restore`).
 */
interface LocalDataEraser {
    /**
     * Every recording's audio file (Story 7.1) and every imported sound in `sounds/` (Story 7.4; both refused before the
     * first unlock), then in `app.db` every alarm, check config, pending change, commitment event, recording and
     * `custom_sound` row (one transaction).
     */
    suspend fun deleteAlarmData(): Outcome<Unit, DomainError>

    /**
     * `runtime.db`: every purchase intent except those of [keepSessions], the sessions a grant ledger row still waits on
     * (their intents price the record a later settle writes, Story 5.9 review).
     */
    suspend fun deletePurchaseIntents(keepSessions: Set<String>): Outcome<Unit, DomainError>

    /**
     * The whole settings DataStore (every key, so also the ones later stories add), the settings fallback cache, the
     * price cache, the reliability checklist's records (Story 5.3) and the usage stats choice (Story 5.6: off, not asked).
     */
    suspend fun clearSettings(): Outcome<Unit, DomainError>
}

/**
 * Deletes every session history row and every `session_merge` row (Story 5.9), through the history's only adapter
 * (`RoomSessionHistoryRepository`, AD-18). Safe to repeat.
 */
fun interface SessionHistoryEraser {
    suspend fun deleteAllHistory(): Outcome<Unit, DomainError>
}

/**
 * "Delete all data" (FR-SET-4, Story 5.9): removes everything the app keeps about the user on the phone, except what is
 * owed money. Like every guarded writer it runs under [AlarmWriteLock] and the session guard
 * ([SessionLockGuard.whenIdleForDeletion], the one guarded write a pending deletion allows), and it also needs
 * [SessionState.Idle]: a Completed or Missed session still waiting for its history row refuses too. A refusal is
 * [DomainError.SessionActive] and changes nothing (no marker, no store or scheduler call).
 *
 * The steps, in order, each one safe to repeat:
 * 1. write the [DeletionMarker];
 * 2. cancel every stored alarm's request code, [RequestCodes.TEST_ALARM] and the session slot;
 * 3. delete the alarms, check configs, pending changes, commitment events, session history and `session_merge`;
 * 4. delete the purchase records not owed a consume, through [PurchaseLedger] (their only writer); a record a grant
 *    ledger row still waits on stays;
 * 5. delete the purchase intents (but those of the sessions a ledger row waits on) and clear the stored session
 *    (nothing is stored while Idle);
 * 6. clear the settings DataStore, the settings fallback cache, the price cache, the reliability records and the usage
 *    stats choice (off, and not asked);
 * 7. reset the install id, only when nothing owed was kept (no pending ledger row, no owed record): the reconciler
 *    ignores a token launched under another install id, so a reset then would leave that payment unconsumed;
 * 8. step 4 again: a purchase recovery that ran meanwhile may have recorded a stranded payment;
 * 9. remove the marker;
 * 10. log one [LogEvent.AllDataDeleted].
 *
 * A failed step (or one that throws) returns its error, is logged as `OperationFailed` and leaves the marker. The app is
 * then held ([PendingDeletion.hold]): no other guarded write runs and the app shows the restoring screen while
 * [ResumePendingDeletion] runs it again from step 1 ([resume]). A run that completes releases it, and
 * [PendingDeletion.completed] sends the app back to the empty Home. The whole run is non-cancellable and off the main
 * thread ([context]).
 *
 * Never touched: the whole grant ledger, owed records, the consume retry jobs and the high-water mark. It never calls
 * another guarded use case (the guard's Mutex is not reentrant) and never `AlarmScheduling.rescheduleAll()`.
 *
 * After the guarded part (so a ring never waits for it): the request codes up to the high-water mark that no stored alarm
 * used are cancelled too (belt and braces, nothing should be armed under them), Crashlytics drops its unsent reports,
 * the usage stats data and id are reset ([AnalyticsDataReset], Story 5.6; the consent itself was cleared in step 6), and
 * the immediate price refresh is enqueued, so snooze prices come back. Network calls never run inside the guard.
 */
@Suppress("LongParameterList")
class DeleteAllData(
    private val lock: AlarmWriteLock,
    private val sessionLock: SessionLockGuard,
    private val marker: DeletionMarker,
    private val alarms: AlarmRepository,
    private val highWaterMark: RequestCodeHighWaterMark,
    scheduler: AlarmScheduler,
    private val eraser: LocalDataEraser,
    private val history: SessionHistoryEraser,
    private val ledger: PurchaseLedger,
    private val activeSession: ActiveSessionStore,
    private val installId: InstallIdReset,
    private val crashReports: UnsentCrashReports,
    private val analytics: AnalyticsDataReset,
    private val work: BackgroundWork,
    private val clock: Clock,
    private val logger: Logger,
    /** The process's pending-deletion state; one instance per process in production (a Koin single). */
    private val pendingDeletion: PendingDeletion = PendingDeletion(marker.exists()),
    /** Where the deletion runs: off the main thread in production; the test dispatcher in tests. */
    private val context: CoroutineContext = Dispatchers.Default,
) {
    private val alarmCodes = DeletionAlarmCodes(scheduler, logger)

    /** True while an unfinished deletion holds the app ([PendingDeletion.pending]). */
    val pending: StateFlow<Boolean> get() = pendingDeletion.pending

    /** The issued codes no stored alarm used, found by step 2 of the last run (read under [lock]). */
    private var unusedCodes: List<Int> = emptyList()

    /** The user's "Delete": a new deletion, its marker's start count set to 0. */
    suspend operator fun invoke(): Outcome<Unit, DomainError> =
        withContext(NonCancellable + context) {
            settled(lock.withLock { sessionLock.whenIdleForDeletion { deleteWhenIdle(fresh = true) } })
        }

    /** [ResumePendingDeletion]: runs an unfinished deletion again from step 1, keeping the marker's start count. */
    suspend fun resume(): Outcome<Unit, DomainError> =
        withContext(NonCancellable + context) {
            settled(lock.withLock { sessionLock.whenIdleForDeletion { deleteWhenIdle(fresh = false) } })
        }

    /** Inside the guard: refused unless the session is Idle (not Completed or Missed waiting for history), else steps 1 to 10. */
    private suspend fun deleteWhenIdle(fresh: Boolean): Outcome<Unit, DomainError> =
        if (sessionLock.state.value != SessionState.Idle) {
            Outcome.Failure(DomainError.SessionActive)
        } else {
            caught { deleteEverything(fresh) { unusedCodes = it } }
        }

    /** After the guard: a completed run releases the app; a failed one that left the marker holds it. */
    private fun settled(deleted: Outcome<Unit, DomainError>): Outcome<Unit, DomainError> {
        when {
            deleted is Outcome.Success -> {
                pendingDeletion.complete()
                afterDeletion(unusedCodes)
            }

            deleted is Outcome.Failure && deleted.error != DomainError.SessionActive -> {
                log(OPERATION, deleted.error)
                // Half done: hold the app until a run completes (a failure before the marker changed nothing).
                if (marker.exists()) pendingDeletion.hold()
            }
        }
        return deleted
    }

    /** Steps 1 to 10; the first failure stops it and is returned. [onUnused] gets the codes for [afterDeletion]. */
    private suspend fun deleteEverything(
        fresh: Boolean,
        onUnused: (List<Int>) -> Unit,
    ): Outcome<Unit, DomainError> {
        var deletedAlarms = 0
        var kept = KeptPayments(records = 0, pendingGrants = 0)
        return marker
            .write(resetAttempts = fresh)
            .flatMap {
                cancelAlarms().map { cancelled ->
                    deletedAlarms = cancelled.stored
                    onUnused(cancelled.unused)
                }
            }.flatMap { eraser.deleteAlarmData() }
            .flatMap { history.deleteAllHistory() }
            .flatMap { ledger.deleteSettledRecords().map { kept = it } }
            .flatMap { eraser.deletePurchaseIntents(keepSessions = kept.pendingSessions) }
            .flatMap { activeSession.clear() }
            .flatMap { eraser.clearSettings() }
            .flatMap { if (kept.owed) Outcome.Success(Unit) else installId.resetInstallId() }
            // A purchase recovery that ran during steps 4 to 7 may have recorded a stranded payment (review item 5).
            .flatMap { ledger.deleteSettledRecords().map { kept = it } }
            .flatMap { marker.remove() }
            .map { logger.log(LogEvent.AllDataDeleted(alarms = deletedAlarms, keptRecords = kept.records, at = clock.now())) }
    }

    /** Step 2: the stored codes, the test alarm and the session slot. */
    private suspend fun cancelAlarms(): Outcome<CancelledAlarms, DomainError> =
        alarms.listAll().flatMap { stored ->
            highWaterMark.highWaterMark().flatMap { mark ->
                val storedCodes = stored.map { it.requestCode }.distinct()
                alarmCodes.cancelAll(storedCodes + RequestCodes.TEST_ALARM).flatMap {
                    alarmCodes.cancelSessionSlot().map {
                        val unused = (RequestCodes.FIRST_ALARM..mark).filterNot { code -> code in storedCodes }
                        CancelledAlarms(stored = stored.size, unused = unused)
                    }
                }
            }
        }

    /** After the guarded part: nothing here can fail the deletion, a failure is only logged. */
    private fun afterDeletion(unused: List<Int>) {
        alarmCodes.cancelUnused(unused)
        crashReports.deleteUnsent()
        analytics.reset()
        val enqueued = work.enqueue(PriceRefreshJobs.NOW)
        if (enqueued is Outcome.Failure) log(ENQUEUE_PRICE_REFRESH, enqueued.error)
    }

    /** A port that throws instead of failing is a failed step (a `StorageFailure` naming the exception type). */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun caught(block: suspend () -> Outcome<Unit, DomainError>): Outcome<Unit, DomainError> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e::class.simpleName ?: "exception"))
        }

    private fun log(
        operation: String,
        error: DomainError,
    ) = logger.log(LogEvent.OperationFailed.of(operation, error))

    /** Step 2's result: how many alarms were stored, and the issued codes none of them used. */
    private class CancelledAlarms(
        val stored: Int,
        val unused: List<Int>,
    )

    companion object {
        /** The operation a failed deletion is logged under. */
        const val OPERATION = "delete all data"

        /** The operation logged when the price refresh after a deletion could not be enqueued. */
        const val ENQUEUE_PRICE_REFRESH = "enqueue price refresh after deleting all data"

        /** The operation logged when some unused request codes could not be cancelled after a deletion. */
        const val CANCEL_UNUSED_CODES = "cancel unused request codes after deleting all data"
    }
}

/**
 * The scheduler calls of "Delete all data" (Story 5.9). [cancelAll] and [cancelSessionSlot] are step 2, inside the
 * guard. [cancelUnused] runs after it: the issued codes no stored alarm used, where nothing should be armed, so a ring
 * never waits for them (review item 10). A failure there is logged once with the count.
 */
internal class DeletionAlarmCodes(
    private val scheduler: AlarmScheduler,
    private val logger: Logger,
) {
    /** Cancels [codes] in order; the first failure stops it and is returned. */
    fun cancelAll(codes: List<Int>): Outcome<Unit, DomainError> =
        codes
            .asSequence()
            .map { code -> scheduler.cancel(code) }
            .filterIsInstance<Outcome.Failure<DomainError>>()
            .firstOrNull() ?: Outcome.Success(Unit)

    fun cancelSessionSlot(): Outcome<Unit, DomainError> = scheduler.cancelSessionSlot()

    fun cancelUnused(codes: List<Int>) {
        val failed = codes.count { scheduler.cancel(it) is Outcome.Failure }
        if (failed > 0) logger.log(LogEvent.OperationFailed(DeleteAllData.CANCEL_UNUSED_CODES, "$failed of ${codes.size} codes"))
    }
}
