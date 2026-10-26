package com.yawnandpawn.app.ui.progress

import com.yawnandpawn.app.core.billing.PurchaseRecordsRead
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.stats.DayOutcome
import com.yawnandpawn.app.core.stats.InsightLine
import com.yawnandpawn.app.core.stats.progressSnapshot
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Story 6.5: the pure mapping from Story 6.2's snapshot to Progress' screen state. */
class ProgressMappingTest {
    private fun stateOf(
        vararg rows: SessionHistoryRow,
        selection: DaySelection? = null,
    ): ProgressUiState =
        progressStateOf(progressSnapshot(rows.toList(), PurchaseRecordsRead(emptyList()), FixtureToday, TimeZone.UTC), selection)

    @Test
    fun `every day outcome maps to its ring outcome, None to a blank day`() {
        assertEquals(
            listOf(null, Outcome.OnTime, Outcome.Snoozed, Outcome.Missed, Outcome.Skipped, Outcome.Test),
            DayOutcome.entries.map { it.toUi() },
        )
        assertEquals(listOf(Insight.FastestOnWeekdays, Insight.FastestOnWeekends), InsightLine.entries.map { it.toUi() })
    }

    @Test
    fun `the average is in whole minutes, rounded half up`() {
        listOf(29.seconds to 0, 30.seconds to 1, 2.minutes + 29.seconds to 2, 2.minutes + 30.seconds to 3).forEach { (time, minutes) ->
            val stats = assertNotNull(stateOf(morning(FixtureToday, millisToUp = time.inWholeMilliseconds)).stats)
            assertEquals(minutes, stats.averageMinutesToUp, "$time")
        }
    }

    @Test
    fun `no finished session is the empty state, with this month's calendar and no other months`() {
        val running = morning(FixtureToday).copy(outcome = null)

        listOf(stateOf(), stateOf(running)).forEach { state ->
            assertNull(state.stats)
            val month = assertNotNull(state.calendar)
            assertEquals(LocalDate(2027, 3, 1), month.firstDay)
            assertEquals(emptyList(), month.days)
            assertEquals(FixtureToday, month.today)
            assertEquals(false, month.hasPrevious || month.hasNext)
        }
    }

    @Test
    fun `a ring chip stays with the day's current outcome, and goes when its day has none`() {
        val chip = DaySelection(FixtureToday, Outcome.OnTime, inCalendar = false)

        assertEquals(
            chip.copy(outcome = Outcome.Snoozed),
            stateOf(morning(FixtureToday, SessionOutcome.Snoozed), selection = chip).selection,
        )
        assertNull(stateOf(morning(day(-1)), selection = chip).selection)
        assertNull(stateOf(morning(FixtureToday), selection = chip.copy(date = day(-30))).selection)
    }

    @Test
    fun `a calendar chip stays with the day's current outcome while its day is in the shown month with a session`() {
        val chip = DaySelection(day(-1), Outcome.OnTime, inCalendar = true)

        assertEquals(chip.copy(outcome = Outcome.Missed), stateOf(morning(day(-1), SessionOutcome.Missed), selection = chip).selection)
        assertNull(stateOf(morning(day(-2)), selection = chip).selection)
        // A day of another month is not in the shown calendar.
        assertNull(stateOf(morning(day(-40)), selection = chip.copy(date = day(-40))).selection)
    }

    // --- Story 6.6: calendarOf ---

    private fun snapshot(
        vararg rows: SessionHistoryRow,
        today: LocalDate = FixtureToday,
        zone: TimeZone = TimeZone.UTC,
    ) = progressSnapshot(rows.toList(), PurchaseRecordsRead(emptyList()), today, zone)

    @Test
    fun `today's pill is only in today's month, and None days are left out`() {
        val rows = arrayOf(morning(LocalDate(2027, 2, 10), SessionOutcome.Missed), morning(day(-1)))

        val march = calendarOf(snapshot(*rows), shown = null)
        assertEquals(LocalDate(2027, 3, 1), march.firstDay)
        assertEquals(FixtureToday, march.today)
        assertEquals(listOf(CalendarDay(day(-1), Outcome.OnTime)), march.days)
        assertEquals(march, calendarOf(snapshot(*rows), shown = YearMonth(2027, 3)))

        val february = calendarOf(snapshot(*rows), shown = YearMonth(2027, 2))
        assertNull(february.today)
        assertEquals(listOf(CalendarDay(LocalDate(2027, 2, 10), Outcome.Missed)), february.days)
    }

    @Test
    fun `Previous stops at the oldest session's month and Next at today's month`() {
        val rows = arrayOf(morning(LocalDate(2027, 1, 31)), morning(day(-90)).copy(outcome = null))

        assertEquals(true to false, calendarOf(snapshot(*rows), null).let { it.hasPrevious to it.hasNext })
        assertEquals(true to true, calendarOf(snapshot(*rows), YearMonth(2027, 2)).let { it.hasPrevious to it.hasNext })
        // A running session is no morning: January is the oldest.
        assertEquals(false to true, calendarOf(snapshot(*rows), YearMonth(2027, 1)).let { it.hasPrevious to it.hasNext })
        assertEquals(false to false, calendarOf(snapshot(), null).let { it.hasPrevious to it.hasNext })
    }

    @Test
    fun `a month after today's shows today's month, and a day after today shows no marker even with a session`() {
        // The clock was set back to 3 March after mornings on 10 March and in April.
        val rows = arrayOf(morning(day(7)), morning(LocalDate(2027, 4, 2)), morning(day(0)))

        val shown = calendarOf(snapshot(*rows), shown = YearMonth(2027, 4))
        assertEquals(LocalDate(2027, 3, 1), shown.firstDay)
        assertEquals(false, shown.hasNext)
        assertEquals(listOf(FixtureToday), shown.days.map { it.date })
    }

    @Test
    fun `a month before the oldest session's shows the oldest, and with no history this month`() {
        val february = calendarOf(snapshot(morning(LocalDate(2027, 2, 10))), shown = YearMonth(2026, 12))
        assertEquals(LocalDate(2027, 2, 1), february.firstDay)
        assertEquals(false, february.hasPrevious)

        // All data deleted while January was shown.
        assertEquals(calendarOf(snapshot(), shown = null), calendarOf(snapshot(), shown = YearMonth(2027, 1)))
    }
}
