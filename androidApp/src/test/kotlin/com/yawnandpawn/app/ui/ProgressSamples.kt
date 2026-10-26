package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.progress.CalendarDay
import com.yawnandpawn.app.ui.progress.CalendarMonth
import com.yawnandpawn.app.ui.progress.DaySelection
import com.yawnandpawn.app.ui.progress.Outcome
import com.yawnandpawn.app.ui.progress.ProgressStats
import com.yawnandpawn.app.ui.progress.ProgressUiState
import com.yawnandpawn.app.ui.progress.RING_DAYS
import com.yawnandpawn.app.ui.progress.RingDay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * Story 6.5 screen states of Progress as the app builds them, on Wednesday 2027-03-03: empty, Test only, EXPERIENCE.md
 * F9's typical month, two currencies, a tapped dot, a streak over 30, loading and the load failure. Story 6.6 adds the
 * calendar's markers to them (March's first days, with February before it where history reaches back) and the calendar
 * states: a past month with every outcome, a fallback day and a two-session day, its chip, an empty oldest month, and a
 * day after today with a session (the clock was set back).
 */
object ProgressSamples {
    val today: LocalDate = LocalDate(2027, 3, 3)

    private fun day(back: Int): LocalDate = today.minus(back, DateTimeUnit.DAY)

    private val calendar =
        CalendarMonth(firstDay = LocalDate(2027, 3, 1), days = emptyList(), today = today, hasPrevious = false, hasNext = false)

    /** March up to today with [outcomes] by day of the month; February has history when [hasPrevious]. */
    private fun march(
        outcomes: Map<Int, Outcome>,
        hasPrevious: Boolean = true,
    ) = calendar.copy(
        days = outcomes.map { (dayOfMonth, outcome) -> CalendarDay(LocalDate(2027, 3, dayOfMonth), outcome) },
        hasPrevious = hasPrevious,
    )

    /** The ring with [outcomes] by days back from today (0 is today); other days blank. */
    private fun ring(outcomes: Map<Int, Outcome>): List<RingDay> = (RING_DAYS - 1 downTo 0).map { RingDay(day(it), outcomes[it]) }

    val empty = ProgressUiState(today = today, ring = ring(emptyMap()), calendar = calendar)

    val testOnly =
        empty.copy(
            ring = ring(mapOf(1 to Outcome.Test, 0 to Outcome.Skipped)),
            calendar = march(mapOf(2 to Outcome.Test, 3 to Outcome.Skipped), hasPrevious = false),
            stats = ProgressStats(0, 0, onTime30Days = null, averageMinutesToUp = null, snoozes30Days = 0),
        )

    /** F9: 12 on time in a row, a snoozed morning, a test alarm, another snoozed morning; $1.00 paid this month. */
    val typical =
        empty.copy(
            ring = ring((0..11).associateWith { Outcome.OnTime } + mapOf(12 to Outcome.Snoozed, 13 to Outcome.Test, 14 to Outcome.Snoozed)),
            stats = ProgressStats(12, 12, onTime30Days = 86, averageMinutesToUp = 3, snoozes30Days = 4),
            calendar = march((1..3).associateWith { Outcome.OnTime }),
            paidThisMonth = listOf(Money(1_000_000, "USD")),
        )

    val twoCurrencies = typical.copy(paidThisMonth = listOf(Money(8_980_000, "EUR"), Money(1_000_000, "USD")))

    val dotChip = typical.copy(selection = DaySelection(day(12), Outcome.Snoozed, inCalendar = false))

    val streakOver30 =
        empty.copy(
            ring = ring((0 until RING_DAYS).associateWith { Outcome.OnTime }),
            stats = ProgressStats(42, 42, onTime30Days = 100, averageMinutesToUp = 0, snoozes30Days = 0),
            calendar = march((1..3).associateWith { Outcome.OnTime }),
        )

    private fun february(dayOfMonth: Int) = LocalDate(2027, 2, dayOfMonth)

    /**
     * February 2027, a past month (no today pill, both directions open): every outcome, a fallback check on Monday 8 and
     * two sessions (Snoozed, then On time) on Tuesday 2.
     */
    val calendarEveryOutcome =
        typical.copy(
            calendar =
                CalendarMonth(
                    firstDay = february(1),
                    days =
                        listOf(
                            CalendarDay(february(1), Outcome.OnTime),
                            CalendarDay(february(2), Outcome.Snoozed, sessions = 2),
                            CalendarDay(february(3), Outcome.Missed),
                            CalendarDay(february(4), Outcome.Skipped),
                            CalendarDay(february(5), Outcome.Test),
                            CalendarDay(february(8), Outcome.OnTime, fallbackUsed = true),
                        ) + (9..28).map { CalendarDay(february(it), if (it % 7 == 3) Outcome.Snoozed else Outcome.OnTime) },
                    today = null,
                    hasPrevious = true,
                    hasNext = true,
                ),
        )

    /** February with the two-session Tuesday 2 tapped once: its chip under the grid. */
    val calendarChip = calendarEveryOutcome.copy(selection = DaySelection(february(2), Outcome.Snoozed, inCalendar = true))

    /** January 2027, the oldest month, with no session in it: the bare grid, "Previous month" disabled. */
    val calendarEmptyOldestMonth =
        typical.copy(calendar = CalendarMonth(LocalDate(2027, 1, 1), emptyList(), today = null, hasPrevious = false, hasNext = true))

    /** This month with a session on Wednesday 10, after today (the clock was set back). */
    val calendarFutureSession = typical.copy(calendar = march(mapOf(1 to Outcome.OnTime, 10 to Outcome.Missed)))

    val loading = ProgressUiState(loading = true)

    val loadFailed = ProgressUiState(loadFailed = true)
}
