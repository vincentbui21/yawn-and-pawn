package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/*
 * Story 6.2: snoozes over 30 days, money paid per currency, each day's outcome and the Insight line (FR-PRG-1, FR-PRG-2,
 * FR-PRG-3). Pure, next to Story 6.1's WakeStats: the history comes from `session_history` (written only by
 * `SessionRecorder`), the purchase records from `PurchaseLedger`, and `today` and the zone from the Story 1.6 ports at the
 * call site (AD-3). Amounts stay per currency and are never converted (AD-8); nothing here is stored (AD-18).
 *
 * The day of a session is 6.1's `sessionDay` and a counted session 6.1's `isCounted`. A running session (no outcome yet)
 * is left out everywhere, as if not recorded yet.
 */

/** The thresholds of the Story 6.2 stats. */
object ProgressRule {
    /** The snoozes tile covers this many local days up to and including today. */
    const val SNOOZE_DAYS = 30

    /** The Insight line compares the sessions of this many local days up to and including today. */
    const val INSIGHT_DAYS = 30

    /** The Insight line needs at least this many timed OnTime or Snoozed sessions on each side (weekdays, weekends). */
    const val INSIGHT_MIN_SESSIONS = 3

    /** The Insight line needs the two averages at least this far apart. */
    val INSIGHT_MIN_GAP: Duration = 2.minutes
}

/** The Insight line Progress shows (Q3 default rule); `null` from [insight] hides the card. */
enum class InsightLine { FastestOnWeekdays, FastestOnWeekends }

/** The period of a money total: the ISO week of today, its local calendar month, or everything. */
enum class MoneyPeriod { ThisWeek, ThisMonth, AllTime }

/**
 * The outcome of one local day: the worst counted outcome (Missed, then Snoozed, then OnTime); with nothing counted,
 * Skipped when any session was skipped, otherwise Test; [None] when no session finished that day.
 */
enum class DayOutcome { None, OnTime, Snoozed, Missed, Skipped, Test }

/**
 * One local day for the ring, the calendar and Day detail.
 *
 * @property sessionCount every session finished that day, Test and Skipped included.
 * @property fallbackUsed any finished session that day used the fallback check.
 */
data class DaySummary(
    val date: LocalDate,
    val outcome: DayOutcome,
    val sessionCount: Int,
    val fallbackUsed: Boolean,
)

/** One session of a day for Day detail (6.7). */
data class SessionOnDay(
    val row: SessionHistoryRow,
    /** Counted records of this session (Granted, Consumed, Reused), oldest purchase first, estimates included (6.7 shows them without an amount). */
    val purchases: List<PurchaseRecord>,
    /** What this session cost, one total per currency, by code; only records with an amount paid. */
    val paid: List<Money>,
    /** Stranded records of this session (refunded by Google), oldest first. */
    val stranded: List<PurchaseRecord>,
)

/**
 * The paid snoozes of counted sessions (OnTime, Snoozed, Missed) in the last [ProgressRule.SNOOZE_DAYS] local days up to
 * and including [today]. Test, Skipped and running sessions never count.
 */
fun snoozesLast30Days(
    history: List<SessionHistoryRow>,
    today: LocalDate,
    zone: TimeZone,
): Int {
    val days = window(today, ProgressRule.SNOOZE_DAYS)
    return history.filter { isCounted(it) && sessionDay(it, zone) in days }.sumOf { it.snoozeCount }
}

/**
 * The Insight line (Q3 default): the mean time to get up on weekdays (Mon to Fri) against weekends (Sat, Sun) over the
 * last [ProgressRule.INSIGHT_DAYS] local days, from OnTime and Snoozed sessions with a time to complete. The faster side
 * wins when each side has at least [ProgressRule.INSIGHT_MIN_SESSIONS] sessions and the averages are at least
 * [ProgressRule.INSIGHT_MIN_GAP] apart; otherwise null.
 */
fun insight(
    history: List<SessionHistoryRow>,
    today: LocalDate,
    zone: TimeZone,
): InsightLine? {
    val days = window(today, ProgressRule.INSIGHT_DAYS)
    val weekdays = mutableListOf<Long>()
    val weekends = mutableListOf<Long>()
    history.forEach { row ->
        val millis = row.timeToCompleteMs
        val timed = row.outcome == SessionOutcome.OnTime || row.outcome == SessionOutcome.Snoozed
        val day = sessionDay(row, zone)
        if (timed && millis != null && day in days) {
            if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) weekends += millis else weekdays += millis
        }
    }
    if (weekdays.size < ProgressRule.INSIGHT_MIN_SESSIONS || weekends.size < ProgressRule.INSIGHT_MIN_SESSIONS) return null
    val weekdayMean = weekdays.sum().milliseconds / weekdays.size
    val weekendMean = weekends.sum().milliseconds / weekends.size
    return when {
        (weekdayMean - weekendMean).absoluteValue < ProgressRule.INSIGHT_MIN_GAP -> null
        weekdayMean < weekendMean -> InsightLine.FastestOnWeekdays
        else -> InsightLine.FastestOnWeekends
    }
}

/** Status Granted, Consumed or Reused, and the price is what was charged. */
fun isPaidAmount(record: PurchaseRecord): Boolean = isCountedRecord(record) && record.priceSource.isAmountPaid

