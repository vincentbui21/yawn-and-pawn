package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.billing.PurchaseRecordsRead
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * The local date and zone stats are computed for. Equal when the date and the zone id are equal ([zone] is read once from
 * [zoneId] and is not part of equality, because `TimeZone` equality is not promised across instances).
 */
data class StatsDay(
    val today: LocalDate,
    val zoneId: String,
) {
    val zone: TimeZone = TimeZone.of(zoneId)
}

/**
 * The current [StatsDay]: once at start, then after each time signal (a minute tick, a clock set, a zone change) or
 * [days]' `refresh`, only when the date or the zone changed. Nothing ticks while nobody collects.
 */
class DayTicker(
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val timeChanges: TimeChangeSignal,
) {
    /** Today's local date in the phone's current zone. */
    fun current(): StatsDay {
        val zone = timeZoneProvider.current()
        return StatsDay(clock.now().toLocalDateTime(zone).date, zone.id)
    }

    /** [current] at start and after every signal or [refresh] (a screen's resume), without repeats. */
    fun days(refresh: Flow<Unit> = emptyFlow()): Flow<StatsDay> =
        merge(timeChanges.changes(), refresh)
            .onStart { emit(Unit) }
            .map { current() }
            .distinctUntilChanged()
}

/** Everything Home (6.3) and Progress (6.5) show, for [today] in [zone] (FR-PRG-1, FR-PRG-2, FR-PRG-3). */
data class ProgressSnapshot(
    val today: LocalDate,
    val zone: TimeZone,
    val currentStreak: Int,
    val bestStreak: Int,
    val onTimeRate7Days: Int?,
    val onTimeRate30Days: Int?,
    val averageTimeToUp: Duration?,
    val snoozesLast30Days: Int,
    val insight: InsightLine?,
    val paidThisWeek: List<Money>,
    val paidThisMonth: List<Money>,
    val paidAllTime: List<Money>,
    /** At least one history row is stored (any outcome): Home shows the hero (6.3), Progress leaves its empty state (6.5). */
    val hasHistory: Boolean,
    /** Stored purchase rows that could not be read; the totals leave them out. */
    val unreadablePurchases: Int,
    /** The inputs, for 6.5's month paging and 6.7's Day detail without a second read. */
    val history: List<SessionHistoryRow>,
    val records: List<PurchaseRecord>,
)

/** Pure: everything Home and Progress show, for [today] in [zone]. */
fun progressSnapshot(
    history: List<SessionHistoryRow>,
    records: PurchaseRecordsRead,
    today: LocalDate,
    zone: TimeZone,
): ProgressSnapshot =
    ProgressSnapshot(
        today = today,
        zone = zone,
        currentStreak = currentStreak(history, today, zone),
        bestStreak = bestStreak(history, zone),
        onTimeRate7Days = onTimeRate(history, today, zone, WakeStats.RATE_DAYS_SHORT),
        onTimeRate30Days = onTimeRate(history, today, zone, WakeStats.RATE_DAYS_LONG),
        averageTimeToUp = averageTimeToUp(history, today, zone),
        snoozesLast30Days = snoozesLast30Days(history, today, zone),
        insight = insight(history, today, zone),
        paidThisWeek = moneyPaid(records.records, MoneyPeriod.ThisWeek, today, zone),
        paidThisMonth = moneyPaid(records.records, MoneyPeriod.ThisMonth, today, zone),
        paidAllTime = moneyPaid(records.records, MoneyPeriod.AllTime, today, zone),
        hasHistory = history.isNotEmpty(),
        unreadablePurchases = records.unreadable,
        history = history,
        records = records.records,
    )

/**
 * FR-PRG-1/2/3: recomputed when session_history or the purchase records change, and when the local date or zone
 * changes. [computeContext] runs the computation off the caller's thread (Dispatchers.Default in the app; tests pass
 * their test dispatcher). Storage failures propagate to the collector. A read only: it never writes either table.
 */
class ObserveProgress(
    private val history: SessionHistoryRepository,
    private val records: PurchaseRecordRepository,
    private val days: DayTicker,
    private val computeContext: CoroutineContext = Dispatchers.Default,
) {
    operator fun invoke(refresh: Flow<Unit> = emptyFlow()): Flow<ProgressSnapshot> =
        combine(history.observeAll(), records.observeAll(), days.days(refresh)) { rows, read, day ->
            progressSnapshot(rows, read, day.today, day.zone)
        }.distinctUntilChanged()
            .flowOn(computeContext)
}
