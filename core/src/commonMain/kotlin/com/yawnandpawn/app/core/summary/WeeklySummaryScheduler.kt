package com.yawnandpawn.app.core.summary

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.stats.nextWeeklySummaryAt
import com.yawnandpawn.app.core.stats.weeklySummaryDue
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.SchedulePolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * Keeps the weekly summary's one unique work (Story 6.8, AD-17) at the next Sunday 19:00 local while the switch is on,
 * and cancelled while it is off. WorkManager keeps its database in credential-protected storage, so nothing is handed
 * over while the user is locked: the unlock signals call [start] again. Calls are serialized.
 *
 * Three ways in, so the running summary is never cancelled by its own successor (WorkManager REPLACE cancels a running
 * work of the same name):
 * - [start] (app start and the unlock signals, once per process): KEEP. A pending or running work stays as it is; with
 *   none, one is enqueued at the next target (or at once while a run is due).
 * - [sync] (a boot, time or zone change, the switch): REPLACE at the next target, re-aligned to the current zone; KEEP
 *   at once while a run is due and unhandled (`weeklySummaryDue`).
 * - [afterRun] (the run's last step): chained after the run (APPEND_OR_REPLACE) at the next target.
 *
 * A store that cannot be read counts as on with no week handled (logged); a hand-over that fails is logged and returned.
 */
class WeeklySummaryScheduler(
    private val work: BackgroundWork,
    private val store: WeeklySummaryStore,
    private val clock: Clock,
    private val zones: TimeZoneProvider,
    private val userLock: UserLockState,
    private val logger: Logger,
) {
    private val lock = Mutex()
    private var started = false

    /** App start and the unlock signals: keeps what is pending ([SchedulePolicy.KeepPending]); once per process. */
    suspend fun start(): Outcome<Unit, DomainError> =
        lock.withLock {
            if (started || !userLock.isUserUnlocked()) return@withLock Outcome.Success(Unit)
            align(keepPending = true).also { started = it is Outcome.Success }
        }

    /** A boot, time or zone change, or the switch: re-aligns to the next target in the current zone. Locked: nothing. */
    suspend fun sync(): Outcome<Unit, DomainError> =
        lock.withLock {
            if (!userLock.isUserUnlocked()) Outcome.Success(Unit) else align(keepPending = false)
        }

    /** The last step of a run: next week's work chained after it while the switch is on; nothing when it is off. */
    suspend fun afterRun(): Outcome<Unit, DomainError> =
        lock.withLock {
            if (!prefs().enabled) return@withLock Outcome.Success(Unit)
            // A run cancelled meanwhile (a sync replaced it, and so enqueued its successor) chains nothing.
            currentCoroutineContext().ensureActive()
            val now = clock.now()
            logged(work.scheduleWeeklySummary(nextWeeklySummaryAt(now, zones.current()) - now, SchedulePolicy.AfterRun))
        }

    private suspend fun align(keepPending: Boolean): Outcome<Unit, DomainError> {
        val prefs = prefs()
        if (!prefs.enabled) return logged(work.cancelWeeklySummary())
        val now = clock.now()
        val zone = zones.current()
        val result =
            when {
                weeklySummaryDue(now, zone, prefs.lastHandledWeek) -> work.scheduleWeeklySummary(Duration.ZERO, SchedulePolicy.KeepPending)
                keepPending -> work.scheduleWeeklySummary(nextWeeklySummaryAt(now, zone) - now, SchedulePolicy.KeepPending)
                else -> work.scheduleWeeklySummary(nextWeeklySummaryAt(now, zone) - now, SchedulePolicy.Replace)
            }
        return logged(result)
    }

    private suspend fun prefs(): WeeklySummaryPrefs =
        when (val read = store.read()) {
            is Outcome.Success -> {
                read.value
            }

            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of(SCHEDULE_WEEKLY_SUMMARY, read.error))
                WeeklySummaryPrefs()
            }
        }

    private fun logged(result: Outcome<Unit, DomainError>): Outcome<Unit, DomainError> {
        if (result is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(SCHEDULE_WEEKLY_SUMMARY, result.error))
        return result
    }

    companion object {
        /** The operation logged when the weekly summary work could not be aligned. */
        const val SCHEDULE_WEEKLY_SUMMARY = "schedule weekly summary"
    }
}
