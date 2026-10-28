package com.yawnandpawn.app.core.summary

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceSource
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.billing.PurchaseRecordsRead
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.stats.IsoWeek
import com.yawnandpawn.app.core.stats.WeeklySummary
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.SchedulePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/*
 * Test-local doubles for the Story 6.8 runtime tests: `:core` tests may not use `:testing` (CoreDependencyRules), so these
 * stand in for FakeBackgroundWork, FakeWeeklySummaryStore, FakeWeeklySummaryNotifier, FakeClock and FakeLogger.
 */

internal val Berlin: TimeZone = TimeZone.of("Europe/Berlin")

/** [text] ("2027-03-07T19:00") in Berlin. */
internal fun berlin(text: String): Instant = LocalDateTime.parse(text).toInstant(Berlin)

/** Every call the weekly summary makes on the background work port, in order. */
internal sealed interface WorkCall {
    data class Schedule(
        val delay: Duration,
        val policy: SchedulePolicy,
    ) : WorkCall

    data object Cancel : WorkCall
}

/** [BackgroundWork] that records the weekly summary calls in [calls]; set [failure] to refuse them. */
internal class WeeklyWork : BackgroundWork {
    val calls = mutableListOf<WorkCall>()
    var failure: DomainError? = null

    /** Called inside each weekly call, before it is recorded (to observe what runs concurrently). */
    var during: () -> Unit = {}

    override fun enqueue(job: BackgroundJob): Outcome<Unit, DomainError> = error("the weekly summary enqueues no BackgroundJob")

    override fun scheduleWeeklySummary(
        delay: Duration,
        policy: SchedulePolicy,
    ): Outcome<Unit, DomainError> = record(WorkCall.Schedule(delay, policy))

    override fun cancelWeeklySummary(): Outcome<Unit, DomainError> = record(WorkCall.Cancel)

    override fun schedulePreAlarm(
        alarmId: String,
        delay: Duration,
    ): Outcome<Unit, DomainError> = Outcome.Success(Unit)

    override fun cancelPreAlarm(alarmId: String): Outcome<Unit, DomainError> = Outcome.Success(Unit)

    private fun record(call: WorkCall): Outcome<Unit, DomainError> {
        during()
        failure?.let { return Outcome.Failure(it) }
        calls += call
        return Outcome.Success(Unit)
    }
}

/** [WeeklySummaryStore] in memory; [failRead] and [failWrite] refuse reads and writes. */
internal class MemoryWeeklyStore(
    initial: WeeklySummaryPrefs = WeeklySummaryPrefs(),
) : WeeklySummaryStore {
    val state = MutableStateFlow(initial)
    var failRead: DomainError? = null
    var failWrite: DomainError? = null
    val writes = mutableListOf<String>()

    override fun observe(): Flow<WeeklySummaryPrefs> = state

    override suspend fun read(): Outcome<WeeklySummaryPrefs, DomainError> =
        failRead?.let { Outcome.Failure(it) } ?: Outcome.Success(state.value)

    override suspend fun setEnabled(on: Boolean): Outcome<Unit, DomainError> = write("enabled=$on") { it.copy(enabled = on) }

    override suspend fun setLastHandledWeek(week: IsoWeek): Outcome<Unit, DomainError> =
        write("week=$week") { it.copy(lastHandledWeek = week) }

    private fun write(
        what: String,
        change: (WeeklySummaryPrefs) -> WeeklySummaryPrefs,
    ): Outcome<Unit, DomainError> {
        failWrite?.let { return Outcome.Failure(it) }
        writes += what
        state.value = change(state.value)
        return Outcome.Success(Unit)
    }
}

/** [WeeklySummaryNotifier] that keeps every posted summary; [allowed] answers [canPost], [failure] refuses posts. */
internal class MemoryNotifier(
    var allowed: Boolean = true,
) : WeeklySummaryNotifier {
    val posted = mutableListOf<WeeklySummary>()
    var failure: DomainError? = null

    override fun canPost(): Boolean = allowed

    override fun post(summary: WeeklySummary): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        posted += summary
        return Outcome.Success(Unit)
    }
}

/** A wall clock stopped at [now] until set. */
internal class StoppedClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

