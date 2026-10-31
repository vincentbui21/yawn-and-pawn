package com.yawnandpawn.app.ui.home

import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.SkipResult
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.LogEvent
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
import com.yawnandpawn.app.testing.PreAlarmCall
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
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 7.11 on Home: "Skip this one" asks "Skip the {time} alarm?" with when it rings again after the skipped one (0.1.8;
 * "Keep it" is the safe default),
 * "Skip" skips the next ring (the countdown then targets the following one, the card says "Next ring skipped") and shows
 * "Skipped. No charge."; "Don't skip" restores the ring and removes the Skipped row.
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
                anAlarm(id = ID, time = LocalTime(7, 0), repeatDays = DayOfWeek.entries.toSet(), requestCode = 1001).copy(offerSkip = true),
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
                AlarmActions(fixture.setEnabled, fixture.delete, logger, skipNext = fixture.skip, undoSkip = fixture.undoSkip),
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
    fun `Skip this one asks first, with the alarm's time, and nothing is skipped yet`() =
        runTest(dispatcher) {
            val viewModel = home()

            viewModel.onIntent(HomeIntent.SkipRequested(ID))

            assertEquals(
                SkipDialog(ID, LocalTime(7, 0), TUESDAY_7, RingsAgain(2, LocalDate(2027, 3, 10))),
                viewModel.state.value.skipDialog,
            )
            assertNull(repository.current.single().skippedDate)
            assertTrue(history.rows.isEmpty())
        }

    /** The skip dialog's body for an alarm at [time] on [repeatDays], asked at [now] (0.1.8). */
    private suspend fun TestScope.ringsAgainAsked(
        repeatDays: Set<DayOfWeek>,
        time: LocalTime,
        now: Instant,
    ): RingsAgain? {
        repository.upsert(repository.current.single().copy(time = time, repeatDays = repeatDays))
        clock.set(now)
        val viewModel = home()
        viewModel.onIntent(HomeIntent.SkipRequested(ID))
        return assertNotNull(viewModel.state.value.skipDialog).ringsAgain
    }

    @Test
    fun `skipping today's ring of a daily alarm says it rings again tomorrow (0_1_8)`() =
        runTest(dispatcher) {
            // Monday 05:00: today's 07:00 is skipped, Tuesday's is next.
            val ringsAgain = ringsAgainAsked(DayOfWeek.entries.toSet(), LocalTime(7, 0), Instant.parse("2027-03-08T05:00:00Z"))

            assertEquals(RingsAgain(1, LocalDate(2027, 3, 9)), ringsAgain)
        }

    @Test
    fun `skipping Friday's ring of a weekday alarm says it rings again on Monday (0_1_8)`() =
        runTest(dispatcher) {
            // Thursday 22:00: Friday's 07:30 is skipped, Monday's is next, 4 days from today.
            val ringsAgain = ringsAgainAsked(WEEKDAYS, LocalTime(7, 30), Instant.parse("2027-03-11T22:00:00Z"))

            assertEquals(RingsAgain(4, LocalDate(2027, 3, 15)), ringsAgain)
        }

    @Test
    fun `skipping today's ring of a weekly alarm says it rings again in 7 days, named by its date (0_1_8)`() =
        runTest(dispatcher) {
            // Monday 05:00: today's 07:00 is skipped, next Monday's is 7 days away ("on Monday, March 15.").
            val ringsAgain = ringsAgainAsked(setOf(DayOfWeek.MONDAY), LocalTime(7, 0), Instant.parse("2027-03-08T05:00:00Z"))

            assertEquals(RingsAgain(7, LocalDate(2027, 3, 15)), ringsAgain)
        }

    @Test
    fun `skipping tomorrow's ring of a weekly alarm, asked the night before, is 8 days away (0_1_8)`() =
        runTest(dispatcher) {
            // Sunday 22:00: Monday's 07:00 is skipped, the Monday after is 8 days from today.
            val ringsAgain = ringsAgainAsked(setOf(DayOfWeek.MONDAY), LocalTime(7, 0), Instant.parse("2027-03-07T22:00:00Z"))

            assertEquals(RingsAgain(8, LocalDate(2027, 3, 15)), ringsAgain)
        }

    @Test
    fun `a weekly alarm asked after today's ring passed skips next week's, so it rings again in 14 days (0_1_8)`() =
        runTest(dispatcher) {
            // Monday 08:00: today's 07:00 has rung, next Monday's is skipped, the one after is 14 days away.
            val ringsAgain = ringsAgainAsked(setOf(DayOfWeek.MONDAY), LocalTime(7, 0), Instant.parse("2027-03-08T08:00:00Z"))

            assertEquals(RingsAgain(14, LocalDate(2027, 3, 22)), ringsAgain)
        }

    @Test
    fun `a one-time alarm's skip dialog has no rings-again body (0_1_8)`() =
        runTest(dispatcher) {
            assertNull(ringsAgainAsked(emptySet(), LocalTime(7, 0), Instant.parse("2027-03-08T05:00:00Z")))
        }

    @Test
    fun `Keep it closes the dialog and skips nothing`() =
        runTest(dispatcher) {
            val viewModel = home()
            viewModel.onIntent(HomeIntent.SkipRequested(ID))

            viewModel.onIntent(HomeIntent.SkipCancelled)

            assertNull(viewModel.state.value.skipDialog)
            assertNull(repository.current.single().skippedDate)
            assertTrue(history.rows.isEmpty())
        }

    @Test
    fun `Skip skips the next ring, logs it free and the card and countdown follow`() =
        runTest(dispatcher) {
            val viewModel = home()
            assertEquals(Countdown.HoursMinutes(9, 0), viewModel.state.value.nextAlarm)
            assertFalse(viewModel.card.skipped)

            viewModel.onIntent(HomeIntent.SkipRequested(ID))
            viewModel.onIntent(HomeIntent.SkipConfirmed)

            assertNull(viewModel.state.value.skipDialog)
            assertEquals(LocalDate(2027, 3, 9), repository.current.single().skippedDate)
            assertEquals(SessionOutcome.Skipped, history.rows.single().outcome)
            assertEquals(
                SchedulerCall.Schedule(ID, 1001, Instant.parse("2027-03-10T07:00:00Z").toEpochMilliseconds()),
                scheduler.calls.last(),
            )
            assertTrue(viewModel.state.value.skippedNoCharge, "Skipped. No charge.")
            assertTrue(viewModel.card.skipped, "Next ring skipped")
            assertEquals(Countdown.DaysHours(1, 9), viewModel.state.value.nextAlarm)
            advanceTimeBy(4_001)
            assertFalse(viewModel.state.value.skippedNoCharge)
        }

    @Test
    fun `Don't skip restores the ring and removes the Skipped row`() =
        runTest(dispatcher) {
            val viewModel = home()
            viewModel.onIntent(HomeIntent.SkipRequested(ID))
            viewModel.onIntent(HomeIntent.SkipConfirmed)

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
    fun `an alarm that is off is not offered a skip`() =
        runTest(dispatcher) {
            repository.upsert(repository.current.single().copy(enabled = false))
            val viewModel = home()

            viewModel.onIntent(HomeIntent.SkipRequested(ID))

            assertNull(viewModel.state.value.skipDialog)
        }

    @Test
    fun `a skip that cannot be stored is logged, with no snackbar and nothing half-applied`() =
        runTest(dispatcher) {
            val viewModel = home()
            viewModel.onIntent(HomeIntent.SkipRequested(ID))
            history.upsertFailure = DomainError.StorageFailure("disk")

            viewModel.onIntent(HomeIntent.SkipConfirmed)

            assertFalse(viewModel.state.value.skippedNoCharge)
            assertNull(repository.current.single().skippedDate)
            assertTrue(logger.events.any { it is LogEvent.OperationFailed && it.operation == "skip alarm" })
        }

    @Test
    fun `a skip confirmed during a session skips nothing and shows nothing`() =
        runTest(dispatcher) {
            val viewModel = home()
            viewModel.onIntent(HomeIntent.SkipRequested(ID))
            sessionState.value = SessionState.Ringing(aSession())

            viewModel.onIntent(HomeIntent.SkipConfirmed)

            assertFalse(viewModel.state.value.skippedNoCharge)
            assertNull(repository.current.single().skippedDate)
            assertTrue(history.rows.isEmpty())
        }

    @Test
    fun `an alarm whose Offer to skip is off is not offered a skip, and a skip of it changes nothing`() =
        runTest(dispatcher) {
            repository.upsert(repository.current.single().copy(offerSkip = false))
            val viewModel = home()

            viewModel.onIntent(HomeIntent.SkipRequested(ID))

            assertNull(viewModel.state.value.skipDialog)
            assertEquals(Outcome.Success(SkipResult.NothingToSkip), fixture.skip(ID))
            assertNull(repository.current.single().skippedDate)
            assertTrue(history.rows.isEmpty())
        }

    @Test
    fun `Skip confirmed after the asked-about ring has passed skips nothing`() =
        runTest(dispatcher) {
            val viewModel = home()
            viewModel.onIntent(HomeIntent.SkipRequested(ID))
            clock.set(Instant.parse("2027-03-09T07:30:00Z"))

            viewModel.onIntent(HomeIntent.SkipConfirmed)

            assertNull(viewModel.state.value.skipDialog)
            assertNull(repository.current.single().skippedDate)
            assertTrue(history.rows.isEmpty())
            assertFalse(viewModel.state.value.skippedNoCharge)
        }

    @Test
    fun `a Don't skip that cannot be stored says Couldn't save and keeps the skip`() =
        runTest(dispatcher) {
            val viewModel = home()
            viewModel.onIntent(HomeIntent.SkipRequested(ID))
            viewModel.onIntent(HomeIntent.SkipConfirmed)
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
                    AlarmDraft(id = ID, time = stored.time, repeatDays = emptySet(), offerSkip = true),
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

            assertTrue(fixture.save(AlarmDraft(time = stored.time, offerSkip = true)) is Outcome.Success)

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
                AlarmDraft(id = ID, time = stored.time, repeatDays = DayOfWeek.entries.toSet() - DayOfWeek.TUESDAY, offerSkip = true),
            )

            assertNull(repository.current.single().skippedDate)
            assertTrue(history.rows.isEmpty())
        }

    @Test
    fun `deleting an alarm cancels its pre-alarm job and notification`() =
        runTest(dispatcher) {
            home()
            clock.set(Instant.parse("2027-03-09T06:00:00Z"))
            fixture.scheduling.sync(repository.current.single())
            assertTrue(fixture.preAlarmNotifier.posted.isEmpty(), "posting is the worker's job")

            assertEquals(Outcome.Success(Unit), fixture.delete(ID))

            assertEquals(PreAlarmCall.Cancel(ID), fixture.work.preAlarmCalls.last())
            assertEquals(listOf(1001), fixture.preAlarmNotifier.cancels.takeLast(1))
        }

    private companion object {
        const val ID = "alarm-1"
        val TUESDAY_7: Instant = Instant.parse("2027-03-09T07:00:00Z")
        val WEEKDAYS: Set<DayOfWeek> =
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    }
}
