package com.yawnandpawn.app.ui.home

import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.SkipResult
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMissedNoteDismissals
import com.yawnandpawn.app.testing.FakeRequestCodeSequence
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.SchedulerCall
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.anEvaluateReliability
import com.yawnandpawn.app.testing.noReRegisterSuggestions
import com.yawnandpawn.app.ui.format.Countdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 7.11 on Home, since 0.1.8 with the skip from the Skip reminder's "Skip today's alarms" ([SkipTodaysAlarms],
 * outside the app): the countdown then targets the following ring and the card says "Next ring skipped"; "Don't skip"
 * restores the ring and removes the Skipped row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSkipTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /** Monday 2027-03-08 22:00 UTC; the daily 07:00 alarm rings Tuesday at 07:00, in 9 h. */
    private val clock = FakeClock(Instant.parse("2027-03-08T22:00:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val logger = FakeLogger()
    private val scheduler = FakeAlarmScheduler()
    private val history = FakeSessionHistoryRepository()
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val repository =
        FakeAlarmRepository(
            listOf(
                anAlarm(id = ID, time = LocalTime(7, 0), repeatDays = DayOfWeek.entries.toSet(), requestCode = 1001),
            ),
        )
    private lateinit var fixture: AlarmUseCasesFixture

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.home(): HomeViewModel {
        fixture =
            AlarmUseCasesFixture(
                repository,
                clock,
                zone,
                FakeIdGenerator(),
                requestCodes = FakeRequestCodeSequence(lastUsed = 1999),
                scheduler = scheduler,
                logger = logger,
                lock = AlarmWriteLock(),
                sessionState = sessionState,
                history = history,
            )
        val viewModel =
            HomeViewModel(
                FakeCheckConfigRepository(repository),
                AlarmActions(fixture.setEnabled, fixture.delete, logger, undoSkip = fixture.undoSkip),
                clock,
                zone,
                FakeTimeChangeSignal(),
                MissedNotes(FakeSessionHistoryRepository(), FakeMissedNoteDismissals()),
                anEvaluateReliability(checks = FakeCheckConfigRepository(repository)),
                noReRegisterSuggestions(clock),
                evaluationContext = dispatcher,
            )
        backgroundScope.launch { viewModel.state.collect { } }
        return viewModel
    }

    private val HomeViewModel.card: AlarmCard
        get() = state.value.alarms.single()

    @Test
    fun `Skip today's alarms skips the next ring, logs it free and the card and countdown follow`() =
        runTest(dispatcher) {
            val viewModel = home()
            assertEquals(Countdown.HoursMinutes(9, 0), viewModel.state.value.nextAlarm)
            assertFalse(viewModel.card.skipped)

            assertEquals(Outcome.Success(1), fixture.skipToday(TUESDAY))

            assertEquals(LocalDate(2027, 3, 9), repository.current.single().skippedDate)
            assertEquals(SessionOutcome.Skipped, history.rows.single().outcome)
            assertEquals(
                SchedulerCall.Schedule(ID, 1001, Instant.parse("2027-03-10T07:00:00Z").toEpochMilliseconds()),
                scheduler.calls.last(),
            )
            assertTrue(viewModel.card.skipped, "Next ring skipped")
            assertEquals(Countdown.DaysHours(1, 9), viewModel.state.value.nextAlarm)
        }

    @Test
    fun `Don't skip restores the ring and removes the Skipped row`() =
        runTest(dispatcher) {
            val viewModel = home()
            fixture.skipToday(TUESDAY)

            viewModel.onIntent(HomeIntent.DontSkipClicked(ID))

            assertNull(repository.current.single().skippedDate)
            assertTrue(history.rows.isEmpty())
            assertFalse(viewModel.card.skipped)
            assertEquals(Countdown.HoursMinutes(9, 0), viewModel.state.value.nextAlarm)
            assertEquals(
                SchedulerCall.Schedule(ID, 1001, Instant.parse("2027-03-09T07:00:00Z").toEpochMilliseconds()),
                scheduler.calls.last(),
            )
        }

    @Test
    fun `a Don't skip that cannot be stored says Couldn't save and keeps the skip`() =
        runTest(dispatcher) {
            val viewModel = home()
            fixture.skipToday(TUESDAY)
            history.deleteSkippedFailure = DomainError.StorageFailure("disk")

            viewModel.onIntent(HomeIntent.DontSkipClicked(ID))

            assertTrue(viewModel.state.value.saveFailed)
            assertEquals(LocalDate(2027, 3, 9), repository.current.single().skippedDate)
            assertEquals(1, history.rows.size)
        }

    @Test
    fun `a skipped one-time alarm switched back on rings that morning, so its Skipped row goes`() =
        runTest(dispatcher) {
            repository.upsert(repository.current.single().copy(repeatDays = emptySet()))
            home()
            assertEquals(Outcome.Success(SkipResult.Skipped), fixture.skip(ID))
            assertFalse(repository.current.single().enabled)
            assertEquals(1, history.rows.size)

            assertTrue(fixture.setEnabled(ID, enabled = true) is Outcome.Success)

            assertTrue(history.rows.isEmpty())
        }

    @Test
    fun `an edit that makes a skipped alarm one-time drops the skip and its row`() =
        runTest(dispatcher) {
            home()
            fixture.skip(ID)
            val stored = repository.current.single()

            val saved =
                fixture.save(
                    AlarmDraft(id = ID, time = stored.time, repeatDays = emptySet()),
                )

            assertTrue(saved is Outcome.Success)
            assertNull(repository.current.single().skippedDate)
            assertTrue(history.rows.isEmpty())
        }

    @Test
    fun `a new alarm identical to a skipped one-time alarm switches it on, and its Skipped row goes`() =
        runTest(dispatcher) {
            repository.upsert(repository.current.single().copy(repeatDays = emptySet()))
            home()
            fixture.skip(ID)
            val stored = repository.current.single()

            assertTrue(fixture.save(AlarmDraft(time = stored.time)) is Outcome.Success)

            assertTrue(repository.current.single().enabled)
            assertTrue(history.rows.isEmpty())
        }

    @Test
    fun `a skipped daily alarm switched off and on keeps its skip and its row`() =
        runTest(dispatcher) {
            home()
            fixture.skip(ID)

            fixture.setEnabled(ID, enabled = false)
            fixture.setEnabled(ID, enabled = true)

            assertEquals(LocalDate(2027, 3, 9), repository.current.single().skippedDate)
            assertEquals(TUESDAY_7, history.rows.single().scheduledAt)
        }

    @Test
    fun `an edit that takes the skipped weekday out of the repeat days drops the skip and its row`() =
        runTest(dispatcher) {
            home()
            fixture.skip(ID)
            val stored = repository.current.single()

            fixture.save(
                AlarmDraft(id = ID, time = stored.time, repeatDays = DayOfWeek.entries.toSet() - DayOfWeek.TUESDAY),
            )

            assertNull(repository.current.single().skippedDate)
            assertTrue(history.rows.isEmpty())
        }

    @Test
    fun `deleting the only alarm cancels the Skip reminder job`() =
        runTest(dispatcher) {
            home()
            fixture.scheduling.sync(repository.current.single())
            assertEquals(Instant.parse("2027-03-09T05:00:00Z") - clock.now(), fixture.work.skipReminder)

            assertEquals(Outcome.Success(Unit), fixture.delete(ID))

            assertNull(fixture.work.skipReminder)
        }

    @Test
    fun `Skip today's alarms during a session skips nothing`() =
        runTest(dispatcher) {
            home()
            sessionState.value = SessionState.Ringing(aSession())

            assertEquals(Outcome.Failure(DomainError.SessionActive), fixture.skipToday(TUESDAY))

            assertNull(repository.current.single().skippedDate)
            assertTrue(history.rows.isEmpty())
        }

    private companion object {
        const val ID = "alarm-1"
        val TUESDAY: LocalDate = LocalDate(2027, 3, 9)
        val TUESDAY_7: Instant = Instant.parse("2027-03-09T07:00:00Z")
    }
}
