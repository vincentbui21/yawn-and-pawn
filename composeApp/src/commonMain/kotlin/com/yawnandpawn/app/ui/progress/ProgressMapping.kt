package com.yawnandpawn.app.ui.progress

import com.yawnandpawn.app.core.stats.DayOutcome
import com.yawnandpawn.app.core.stats.InsightLine
import com.yawnandpawn.app.core.stats.ProgressSnapshot
import com.yawnandpawn.app.core.stats.firstSessionDay
import com.yawnandpawn.app.core.stats.monthSummary
import com.yawnandpawn.app.core.stats.wholeMinutes
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

/*
 * Story 6.5: Progress' screen state from Story 6.2's ProgressSnapshot. Pure: every number comes from core.stats (AD-18) and
 * the UI only renames it; amounts stay per currency and are formatted only by formatMoney on the screen (AD-8).
 */

/**
 * Progress for [snapshot] with the chip of [selection] (dropped when its day left the ring or the shown month, see
 * [keptSelection]) and the month [calendar] (Story 6.6, [calendarOf]; this month by default). With no finished session
 * stored the stats are `null` (the empty state); otherwise the tiles, the streak card and Money paid show.
 */
internal fun progressStateOf(
    snapshot: ProgressSnapshot,
    selection: DaySelection?,
    calendar: CalendarMonth = calendarOf(snapshot, shown = null),
): ProgressUiState {
    val ring = snapshot.lastMornings.map { RingDay(it.date, it.outcome.toUi(), it.fallbackUsed) }
    // A running session (no outcome yet) is not a morning: Progress stays empty until one has finished.
    val finished = snapshot.history.any { it.outcome != null }
    return ProgressUiState(
        today = snapshot.today,
        ring = ring,
        stats =
            if (finished) {
                ProgressStats(
                    currentStreak = snapshot.currentStreak,
                    bestStreak = snapshot.bestStreak,
                    onTime30Days = snapshot.onTimeRate30Days,
                    averageMinutesToUp = snapshot.averageTimeToUp?.let(::wholeMinutes),
                    snoozes30Days = snapshot.snoozesLast30Days,
                )
            } else {
                null
            },
        calendar = calendar,
        paidThisMonth = snapshot.paidThisMonth,
        insight = snapshot.insight?.toUi(),
        selection = keptSelection(selection, ring, calendar),
    )
}

/**
 * Story 6.6: the calendar of month [shown] (`null`: today's month, which follows today across midnight) from Story 6.2's
 * [monthSummary] over the snapshot's history, in the snapshot's zone. Only days with a finished session up to today get a
 * [CalendarDay] (a day after today shows its date only, even with a row from a clock set back). Today's pill only in
 * today's month. "Previous month" stops at the month of the oldest finished session (none without history), "Next month"
 * at today's month. A shown month outside those limits (the clock was set back, or the history was deleted) shows the
 * nearest month inside them.
 */
internal fun calendarOf(
    snapshot: ProgressSnapshot,
    shown: YearMonth?,
): CalendarMonth {
    val today = snapshot.today
    val current = today.yearMonth
    val oldest = firstSessionDay(snapshot.history, snapshot.zone)?.yearMonth
    // Never after today's month, never before the oldest session's month, and today's month without history.
    val month = if (oldest == null) current else (shown ?: current).coerceIn(minOf(oldest, current), current)
    val days =
        monthSummary(snapshot.history, month, snapshot.zone).mapNotNull { summary ->
            summary.outcome
                .toUi()
                ?.takeIf { summary.date <= today }
                ?.let { CalendarDay(summary.date, it, summary.fallbackUsed, summary.sessionCount) }
        }
    return CalendarMonth(
        firstDay = month.firstDay,
        days = days,
        today = today.takeIf { month == current },
        hasPrevious = oldest != null && month > oldest,
        hasNext = month < current,
    )
}

/** The day's outcome as the ring shows it; `null` for a day with no finished session (the faint dot). */
internal fun DayOutcome.toUi(): Outcome? =
    when (this) {
        DayOutcome.None -> null
        DayOutcome.OnTime -> Outcome.OnTime
        DayOutcome.Snoozed -> Outcome.Snoozed
        DayOutcome.Missed -> Outcome.Missed
        DayOutcome.Skipped -> Outcome.Skipped
        DayOutcome.Test -> Outcome.Test
    }

internal fun InsightLine.toUi(): Insight =
    when (this) {
        InsightLine.FastestOnWeekdays -> Insight.FastestOnWeekdays
        InsightLine.FastestOnWeekends -> Insight.FastestOnWeekends
    }

/**
 * [selection] while its day is still there, with the day's current outcome: a ring selection only while its date is a
 * ring day with an outcome (so a chip whose day left the ring at midnight is cleared), a calendar selection only while its
 * date is a day of the shown month with a session (so a chip whose month was paged away or whose day lost its session is
 * cleared).
 */
internal fun keptSelection(
    selection: DaySelection?,
    ring: List<RingDay>,
    calendar: CalendarMonth?,
): DaySelection? =
    when {
        selection == null -> null
        selection.inCalendar -> calendar?.days?.firstOrNull { it.date == selection.date }?.let { selection.copy(outcome = it.outcome) }
        else -> ring.firstOrNull { it.date == selection.date }?.outcome?.let { selection.copy(outcome = it) }
    }
