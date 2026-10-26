package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/*
 * Story 6.8: the Sunday-evening weekly summary (FR-PRG-5). Pure: when it is due, which week a run summarises, and what
 * that week says. `now` and the zone come from the Story 1.6 ports at the call site (AD-3); nothing here is stored
 * except the last handled week id, by the caller (AD-18).
 */

/** An ISO-8601 week: weeks start on Monday, and week 1 holds the year's first Thursday. Stored as "2027-W01". */
data class IsoWeek(
    val year: Int,
    val week: Int,
) : Comparable<IsoWeek> {
    init {
        require(week in 1..MAX_WEEKS) { "week must be 1 to $MAX_WEEKS, was $week" }
    }

    /** The Monday that starts this week. */
    fun monday(): LocalDate {
        // January 4 is always in week 1.
        val fourth = LocalDate(year, 1, FIRST_WEEK_DAY)
        val firstMonday = fourth.minus(fourth.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        return firstMonday.plus((week - 1) * DAYS_IN_WEEK, DateTimeUnit.DAY)
    }

    override fun compareTo(other: IsoWeek): Int = compareValuesBy(this, other, { it.year }, { it.week })

    /** "YYYY-Www", zero-padded (common code: no `String.format`). */
    override fun toString(): String = "${year.toString().padStart(YEAR_DIGITS, '0')}-W${week.toString().padStart(2, '0')}"

    companion object {
        private const val MAX_WEEKS = 53
        private const val FIRST_WEEK_DAY = 4
        private const val YEAR_DIGITS = 4
        private val TEXT = Regex("""(\d{4})-W(\d{2})""")

        /** The ISO week of [date], through the Thursday of its week (which decides the week's year). */
        fun of(date: LocalDate): IsoWeek {
            val thursday = date.plus(DayOfWeek.THURSDAY.isoDayNumber - date.dayOfWeek.isoDayNumber, DateTimeUnit.DAY)
            return IsoWeek(thursday.year, (thursday.dayOfYear - 1) / DAYS_IN_WEEK + 1)
        }

        /** The week [text] names in [toString]'s form; null for anything else, a week 53 its year lacks included. */
        fun parse(text: String): IsoWeek? {
            val match = TEXT.matchEntire(text) ?: return null
            val week = match.groupValues[2].toInt()
            return IsoWeek(match.groupValues[1].toInt(), week.coerceIn(1, MAX_WEEKS))
                .takeIf { week in 1..MAX_WEEKS && of(it.monday()) == it }
        }
    }
}

/** When the weekly summary is due: Sunday 19:00 local (FR-PRG-5). */
object WeeklySummaryRule {
    val DAY: DayOfWeek = DayOfWeek.SUNDAY
    val TIME: LocalTime = LocalTime(19, 0)

    /** A run later than this after its target skips that week (FR-PRG-5). */
    val LATE_AFTER: Duration = 24.hours
}

/** The first Sunday 19:00 in [zone] strictly after [now] (built from local dates, so DST weeks are right). */
fun nextWeeklySummaryAt(
    now: Instant,
    zone: TimeZone,
): Instant {
    val today = now.toLocalDateTime(zone).date
    val sunday = today.plus(daysUntilSunday(today), DateTimeUnit.DAY)
    val target = targetOn(sunday, zone)
    return if (target > now) target else targetOn(sunday.plus(DAYS_IN_WEEK, DateTimeUnit.DAY), zone)
}

/** The latest Sunday 19:00 in [zone] at or before [now]. */
fun lastWeeklySummaryAt(
    now: Instant,
    zone: TimeZone,
): Instant = targetOn(lastSunday(now, zone), zone)

/** What a weekly summary run at a given time does. */
sealed interface WeeklySummaryDecision {
    /** Summarise [week], whose Sunday is [sunday]. */
    data class Summarise(
        val week: IsoWeek,
        val sunday: LocalDate,
    ) : WeeklySummaryDecision

    /** More than [WeeklySummaryRule.LATE_AFTER] past its target: [week] is skipped (and stored as handled). */
    data class SkipLate(
        val week: IsoWeek,
    ) : WeeklySummaryDecision

    /** The week was handled already: nothing to do. */
    data object AlreadyHandled : WeeklySummaryDecision
}

/**
 * For a run at [now], in the zone of now: the target is [lastWeeklySummaryAt]. Its week is [WeeklySummaryDecision.AlreadyHandled]
 * when [lastHandled] is that week or the one after (the clock was set back after a run: an older week is never summarised
 * again); a [lastHandled] further ahead is left from a clock set far forward and is ignored. Otherwise a run more than
 * [WeeklySummaryRule.LATE_AFTER] after the target (in elapsed time) is [WeeklySummaryDecision.SkipLate], else
 * [WeeklySummaryDecision.Summarise].
 */
fun weeklySummaryDecision(
    now: Instant,
    zone: TimeZone,
    lastHandled: IsoWeek?,
): WeeklySummaryDecision {
    val sunday = lastSunday(now, zone)
    val week = IsoWeek.of(sunday)
    val following = IsoWeek.of(sunday.plus(DAYS_IN_WEEK, DateTimeUnit.DAY))
    return when {
        lastHandled != null && lastHandled in week..following -> WeeklySummaryDecision.AlreadyHandled
        now - targetOn(sunday, zone) > WeeklySummaryRule.LATE_AFTER -> WeeklySummaryDecision.SkipLate(week)
        else -> WeeklySummaryDecision.Summarise(week, sunday)
    }
}

/** A run is due now: [weeklySummaryDecision] would summarise (the scheduler keeps a due run instead of replacing it). */
fun weeklySummaryDue(
    now: Instant,
    zone: TimeZone,
    lastHandled: IsoWeek?,
): Boolean = weeklySummaryDecision(now, zone, lastHandled) is WeeklySummaryDecision.Summarise

/**
 * The summary of one ISO [week]: [onTime] good days (at least one counted session, all OnTime; the Story 6.1 rules, the
 * calendar's "On time" days), [paid] per currency sorted by code (Story 6.2's [moneyPaid]), and whether it is worth
 * sending at all ([shouldSend]: the week has a counted session).
 */
data class WeeklySummary(
    val week: IsoWeek,
    val onTime: Int,
    val paid: List<Money>,
    val shouldSend: Boolean,
)

/** The summary of the ISO week holding [weekOf] (any date in it), sessions and payments placed by local date in [zone]. */
fun weeklySummary(
    history: List<SessionHistoryRow>,
    records: List<PurchaseRecord>,
    weekOf: LocalDate,
    zone: TimeZone,
): WeeklySummary {
    val days = isoWeekOf(weekOf)
    val counted = history.filter { isCounted(it) && sessionDay(it, zone) in days }
    val onTime = counted.groupBy { sessionDay(it, zone) }.count { (_, rows) -> rows.all { it.outcome == SessionOutcome.OnTime } }
    return WeeklySummary(
        week = IsoWeek.of(weekOf),
        onTime = onTime,
        paid = moneyPaid(records, MoneyPeriod.ThisWeek, weekOf, zone),
        shouldSend = counted.isNotEmpty(),
    )
}

/** Which text the notification uses (EXPERIENCE.md Key strings). */
sealed interface WeeklySummaryText {
    /** Nothing paid, [onTime] at least 1 ("… nothing paid. Nice."); the poster picks the singular for 1. */
    data class NothingPaid(
        val onTime: Int,
    ) : WeeklySummaryText

    /** Something was paid ("… {paid} paid this week."), whatever [onTime]. */
    data class Paid(
        val onTime: Int,
        val paid: List<Money>,
    ) : WeeklySummaryText

    /** No on-time morning and nothing paid: "0 on-time mornings this week. Next week's a fresh start." */
    data object NoOnTime : WeeklySummaryText
}

/** The text of this summary. */
fun WeeklySummary.text(): WeeklySummaryText =
    when {
        paid.isNotEmpty() -> WeeklySummaryText.Paid(onTime, paid)
        onTime >= 1 -> WeeklySummaryText.NothingPaid(onTime)
        else -> WeeklySummaryText.NoOnTime
    }

/** The latest Sunday whose 19:00 in [zone] is at or before [now]. */
private fun lastSunday(
    now: Instant,
    zone: TimeZone,
): LocalDate {
    val today = now.toLocalDateTime(zone).date
    val sunday = today.minus(today.dayOfWeek.isoDayNumber % DAYS_IN_WEEK, DateTimeUnit.DAY)
    return if (targetOn(sunday, zone) <= now) sunday else sunday.minus(DAYS_IN_WEEK, DateTimeUnit.DAY)
}

/** Days from [date] to the next Sunday, 0 on a Sunday. */
private fun daysUntilSunday(date: LocalDate): Int =
    (WeeklySummaryRule.DAY.isoDayNumber - date.dayOfWeek.isoDayNumber + DAYS_IN_WEEK) % DAYS_IN_WEEK

/** [WeeklySummaryRule.TIME] on [sunday] in [zone]. */
private fun targetOn(
    sunday: LocalDate,
    zone: TimeZone,
): Instant = LocalDateTime(sunday, WeeklySummaryRule.TIME).toInstant(zone)

private const val DAYS_IN_WEEK = 7
