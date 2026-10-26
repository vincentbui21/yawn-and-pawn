package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/*
 * Story 6.1: the streak, on-time rate and time-to-up numbers that Home, Progress, the celebration and the weekly summary
 * share (FR-PRG-1, FR-PRG-2). Pure: the history comes from `session_history` (written only by `SessionRecorder`), and
 * `today` and the zone come from the Story 1.6 ports at the call site (AD-3). Nothing here is stored (AD-18).
 *
 * The Epic 6 rules (owner-approved default 2026-09-26):
 * - the day of a session is the local date of `scheduled_at` in the given zone, so a 23:50 alarm that ends at 00:10
 *   belongs to the earlier day;
 * - a session counts only with outcome OnTime, Snoozed or Missed (never Test, Skipped or a running session);
 * - a good day has at least one counted session and every counted session on it is OnTime; a day without a counted
 *   session neither extends nor breaks a streak.
 */

/** The thresholds of the wake stats (FR-PRG-2). */
object WakeStats {
    /** The short on-time rate window, in local days up to and including today. */
    const val RATE_DAYS_SHORT = 7

    /** The long on-time rate window (the Progress tile), in local days up to and including today. */
    const val RATE_DAYS_LONG = 30

    /** The average time to get up covers this many local days up to and including today. */
    const val AVERAGE_DAYS = 30
}

/** The local date of [row]'s `scheduled_at` in [zone]: the day the session belongs to. */
fun sessionDay(
    row: SessionHistoryRow,
    zone: TimeZone,
): LocalDate = row.scheduledAt.toLocalDateTime(zone).date

/** Whether [row] counts toward streaks and rates: outcome OnTime, Snoozed or Missed only. */
fun isCounted(row: SessionHistoryRow): Boolean =
    when (row.outcome) {
        SessionOutcome.OnTime, SessionOutcome.Snoozed, SessionOutcome.Missed -> true
        SessionOutcome.Skipped, SessionOutcome.Test, null -> false
    }

/**
 * The current zero-snooze streak: the run of good days that ends at the latest day on or before [today] with a counted
 * session. Days after [today] (the clock was set back) are ignored. 0 when that latest day is not a good day, or when
 * nothing is counted.
 */
fun currentStreak(
    history: List<SessionHistoryRow>,
    today: LocalDate,
    zone: TimeZone,
): Int =
    countedDays(history, zone)
        .filter { (day, _) -> day <= today }
        .sortedByDescending { (day, _) -> day }
        .takeWhile { (_, good) -> good }
        .size

/** The longest run of good days in the whole [history], in date order (days without a counted session are skipped). */
fun bestStreak(
    history: List<SessionHistoryRow>,
    zone: TimeZone,
): Int {
    var best = 0
    var run = 0
    for ((_, good) in countedDays(history, zone).sortedBy { (day, _) -> day }) {
        run = if (good) run + 1 else 0
        best = maxOf(best, run)
    }
    return best
}

/**
 * The share of counted sessions in the last [days] local days (`today − (days − 1)` through [today]) that were OnTime,
 * as a whole percent rounded half up; null when the window has no counted session. [days] must be at least 1.
 */
fun onTimeRate(
    history: List<SessionHistoryRow>,
    today: LocalDate,
    zone: TimeZone,
    days: Int,
): Int? {
    require(days >= 1) { "days must be at least 1, was $days" }
    val inWindow = window(today, days)
    val counted = history.filter { isCounted(it) && sessionDay(it, zone) in inWindow }
    if (counted.isEmpty()) return null
    val onTime = counted.count { it.outcome == SessionOutcome.OnTime }
    return (onTime * PERCENT * 2 + counted.size) / (2 * counted.size)
}

/**
 * The mean time from the first ring to up over OnTime and Snoozed sessions in the last [WakeStats.AVERAGE_DAYS]
 * local days up to and including [today] (for a Snoozed session it includes the snooze time). Missed sessions and rows
 * without `time_to_complete_ms` are left out; null when nothing is left.
 */
fun averageTimeToUp(
    history: List<SessionHistoryRow>,
    today: LocalDate,
    zone: TimeZone,
): Duration? {
    val days = window(today, WakeStats.AVERAGE_DAYS)
    val times =
        history
            .filter { it.outcome == SessionOutcome.OnTime || it.outcome == SessionOutcome.Snoozed }
            .filter { sessionDay(it, zone) in days }
            .mapNotNull { it.timeToCompleteMs }
    if (times.isEmpty()) return null
    return times.sum().milliseconds / times.size
}

/**
 * [duration] in whole minutes, rounded half up (2 min 29 s → 2, 2 min 30 s → 3). 0 means "under a minute" for the UI:
 * exactly the durations under 30 s. A negative duration counts as 0.
 */
fun wholeMinutes(duration: Duration): Int {
    val millis = duration.inWholeMilliseconds.coerceAtLeast(0)
    return ((millis + HALF_MINUTE_MS) / MINUTE_MS).toInt()
}

/** Each day with a counted session, mapped to whether it is a good day (every counted session OnTime). */
private fun countedDays(
    history: List<SessionHistoryRow>,
    zone: TimeZone,
): List<Pair<LocalDate, Boolean>> =
    history
        .filter(::isCounted)
        .groupBy { sessionDay(it, zone) }
        .map { (day, rows) -> day to rows.all { it.outcome == SessionOutcome.OnTime } }

/** The [days] local dates ending with [today]. */
private fun window(
    today: LocalDate,
    days: Int,
): ClosedRange<LocalDate> = today.minus(days - 1, DateTimeUnit.DAY)..today

private const val PERCENT = 100
private const val MINUTE_MS = 60_000L
private const val HALF_MINUTE_MS = 30_000L
