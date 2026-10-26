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
import kotlinx.datetime.TimeZone
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
        val viewModel = ProgressViewModel(observeProgress(), logger)
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
            assertEquals(thisMonth(FixtureToday), state.calendar)
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
            val viewModel = ProgressViewModel(observeProgress(), logger)
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
}
