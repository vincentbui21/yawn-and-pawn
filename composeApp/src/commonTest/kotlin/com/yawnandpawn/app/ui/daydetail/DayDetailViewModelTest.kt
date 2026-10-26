package com.yawnandpawn.app.ui.daydetail

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.config.CommitmentAction
import com.yawnandpawn.app.core.config.CommitmentEvent
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeCommitmentEventRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakePurchaseRecordRepository
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aPurchaseRecord
import com.yawnandpawn.app.testing.aSessionHistoryRow
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Story 6.7: Day detail over the fakes (UTC, the fake clock's Wednesday 2027-03-03, a session at 06:00), from loading to
 * live updates and failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayDetailViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val history = FakeSessionHistoryRepository()
    private val records = FakePurchaseRecordRepository()
    private val alarms = FakeAlarmRepository(listOf(anAlarm(id = ALARM, label = "Work")))
    private val events = FakeCommitmentEventRepository()
    private val logger = FakeLogger()
    private val today = LocalDate(2027, 3, 3)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.screen(seen: MutableList<DayDetailUiState> = mutableListOf()): DayDetailViewModel {
        val viewModel = DayDetailViewModel(today, history, records, alarms, events, FakeTimeZoneProvider(TimeZone.UTC), logger)
        backgroundScope.launch { viewModel.state.collect { seen += it } }
        return viewModel
    }

    private suspend fun seedSnoozedMorning() {
        history.upsert(
            aSessionHistoryRow(sessionId = SESSION, alarmId = ALARM).copy(
                endedAt = DEFAULT_FAKE_INSTANT + 12.minutes,
                snoozeCount = 1,
                checkTypes = listOf("Math"),
                timeToCompleteMs = 12.minutes.inWholeMilliseconds,
                outcome = SessionOutcome.Snoozed,
            ),
        )
    }

    private fun record(
        token: String,
        minute: Int,
    ) = aPurchaseRecord(
        token = token,
        status = RecordStatus.Consumed,
        sessionId = SESSION,
        snoozeNumber = minute,
        price = Money(990_000, "EUR"),
        purchasedAt = DEFAULT_FAKE_INSTANT + minute.minutes,
    )

    private fun DayDetailViewModel.session(): SessionDetail = state.value.sessions.single()

    @Test
    fun `loading comes first, then the day`() =
        runTest(dispatcher) {
            seedSnoozedMorning()
            records.putRecord(record("a", 1))
            val seen = mutableListOf<DayDetailUiState>()

            val viewModel = screen(seen)

            assertEquals(listOf(true, false), seen.map { it.loading })
            val shown = viewModel.session()
            assertEquals("Work", shown.alarmLabel)
            assertEquals(12, shown.minutesToUp)
            assertEquals(listOf(Money(990_000, "EUR")), shown.paid)
            assertEquals(MorningEvent.Snoozed(LocalTime(6, 1), minutes = null, price = Money(990_000, "EUR")), shown.events[1])
            assertFalse(viewModel.state.value.loading)
        }

    @Test
    fun `a new purchase record of the session shows again with its snooze`() =
        runTest(dispatcher) {
            seedSnoozedMorning()
            val viewModel = screen()
            assertEquals(2, viewModel.session().events.size)

            records.putRecord(record("a", 1))

            assertEquals(3, viewModel.session().events.size)
            assertTrue(viewModel.session().events[1] is MorningEvent.Snoozed)
        }

    @Test
    fun `a failed merges read is logged and the rest shows`() =
        runTest(dispatcher) {
            seedSnoozedMorning()
            history.recordMerge(SessionMergeRow(SESSION, "alarm-2", DEFAULT_FAKE_INSTANT, DEFAULT_FAKE_INSTANT + 2.minutes))
            history.mergesFailure = DomainError.StorageFailure("merges")

            val viewModel = screen()

            assertEquals(2, viewModel.session().events.size)
            assertTrue(logger.events.any { it is LogEvent.OperationFailed && it.operation == "load merges" }, "${logger.events}")
        }

    @Test
    fun `a merge shows at its time while the read works`() =
        runTest(dispatcher) {
            seedSnoozedMorning()
            history.recordMerge(SessionMergeRow(SESSION, "alarm-2", DEFAULT_FAKE_INSTANT, DEFAULT_FAKE_INSTANT + 2.minutes))

            val viewModel = screen()

            assertEquals(MorningEvent.Merged(LocalTime(6, 2), LocalTime(6, 0)), viewModel.session().events[1])
        }

    @Test
    fun `a failed read of the alarm changes is logged and shows no notes`() =
        runTest(dispatcher) {
            seedSnoozedMorning()
            events.insert(CommitmentEvent("e", ALARM, DEFAULT_FAKE_INSTANT + 60.minutes, CommitmentAction.Disabled, DEFAULT_FAKE_INSTANT))
            events.failure = DomainError.StorageFailure("events")

            val viewModel = screen()

            assertEquals(emptyList(), viewModel.state.value.changes)
            assertEquals(1, viewModel.state.value.sessions.size)
            assertTrue(logger.events.any { it is LogEvent.OperationFailed && it.operation == "load alarm changes" }, "${logger.events}")

            events.failure = null
            viewModel.onIntent(DayDetailIntent.RetryLoad)
            assertEquals(listOf(AlarmChange(LocalTime(7, 0), deleted = false)), viewModel.state.value.changes)
        }

    @Test
    fun `a failing history flow shows the load failure, and Try again reloads`() =
        runTest(dispatcher) {
            seedSnoozedMorning()
            history.observeFailure = IllegalStateException("disk")
            val seen = mutableListOf<DayDetailUiState>()
            val viewModel = screen(seen)

            assertTrue(viewModel.state.value.loadFailed)
            assertTrue(logger.events.any { it is LogEvent.OperationFailed && it.operation == "load day detail" })

            history.observeFailure = null
            viewModel.onIntent(DayDetailIntent.RetryLoad)

            assertTrue(seen.drop(1).any { it.loading }, "loading again after Try again: $seen")
            assertFalse(viewModel.state.value.loadFailed)
            assertEquals(1, viewModel.state.value.sessions.size)
        }

    @Test
    fun `a failing records flow shows the load failure, and a failing alarms flow only drops the label`() =
        runTest(dispatcher) {
            seedSnoozedMorning()
            records.observeFailure = IllegalStateException("records")
            val failing = screen()
            assertTrue(failing.state.value.loadFailed)

            records.observeFailure = null
            val failingAlarms =
                object : AlarmRepository by alarms {
                    override fun observeAll(): Flow<List<Alarm>> = flow { throw IllegalStateException("alarms") }
                }
            val labelless = DayDetailViewModel(today, history, records, failingAlarms, events, FakeTimeZoneProvider(TimeZone.UTC), logger)
            backgroundScope.launch { labelless.state.collect { } }
            assertEquals(null, labelless.session().alarmLabel)
            assertEquals(MorningEvent.Rang(LocalTime(6, 0)), labelless.session().events.first())
            assertTrue(logger.events.any { it is LogEvent.OperationFailed && it.operation == "load alarm labels" }, "${logger.events}")
        }

    @Test
    fun `back on Day detail after more than 5 s away shows the last day, never loading again`() =
        runTest(dispatcher) {
            seedSnoozedMorning()
            val viewModel = DayDetailViewModel(today, history, records, alarms, events, FakeTimeZoneProvider(TimeZone.UTC), logger)
            val first = backgroundScope.launch { viewModel.state.collect { } }
            assertEquals(1, viewModel.state.value.sessions.size)
            first.cancel()
            advanceTimeBy(6_000)

            val seen = mutableListOf<DayDetailUiState>()
            backgroundScope.launch { viewModel.state.collect { seen += it } }

            assertTrue(seen.none { it.loading }, "$seen")
            assertEquals(1, viewModel.state.value.sessions.size)
        }

    @Test
    fun `a deleted alarm shows the time only`() =
        runTest(dispatcher) {
            seedSnoozedMorning()
            val viewModel = screen()
            assertEquals("Work", viewModel.session().alarmLabel)

            alarms.delete(ALARM)

            assertEquals(null, viewModel.session().alarmLabel)
            assertEquals(LocalTime(6, 0), viewModel.session().alarmTime)
        }

    @Test
    fun `a date with nothing logged says so, so the route can close`() =
        runTest(dispatcher) {
            val viewModel = screen()

            assertTrue(viewModel.state.value.nothingLogged)
        }

    private companion object {
        const val SESSION = "session-1"
        const val ALARM = "alarm-1"
    }
}
