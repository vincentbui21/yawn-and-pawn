package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.billing.PriceSnapshotLookup
import com.yawnandpawn.app.core.billing.PurchaseLedger
import com.yawnandpawn.app.core.crash.UnsentCrashReports
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reset.DeleteAllData
import com.yawnandpawn.app.core.reset.DeletionMarker
import com.yawnandpawn.app.core.reset.LocalDataEraser
import com.yawnandpawn.app.core.reset.PendingDeletion
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.telemetry.AnalyticsDataReset
import kotlin.coroutines.EmptyCoroutineContext

/**
 * In-memory [DeletionMarker] (Story 5.9): [present] is the file, [starts] its start count. Set [failure] to make writing
 * and removing fail.
 */
class FakeDeletionMarker(
    var present: Boolean = false,
    var starts: Int = 0,
) : DeletionMarker {
    var failure: DomainError? = null

    override fun exists(): Boolean = present

    override suspend fun write(resetAttempts: Boolean): Outcome<Unit, DomainError> =
        guarded {
            if (resetAttempts || !present) starts = 0
            present = true
        }

    override suspend fun remove(): Outcome<Unit, DomainError> = guarded { present = false }

    override fun countStart(): Int = if (present) ++starts else 0

    private fun guarded(change: () -> Unit): Outcome<Unit, DomainError> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(change())
}

/**
 * [LocalDataEraser] over counters (Story 5.9): [alarmDataDeletes], [intentDeletes] and [settingsClears] count the
 * successful calls. Set [failure] to make every call fail (nothing is counted).
 */
class FakeLocalDataEraser : LocalDataEraser {
    var failure: DomainError? = null

    var alarmDataDeletes: Int = 0
        private set
    var intentDeletes: Int = 0
        private set
    var settingsClears: Int = 0
        private set

    /** The sessions whose intents the last intent delete kept. */
    var keptIntentSessions: Set<String> = emptySet()
        private set

    /** Runs when the alarm data is deleted, so a test can empty its alarm fake with it. */
    var onDeleteAlarmData: () -> Unit = {}

    override suspend fun deleteAlarmData(): Outcome<Unit, DomainError> =
        guarded {
            alarmDataDeletes++
            onDeleteAlarmData()
        }

    override suspend fun deletePurchaseIntents(keepSessions: Set<String>): Outcome<Unit, DomainError> =
        guarded {
            intentDeletes++
            keptIntentSessions = keepSessions
        }

    override suspend fun clearSettings(): Outcome<Unit, DomainError> = guarded { settingsClears++ }

    private fun guarded(change: () -> Unit): Outcome<Unit, DomainError> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(change())
}

/** [AnalyticsDataReset] that counts the resets in [resets] ("Delete all data", Story 5.9 with 5.6). */
class FakeAnalyticsDataReset : AnalyticsDataReset {
    var resets: Int = 0
        private set

    override fun reset() {
        resets++
    }
}

/** [UnsentCrashReports] that counts the deletes in [deletes]. */
class FakeUnsentCrashReports : UnsentCrashReports {
    var deletes: Int = 0
        private set

    override fun deleteUnsent() {
        deletes++
    }
}

/**
 * A [DeleteAllData] on fakes only (Story 5.9), running on the caller's dispatcher: one stored alarm (code 1000), an empty
 * purchase ledger and the given [sessionLock]. Pass the fakes a test inspects.
 */
@Suppress("LongParameterList")
fun aDeleteAllData(
    sessionLock: SessionLockGuard,
    marker: FakeDeletionMarker = FakeDeletionMarker(),
    eraser: FakeLocalDataEraser = FakeLocalDataEraser(),
    scheduler: FakeAlarmScheduler = FakeAlarmScheduler(),
    installIds: FakeInstallIdProvider = FakeInstallIdProvider(),
    work: FakeBackgroundWork = FakeBackgroundWork(),
    analytics: FakeAnalyticsDataReset = FakeAnalyticsDataReset(),
    logger: FakeLogger = FakeLogger(),
    pendingDeletion: PendingDeletion = PendingDeletion(initiallyPending = false),
): DeleteAllData {
    val clock = FakeClock()
    return DeleteAllData(
        lock = AlarmWriteLock(),
        sessionLock = sessionLock,
        marker = marker,
        alarms = FakeAlarmRepository(listOf(anAlarm(requestCode = 1000))),
        highWaterMark = FakeRequestCodeSequence(lastUsed = 1000),
        scheduler = scheduler,
        eraser = eraser,
        history = FakeSessionHistoryRepository(),
        ledger =
            PurchaseLedger(
                FakeGrantLedgerStore(),
                FakePurchaseRecordRepository(),
                FakePurchaseIntentStore(),
                FakeBilling(),
                FakeBackgroundWork(),
                PriceSnapshotLookup.None,
                clock,
                logger,
            ),
        activeSession = FakeActiveSessionStore(),
        installId = installIds,
        crashReports = FakeUnsentCrashReports(),
        analytics = analytics,
        work = work,
        clock = clock,
        logger = logger,
        pendingDeletion = pendingDeletion,
        context = EmptyCoroutineContext,
    )
}
