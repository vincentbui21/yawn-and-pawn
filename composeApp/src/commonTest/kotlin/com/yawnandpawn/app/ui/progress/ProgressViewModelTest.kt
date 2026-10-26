package com.yawnandpawn.app.ui.progress

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceSource
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.stats.DayTicker
import com.yawnandpawn.app.core.stats.ObserveProgress
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakePurchaseRecordRepository
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aPurchaseRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Story 6.5: Progress over the real Story 6.2 snapshot on the fakes, from loading to live updates and failures. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val history = FakeSessionHistoryRepository()
    private val records = FakePurchaseRecordRepository()
    private val clock = FakeClock()
    private val zones = FakeTimeZoneProvider(TimeZone.UTC)
    private val timeChanges = FakeTimeChangeSignal()
    private val logger = FakeLogger()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun observeProgress() = ObserveProgress(history, records, DayTicker(clock, zones, timeChanges), dispatcher)

    /** The ViewModel, collected for the test; every state it showed goes into [seen]. */
    private fun TestScope.screen(seen: MutableList<ProgressUiState> = mutableListOf()): ProgressViewModel {
        val viewModel = ProgressViewModel(observeProgress(), logger, dispatcher)
        backgroundScope.launch { viewModel.state.collect { seen += it } }
        return viewModel
    }

    private fun ProgressViewModel.now(): ProgressUiState = state.value

    private fun ProgressViewModel.stats(): ProgressStats = assertNotNull(now().stats)

    @Test
    fun `loading comes first, then the empty state with 30 blank days and this month's calendar`() =
        runTest(dispatcher) {
            val seen = mutableListOf<ProgressUiState>()
            val state = screen(seen).now()

            assertTrue(seen.first().loading)
            assertFalse(state.loading)
            assertFalse(state.loadFailed)
            assertTrue(state.isEmpty)
            assertEquals(RING_DAYS, state.ring.size)
            assertTrue(state.ring.all { it.outcome == null })
            assertEquals(FixtureToday, state.ring.last().date)
            val march = CalendarMonth(LocalDate(2027, 3, 1), emptyList(), FixtureToday, hasPrevious = false, hasNext = false)
            assertEquals(march, state.calendar)
            assertEquals(emptyList(), state.paidThisMonth)
            assertNull(state.insight)
        }

    @Test
    fun `Test and Skipped only show zero streaks, no rate or average, and zero snoozes`() =
        runTest(dispatcher) {
            history.upsert(morning(day(-1), SessionOutcome.Test, millisToUp = null))
            history.upsert(morning(day(0), SessionOutcome.Skipped, millisToUp = null))

            val viewModel = screen()

            assertEquals(ProgressStats(0, 0, onTime30Days = null, averageMinutesToUp = null, snoozes30Days = 0), viewModel.stats())
            assertEquals(
                listOf(Outcome.Test, Outcome.Skipped),
                viewModel
                    .now()
                    .ring
                    .takeLast(2)
                    .map { it.outcome },
            )
        }

    @Test
    fun `the F9 month gives streak 12, best 12, 86 percent, 3 min and 4 snoozes, with today last on the ring`() =
        runTest(dispatcher) {
            typicalMonth().forEach { history.upsert(it) }

            val viewModel = screen()

            assertEquals(ProgressStats(12, 12, onTime30Days = 86, averageMinutesToUp = 3, snoozes30Days = 4), viewModel.stats())
            val ring = viewModel.now().ring
            assertEquals(day(-29), ring.first().date)
            assertEquals(FixtureToday, ring.last().date)
            val expected = List(15) { null } + listOf(Outcome.Snoozed, Outcome.Test, Outcome.Snoozed) + List(12) { Outcome.OnTime }
            assertEquals(expected, ring.map { it.outcome })
        }

    @Test
    fun `this month's money is one total per currency by code, never added across currencies`() =
        runTest(dispatcher) {
            history.upsert(morning(day(0)))
            records.putRecord(aPurchaseRecord(token = "a", price = Money(2_990_000, "EUR")))
            records.putRecord(aPurchaseRecord(token = "b", price = Money(1_000_000, "USD")))
            records.putRecord(aPurchaseRecord(token = "c", price = Money(5_990_000, "EUR")))

            assertEquals(listOf(Money(8_980_000, "EUR"), Money(1_000_000, "USD")), screen().now().paidThisMonth)
        }

    @Test
    fun `an estimated price and a stranded payment are left out of the money paid`() =
        runTest(dispatcher) {
            history.upsert(morning(day(0)))
            records.putRecord(aPurchaseRecord(token = "paid", price = Money(1_000_000, "USD")))
            records.putRecord(aPurchaseRecord(token = "estimate", price = Money(2_000_000, "USD"), priceSource = PriceSource.Snapshot))
            records.putRecord(aPurchaseRecord(token = "refunded", price = Money(4_000_000, "USD"), status = RecordStatus.Stranded))

            assertEquals(listOf(Money(1_000_000, "USD")), screen().now().paidThisMonth)
        }

    @Test
    fun `an average under 30 seconds is 0 minutes, shown as under a minute`() =
        runTest(dispatcher) {
            history.upsert(morning(day(0), millisToUp = 29.seconds.inWholeMilliseconds))

            assertEquals(0, screen().stats().averageMinutesToUp)
        }

    @Test
    fun `a streak of 31 days is 31`() =
        runTest(dispatcher) {
            (0 downTo -30).forEach { history.upsert(morning(day(it))) }

            assertEquals(31, screen().stats().currentStreak)
        }

    @Test
    fun `Insight follows the snapshot, none when the gap is small`() =
        runTest(dispatcher) {
            // Weekdays at 2 min, weekends at 6 min, three of each in the last 30 days.
            val weekdays = (0 downTo -29).map(::day).filter { it.dayOfWeek.ordinal < 5 }.take(3)
            val weekends = (0 downTo -29).map(::day).filter { it.dayOfWeek.ordinal >= 5 }.take(3)
            weekdays.forEach { history.upsert(morning(it, millisToUp = 120_000)) }
            weekends.forEach { history.upsert(morning(it, millisToUp = 360_000)) }
            val viewModel = screen()
            assertEquals(Insight.FastestOnWeekdays, viewModel.now().insight)

            weekends.forEach { history.upsert(morning(it, millisToUp = 130_000)) }

            assertNull(viewModel.now().insight)
        }

    @Test
    fun `a second tap keeps the chip, and another day replaces it`() =
        runTest(dispatcher) {
            typicalMonth().forEach { history.upsert(it) }
            val viewModel = screen()
            val snoozed = DaySelection(day(-12), Outcome.Snoozed, inCalendar = false)

            viewModel.onIntent(ProgressIntent.DaySelected(snoozed))
            viewModel.onIntent(ProgressIntent.DayTapped(snoozed.date))
            assertEquals(snoozed, viewModel.now().selection)

            val today = DaySelection(FixtureToday, Outcome.OnTime, inCalendar = false)
            viewModel.onIntent(ProgressIntent.DaySelected(today))
            assertEquals(today, viewModel.now().selection)
        }

    @Test
    fun `at midnight the ring moves on, and a chip whose day left it is cleared for good`() =
        runTest(dispatcher) {
            history.upsert(morning(day(-29), SessionOutcome.Snoozed, snoozes = 1))
            history.upsert(morning(day(0)))
            val viewModel = screen()
            viewModel.onIntent(ProgressIntent.DaySelected(DaySelection(day(-29), Outcome.Snoozed, inCalendar = false)))
            assertNotNull(viewModel.now().selection)

            clock.set(DEFAULT_FAKE_INSTANT + 1.days - 5.hours)
            timeChanges.emit()

            assertEquals(
                day(1),
                viewModel
                    .now()
                    .ring
                    .last()
                    .date,
            )
            assertEquals(
                day(-28),
                viewModel
                    .now()
                    .ring
                    .first()
                    .date,
            )
            assertNull(viewModel.now().selection)
            // Back to the day before (the clock was set back): the dropped chip does not come back.
            clock.set(DEFAULT_FAKE_INSTANT)
            timeChanges.emit()
            assertEquals(
                FixtureToday,
                viewModel
                    .now()
                    .ring
                    .last()
                    .date,
            )
            assertNull(viewModel.now().selection)
        }

    @Test
    fun `a new history row or purchase record shows at once`() =
        runTest(dispatcher) {
            val viewModel = screen()
            assertTrue(viewModel.now().isEmpty)

            history.upsert(morning(day(0)))
            assertEquals(1, viewModel.stats().currentStreak)

            records.putRecord(aPurchaseRecord(price = Money(1_000_000, "USD")))
            assertEquals(listOf(Money(1_000_000, "USD")), viewModel.now().paidThisMonth)
        }

    @Test
    fun `back on Progress after more than 5 s away, the last screen stays until the new snapshot, with no loading`() =
        runTest(dispatcher) {
            history.upsert(morning(day(0)))
            val viewModel = ProgressViewModel(observeProgress(), logger, dispatcher)
            val first = backgroundScope.launch { viewModel.state.collect { } }
            assertEquals(1, viewModel.stats().currentStreak)
            first.cancel()
            advanceTimeBy(6.seconds)
            // The snapshot really stopped (no one listens for time changes), so the next collector restarts it.
            assertEquals(0, timeChanges.subscribers)

            val seen = mutableListOf<ProgressUiState>()
            backgroundScope.launch { viewModel.state.collect { seen += it } }

            assertTrue(seen.none { it.loading }, "$seen")
            assertEquals(1, viewModel.stats().currentStreak)
        }

    @Test
    fun `a failing read shows the load failure, logged once, and Try again loads again`() =
        runTest(dispatcher) {
            history.upsert(morning(day(0)))
            history.observeFailure = IllegalStateException("disk")
            val seen = mutableListOf<ProgressUiState>()
            val viewModel = screen(seen)

            assertEquals(ProgressUiState(loadFailed = true), viewModel.now())
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("load progress", "disk")), logger.events)

            history.observeFailure = null
            seen.clear()
            viewModel.onIntent(ProgressIntent.RetryLoad)

            assertTrue(seen.first().loading)
            assertEquals(1, viewModel.stats().currentStreak)
            assertEquals(1, logger.events.size)
        }

    // --- Story 6.6: the calendar ---

    private fun ProgressViewModel.calendar(): CalendarMonth = assertNotNull(now().calendar)

    @Test
    fun `the months page between the oldest session's month and this month, and a disabled direction changes nothing`() =
        runTest(dispatcher) {
            history.upsert(morning(LocalDate(2027, 1, 15)))
            history.upsert(morning(LocalDate(2027, 2, 10), SessionOutcome.Snoozed, snoozes = 1))
            history.upsert(morning(FixtureToday))
            val viewModel = screen()
            assertEquals(CalendarEdges(MARCH, FixtureToday, hasPrevious = true, hasNext = false), viewModel.calendar().edges())

            viewModel.onIntent(ProgressIntent.PreviousMonth)
            assertEquals(CalendarEdges(FEBRUARY, null, hasPrevious = true, hasNext = true), viewModel.calendar().edges())
            assertEquals(listOf(CalendarDay(LocalDate(2027, 2, 10), Outcome.Snoozed)), viewModel.calendar().days)

            viewModel.onIntent(ProgressIntent.PreviousMonth)
            val oldest = CalendarEdges(JANUARY, null, hasPrevious = false, hasNext = true)
            assertEquals(oldest, viewModel.calendar().edges())
            viewModel.onIntent(ProgressIntent.PreviousMonth)
            assertEquals(oldest, viewModel.calendar().edges())

            viewModel.onIntent(ProgressIntent.NextMonth)
            viewModel.onIntent(ProgressIntent.NextMonth)
            val current = CalendarEdges(MARCH, FixtureToday, hasPrevious = true, hasNext = false)
            assertEquals(current, viewModel.calendar().edges())
            viewModel.onIntent(ProgressIntent.NextMonth)
            assertEquals(current, viewModel.calendar().edges())
        }

    @Test
    fun `with no history the calendar is this month with both directions disabled, and neither changes it`() =
        runTest(dispatcher) {
            val viewModel = screen()
            val expected = CalendarMonth(LocalDate(2027, 3, 1), emptyList(), FixtureToday, hasPrevious = false, hasNext = false)
            assertEquals(expected, viewModel.calendar())

            viewModel.onIntent(ProgressIntent.PreviousMonth)
            viewModel.onIntent(ProgressIntent.NextMonth)

            assertEquals(expected, viewModel.calendar())
        }

    @Test
    fun `a day shows its worst counted outcome with its session count and fallback, and Skipped wins over Test`() =
        runTest(dispatcher) {
            history.upsert(morning(day(-2)).copy(fallbackUsed = true))
            history.upsert(morning(day(-1), SessionOutcome.Snoozed, snoozes = 1, id = "six"))
            history.upsert(morning(day(-1), SessionOutcome.OnTime, id = "half past six"))
            history.upsert(morning(FixtureToday, SessionOutcome.Test, millisToUp = null))
            val viewModel = screen()

            assertEquals(
                listOf(
                    CalendarDay(day(-2), Outcome.OnTime, fallbackUsed = true),
                    CalendarDay(day(-1), Outcome.Snoozed, sessions = 2),
                    CalendarDay(FixtureToday, Outcome.Test),
                ),
                viewModel.calendar().days,
            )

            history.upsert(morning(FixtureToday, SessionOutcome.Skipped, millisToUp = null))
            assertEquals(CalendarDay(FixtureToday, Outcome.Skipped, sessions = 2), viewModel.calendar().day(FixtureToday.day))
        }

    @Test
    fun `only finished sessions mark a day, so a running one or a day with none leaves no calendar day`() =
        runTest(dispatcher) {
            history.upsert(morning(day(-1)))
            history.upsert(morning(FixtureToday).copy(outcome = null))

            assertEquals(listOf(day(-1)), screen().calendar().days.map { it.date })
        }

    @Test
    fun `a month change clears a calendar chip but keeps a ring chip`() =
        runTest(dispatcher) {
            history.upsert(morning(LocalDate(2027, 2, 27), SessionOutcome.Missed))
            history.upsert(morning(day(-1)))
            val viewModel = screen()
            val calendarChip = DaySelection(day(-1), Outcome.OnTime, inCalendar = true)
            viewModel.onIntent(ProgressIntent.DaySelected(calendarChip))
            assertEquals(calendarChip, viewModel.now().selection)

            viewModel.onIntent(ProgressIntent.PreviousMonth)
            assertNull(viewModel.now().selection)

            val ringChip = DaySelection(LocalDate(2027, 2, 27), Outcome.Missed, inCalendar = false)
            viewModel.onIntent(ProgressIntent.DaySelected(ringChip))
            viewModel.onIntent(ProgressIntent.NextMonth)
            assertEquals(ringChip, viewModel.now().selection)
            assertEquals(LocalDate(2027, 3, 1), viewModel.calendar().firstDay)
        }

    @Test
    fun `this month follows today into a new month at midnight, a past month stays put`() =
        runTest(dispatcher) {
            history.upsert(morning(LocalDate(2027, 2, 10)))
            val viewModel = screen()
            clock.set(LocalDateTime(2027, 3, 31, 23, 59).toInstant(TimeZone.UTC))
            timeChanges.emit()
            assertEquals(LocalDate(2027, 3, 31), viewModel.calendar().today)

            clock.set(LocalDateTime(2027, 4, 1, 0, 1).toInstant(TimeZone.UTC))
            timeChanges.emit()
            assertEquals(CalendarEdges(APRIL, LocalDate(2027, 4, 1), hasPrevious = true, hasNext = false), viewModel.calendar().edges())

            viewModel.onIntent(ProgressIntent.PreviousMonth)
            viewModel.onIntent(ProgressIntent.PreviousMonth)
            clock.set(LocalDateTime(2027, 5, 1, 0, 1).toInstant(TimeZone.UTC))
            timeChanges.emit()
            assertEquals(CalendarEdges(FEBRUARY, null, hasPrevious = false, hasNext = true), viewModel.calendar().edges())
        }

    @Test
    fun `when today moves back into the shown month, the calendar follows today again`() =
        runTest(dispatcher) {
            history.upsert(morning(LocalDate(2027, 1, 10)))
            history.upsert(morning(FixtureToday))
            val viewModel = screen()
            viewModel.onIntent(ProgressIntent.PreviousMonth)
            assertEquals(CalendarEdges(FEBRUARY, null, hasPrevious = true, hasNext = true), viewModel.calendar().edges())

            // The clock is set back into February: it is today's month now, with its pill.
            clock.set(LocalDateTime(2027, 2, 27, 23, 59).toInstant(TimeZone.UTC))
            timeChanges.emit()
            assertEquals(CalendarEdges(FEBRUARY, LocalDate(2027, 2, 27), hasPrevious = true, hasNext = false), viewModel.calendar().edges())

            // So it follows today into March at midnight.
            clock.set(LocalDateTime(2027, 3, 1, 0, 1).toInstant(TimeZone.UTC))
            timeChanges.emit()
            assertEquals(CalendarEdges(MARCH, LocalDate(2027, 3, 1), hasPrevious = true, hasNext = false), viewModel.calendar().edges())
        }

    @Test
    fun `a session recorded today marks today's day at once`() =
        runTest(dispatcher) {
            history.upsert(morning(day(-1)))
            val viewModel = screen()
            assertNull(viewModel.calendar().day(FixtureToday.day))

            history.upsert(morning(FixtureToday, SessionOutcome.Snoozed, snoozes = 1))

            assertEquals(CalendarDay(FixtureToday, Outcome.Snoozed), viewModel.calendar().day(FixtureToday.day))
        }

    @Test
    fun `a zone change moves a session to its local day and month`() =
        runTest(dispatcher) {
            // 06:30 on 1 March in Ho Chi Minh City is 23:30 on 28 February in UTC.
            val saigon = TimeZone.of("Asia/Ho_Chi_Minh")
            zones.set(saigon)
            history.upsert(morning(day(0)).copy(scheduledAt = LocalDateTime(2027, 3, 1, 6, 30).toInstant(saigon)))
            val viewModel = screen()
            assertEquals(listOf(LocalDate(2027, 3, 1)), viewModel.calendar().days.map { it.date })
            assertFalse(viewModel.calendar().hasPrevious)

            zones.set(TimeZone.UTC)
            timeChanges.emit()

            assertEquals(emptyList(), viewModel.calendar().days)
            assertTrue(viewModel.calendar().hasPrevious)
            viewModel.onIntent(ProgressIntent.PreviousMonth)
            assertEquals(listOf(LocalDate(2027, 2, 28)), viewModel.calendar().days.map { it.date })
        }

    @Test
    fun `a read failure after paging shows the load failure, and Try again keeps the month`() =
        runTest(dispatcher) {
            history.upsert(morning(LocalDate(2027, 2, 10)))
            history.upsert(morning(FixtureToday))
            val viewModel = screen()
            viewModel.onIntent(ProgressIntent.PreviousMonth)

            history.observeFailure = IllegalStateException("disk")
            viewModel.onIntent(ProgressIntent.RetryLoad)
            assertTrue(viewModel.now().loadFailed)
            viewModel.onIntent(ProgressIntent.NextMonth)

            history.observeFailure = null
            viewModel.onIntent(ProgressIntent.RetryLoad)
            assertEquals(LocalDate(2027, 2, 1), viewModel.calendar().firstDay)
        }

    /** The parts of a calendar month that paging changes. */
    private data class CalendarEdges(
        val firstDay: LocalDate,
        val today: LocalDate?,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
    )

    private fun CalendarMonth.edges() = CalendarEdges(firstDay, today, hasPrevious, hasNext)

    private companion object {
        val JANUARY = LocalDate(2027, 1, 1)
        val FEBRUARY = LocalDate(2027, 2, 1)
        val MARCH = LocalDate(2027, 3, 1)
        val APRIL = LocalDate(2027, 4, 1)
    }
}
