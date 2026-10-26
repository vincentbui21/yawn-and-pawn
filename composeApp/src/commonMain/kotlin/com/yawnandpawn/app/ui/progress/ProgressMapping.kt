package com.yawnandpawn.app.ui.progress

import com.yawnandpawn.app.core.stats.DayOutcome
import com.yawnandpawn.app.core.stats.InsightLine
import com.yawnandpawn.app.core.stats.ProgressSnapshot
import com.yawnandpawn.app.core.stats.wholeMinutes
import kotlinx.datetime.LocalDate

/*
 * Story 6.5: Progress' screen state from Story 6.2's ProgressSnapshot. Pure: every number comes from core.stats (AD-18) and
 * the UI only renames it; amounts stay per currency and are formatted only by formatMoney on the screen (AD-8).
 */

/**
 * Progress for [snapshot] with the chip of [selection] (dropped when its day left the ring, see [keptSelection]). With no
 * finished session stored the stats are `null` (the empty state); otherwise the tiles, the streak card and Money paid show.
 * The calendar is this month's, without markers or paging until Story 6.6.
 */
internal fun progressStateOf(
    snapshot: ProgressSnapshot,
    selection: DaySelection?,
): ProgressUiState {
    val ring = snapshot.lastMornings.map { RingDay(it.date, it.outcome.toUi(), it.fallbackUsed) }
    val calendar = thisMonth(snapshot.today)
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

/** This month's calendar on [today], with no markers and no other months (Story 6.6 fills and pages it). */
internal fun thisMonth(today: LocalDate): CalendarMonth =
    CalendarMonth(
        firstDay = LocalDate(today.year, today.month, 1),
        days = emptyList(),
        today = today,
        hasPrevious = false,
        hasNext = false,
    )

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
 * [selection] while its day is still there: a ring selection only while its date is a ring day with an outcome (so a chip
 * whose day left the ring at midnight is cleared, and its outcome follows the day's). A calendar selection stays as it is
 * (Story 6.6 refines it).
 */
internal fun keptSelection(
    selection: DaySelection?,
    ring: List<RingDay>,
    calendar: CalendarMonth?,
): DaySelection? {
    if (selection == null) return null
    if (selection.inCalendar) return selection.takeIf { calendar != null }
    val outcome = ring.firstOrNull { it.date == selection.date }?.outcome ?: return null
    return selection.copy(outcome = outcome)
}