/** A zone provider answering [zone] until set. */
internal class SetZone(
    var zone: TimeZone = Berlin,
) : TimeZoneProvider {
    override fun current(): TimeZone = zone
}

/** The user lock: unlocked unless told otherwise. */
internal class Lock(
    var unlocked: Boolean = true,
) : UserLockState {
    override fun isUserUnlocked(): Boolean = unlocked

    override fun observe(): Flow<Boolean> = flowOf(unlocked)
}

internal class Logs : Logger {
    val events = mutableListOf<LogEvent>()

    override fun log(event: LogEvent) {
        events += event
    }
}

/** Session history in memory for [all]; [failure] makes it fail. */
internal class History(
    var rows: List<SessionHistoryRow> = emptyList(),
) : SessionHistoryRepository {
    var failure: DomainError? = null

    override suspend fun all(): Outcome<List<SessionHistoryRow>, DomainError> =
        failure?.let { Outcome.Failure(it) } ?: Outcome.Success(rows)

    override suspend fun upsert(row: SessionHistoryRow): Outcome<Unit, DomainError> = error("a read only")

    override suspend fun find(sessionId: String): Outcome<SessionHistoryRow?, DomainError> = error("a read only")

    override fun observeLatestMissed(): Flow<SessionHistoryRow?> = emptyFlow()

    override suspend fun deleteSkipped(
        alarmId: String,
        scheduledAt: Instant,
    ): Outcome<Unit, DomainError> = Outcome.Success(Unit)

    override fun observeAll(): Flow<List<SessionHistoryRow>> = emptyFlow()

    override suspend fun recordMerge(merge: SessionMergeRow): Outcome<Unit, DomainError> = error("a read only")

    override suspend fun merges(sessionId: String): Outcome<List<SessionMergeRow>, DomainError> = Outcome.Success(emptyList())
}

/** Purchase records in memory for [all]; [failure] makes it fail. */
internal class Records(
    var records: List<PurchaseRecord> = emptyList(),
) : PurchaseRecordRepository {
    var failure: DomainError? = null

    override suspend fun all(): Outcome<List<PurchaseRecord>, DomainError> =
        failure?.let { Outcome.Failure(it) } ?: Outcome.Success(records)

    override fun observeAll(): Flow<PurchaseRecordsRead> = flowOf(PurchaseRecordsRead(records))

    override suspend fun get(tokenHash: String): Outcome<PurchaseRecord?, DomainError> = Outcome.Success(null)

    override suspend fun putRecord(record: PurchaseRecord): Outcome<Unit, DomainError> = error("a read only")

    override suspend fun deleteNotOwed(keep: Set<String>): Outcome<Int, DomainError> = error("a read only")
}

private var sessionNumber = 0

/** A session scheduled at [at] ("2027-03-01T06:30", Berlin) ending as [outcome]. */
internal fun session(
    at: String,
    outcome: SessionOutcome = SessionOutcome.OnTime,
): SessionHistoryRow {
    val scheduledAt = berlin(at)
    return SessionHistoryRow(
        sessionId = "s${++sessionNumber}",
        alarmId = "a",
        scheduledAt = scheduledAt,
        firstRingAt = scheduledAt,
        endedAt = scheduledAt + 3.minutes,
        snoozeCount = if (outcome == SessionOutcome.Snoozed) 1 else 0,
        checkTypes = listOf("Math"),
        timeToCompleteMs = 3.minutes.inWholeMilliseconds,
        fallbackUsed = false,
        directBoot = false,
        outcome = outcome,
    )
}

/** A consumed payment of [price] at [at] ("2027-03-01T06:35", Berlin), priced from its intent. */
internal fun payment(
    at: String,
    price: Money,
): PurchaseRecord {
    val purchasedAt = berlin(at)
    return PurchaseRecord(
        tokenHash = "t${++sessionNumber}",
        orderId = null,
        productId = "snooze_usd_01",
        sessionId = "s",
        alarmId = "a",
        snoozeNumber = 1,
        price = price,
        priceSource = PriceSource.Intent,
        purchasedAt = purchasedAt,
        status = RecordStatus.Consumed,
        consumedAt = purchasedAt + 1.minutes,
        updatedAt = purchasedAt,
    )
}