/** Monday through Sunday of [date]'s ISO week. */
fun isoWeekOf(date: LocalDate): ClosedRange<LocalDate> {
    val monday = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    return monday..monday.plus(DAYS_IN_WEEK - 1, DateTimeUnit.DAY)
}

/**
 * The money paid in [period], one total per currency sorted by ISO code, micros summed as `Long` and never converted;
 * empty when nothing was paid. Only [isPaidAmount] records count, so stranded payments and estimated prices never do. A
 * record falls in a week or month by the local date of its `purchasedAt` in [zone].
 */
fun moneyPaid(
    records: List<PurchaseRecord>,
    period: MoneyPeriod,
    today: LocalDate,
    zone: TimeZone,
): List<Money> {
    val week = isoWeekOf(today)
    val inPeriod: (LocalDate) -> Boolean =
        when (period) {
            MoneyPeriod.ThisWeek -> { date -> date in week }
            MoneyPeriod.ThisMonth -> { date -> date.year == today.year && date.month == today.month }
            MoneyPeriod.AllTime -> { _ -> true }
        }
    return totalsPerCurrency(records.filter { isPaidAmount(it) && inPeriod(it.purchasedAt.toLocalDateTime(zone).date) })
}

/** The outcome of [date]: see [DayOutcome]. */
fun dayOutcome(
    history: List<SessionHistoryRow>,
    date: LocalDate,
    zone: TimeZone,
): DaySummary = summaryOf(date, history.filter { it.outcome != null && sessionDay(it, zone) == date })

/** One DaySummary per date in [dates], in order (6.5's ring uses the last 30 days). */
fun daySummaries(
    history: List<SessionHistoryRow>,
    dates: ClosedRange<LocalDate>,
    zone: TimeZone,
): List<DaySummary> {
    val byDay = history.filter { it.outcome != null }.groupBy { sessionDay(it, zone) }
    return datesIn(dates).map { date -> summaryOf(date, byDay[date].orEmpty()) }
}

/** One DaySummary per day of [month], first day first (the calendar, 6.6). */
fun monthSummary(
    history: List<SessionHistoryRow>,
    month: YearMonth,
    zone: TimeZone,
): List<DaySummary> = daySummaries(history, month.firstDay..month.lastDay, zone)

/**
 * The finished sessions of [date], earliest `scheduledAt` first, each with its counted and stranded purchase records
 * (Day detail, 6.7).
 */
fun sessionsOn(
    history: List<SessionHistoryRow>,
    records: List<PurchaseRecord>,
    date: LocalDate,
    zone: TimeZone,
): List<SessionOnDay> {
    val rows =
        history
            .filter { it.outcome != null && sessionDay(it, zone) == date }
            .sortedWith(compareBy({ it.scheduledAt }, { it.sessionId }))
    if (rows.isEmpty()) return emptyList()
    val bySession = records.filter { it.sessionId != null }.groupBy { it.sessionId }
    return rows.map { row ->
        val own = bySession[row.sessionId].orEmpty().sortedBy { it.purchasedAt }
        val counted = own.filter(::isCountedRecord)
        SessionOnDay(
            row = row,
            purchases = counted,
            paid = totalsPerCurrency(counted.filter { it.priceSource.isAmountPaid }),
            stranded = own.filter { it.status == RecordStatus.Stranded },
        )
    }
}

/** Granted, Consumed or Reused: a payment that stands (Stranded was refunded by Google). */
private fun isCountedRecord(record: PurchaseRecord): Boolean =
    when (record.status) {
        RecordStatus.Granted, RecordStatus.Consumed, RecordStatus.Reused -> true
        RecordStatus.Stranded, RecordStatus.Absent -> false
    }

/** One total per currency of [records]' prices, sorted by ISO code. */
private fun totalsPerCurrency(records: List<PurchaseRecord>): List<Money> =
    records
        .groupBy { it.price.currency }
        .map { (currency, paid) -> Money(paid.sumOf { it.price.micros }, currency) }
        .sortedBy { it.currency }

/** The summary of [date] from its finished [rows] (each has an outcome). */
private fun summaryOf(
    date: LocalDate,
    rows: List<SessionHistoryRow>,
): DaySummary {
    val outcomes = rows.mapNotNullTo(mutableSetOf()) { it.outcome }
    val outcome =
        when {
            SessionOutcome.Missed in outcomes -> DayOutcome.Missed
            SessionOutcome.Snoozed in outcomes -> DayOutcome.Snoozed
            SessionOutcome.OnTime in outcomes -> DayOutcome.OnTime
            SessionOutcome.Skipped in outcomes -> DayOutcome.Skipped
            SessionOutcome.Test in outcomes -> DayOutcome.Test
            else -> DayOutcome.None
        }
    return DaySummary(date, outcome, sessionCount = rows.size, fallbackUsed = rows.any { it.fallbackUsed })
}

/** Every date of [range] in order; empty when it is empty. */
private fun datesIn(range: ClosedRange<LocalDate>): List<LocalDate> =
    generateSequence(range.start) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= range.endInclusive }.toList()

private const val DAYS_IN_WEEK = 7
