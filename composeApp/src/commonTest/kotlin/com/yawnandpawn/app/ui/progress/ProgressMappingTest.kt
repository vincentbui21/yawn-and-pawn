package com.yawnandpawn.app.ui.progress

import com.yawnandpawn.app.core.billing.PurchaseRecordsRead
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.stats.DayOutcome
import com.yawnandpawn.app.core.stats.InsightLine
import com.yawnandpawn.app.core.stats.progressSnapshot
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
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
    fun `a calendar chip is kept as it is`() {
        val chip = DaySelection(day(-40), Outcome.Missed, inCalendar = true)

        assertEquals(chip, stateOf(morning(FixtureToday), selection = chip).selection)
    }
}
