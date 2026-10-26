package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.billing.Money
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
 * Story 6.5 screen states of Progress as the app builds them (no calendar markers until Story 6.6), on Wednesday
 * 2027-03-03: empty, Test only, EXPERIENCE.md F9's typical month, two currencies, a tapped dot, a streak over 30, loading
 * and the load failure.
 */
object ProgressSamples {
    val today: LocalDate = LocalDate(2027, 3, 3)

    private fun day(back: Int): LocalDate = today.minus(back, DateTimeUnit.DAY)

    private val calendar =
        CalendarMonth(firstDay = LocalDate(2027, 3, 1), days = emptyList(), today = today, hasPrevious = false, hasNext = false)

    /** The ring with [outcomes] by days back from today (0 is today); other days blank. */
    private fun ring(outcomes: Map<Int, Outcome>): List<RingDay> = (RING_DAYS - 1 downTo 0).map { RingDay(day(it), outcomes[it]) }

    val empty = ProgressUiState(today = today, ring = ring(emptyMap()), calendar = calendar)

    val testOnly =
        empty.copy(
            ring = ring(mapOf(1 to Outcome.Test, 0 to Outcome.Skipped)),
            stats = ProgressStats(0, 0, onTime30Days = null, averageMinutesToUp = null, snoozes30Days = 0),
        )

    /** F9: 12 on time in a row, a snoozed morning, a test alarm, another snoozed morning; $1.00 paid this month. */
    val typical =
        empty.copy(
            ring = ring((0..11).associateWith { Outcome.OnTime } + mapOf(12 to Outcome.Snoozed, 13 to Outcome.Test, 14 to Outcome.Snoozed)),
            stats = ProgressStats(12, 12, onTime30Days = 86, averageMinutesToUp = 3, snoozes30Days = 4),
            paidThisMonth = listOf(Money(1_000_000, "USD")),
        )

    val twoCurrencies = typical.copy(paidThisMonth = listOf(Money(8_980_000, "EUR"), Money(1_000_000, "USD")))

    val dotChip = typical.copy(selection = DaySelection(day(12), Outcome.Snoozed, inCalendar = false))

    val streakOver30 =
        empty.copy(
            ring = ring((0 until RING_DAYS).associateWith { Outcome.OnTime }),
            stats = ProgressStats(42, 42, onTime30Days = 100, averageMinutesToUp = 0, snoozes30Days = 0),
        )

    val loading = ProgressUiState(loading = true)

    val loadFailed = ProgressUiState(loadFailed = true)
}
