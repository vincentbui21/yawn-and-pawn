package com.yawnandpawn.app.ui.home

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.config.CommitmentAction
import com.yawnandpawn.app.core.config.CommitmentEvent
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeCommitmentEventRepository
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMissedNoteDismissals
import com.yawnandpawn.app.testing.FakeRequestCodeSequence
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.SchedulerCall
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.anEvaluateReliability
import com.yawnandpawn.app.testing.noReRegisterSuggestions
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.settings.WeakeningNote
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Story 4.6: switching an alarm off inside its lock window asks first ("Turn off your {time} alarm? It rings in {hours}
 * h. This is logged."), "Keep it on" is the safe default, "Turn off" disables the alarm and writes the commitment event;
 * outside the window the switch turns off at once with no event (Story 1.9). Also the editor's "Saved. Takes effect
 * after ..." snackbar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeTurnOffUnderLockTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /** The alarm rings at 07:30 UTC on Tuesday 2027-03-09. */
    private val sevenThirty = Instant.parse("2027-03-09T07:30:00Z")
    private val clock = FakeClock(sevenThirty - 7.hours - 59.minutes)
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val logger = FakeLogger()
    private val events = FakeCommitmentEventRepository()
    private val scheduler = FakeAlarmScheduler()
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val repository = FakeAlarmRepository(listOf(anAlarm(id = ID, time = LocalTime(7, 30), requestCode = 1001)))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.home(
        store: AlarmRepository = repository,
        collect: Boolean = true,
    ): HomeViewModel {
        val alarms =
            AlarmUseCasesFixture(
                store,
                clock,
                zone,
                FakeIdGenerator(),
                requestCodes = FakeRequestCodeSequence(lastUsed = 1999),
                scheduler = scheduler,
                logger = logger,
                lock = AlarmWriteLock(),
                sessionState = sessionState,
                commitmentEvents = events,
            )
        val viewModel =
            HomeViewModel(
                FakeCheckConfigRepository(store),
                AlarmActions(alarms.setEnabled, alarms.delete, logger),
                clock,
                zone,
                FakeTimeChangeSignal(),
                MissedNotes(FakeSessionHistoryRepository(), FakeMissedNoteDismissals()),
                anEvaluateReliability(checks = FakeCheckConfigRepository(store)),
                noReRegisterSuggestions(clock),
            )
        if (collect) backgroundScope.launch { viewModel.state.collect { } }
        return viewModel
    }

    private fun HomeViewModel.switchOff() = onIntent(HomeIntent.AlarmToggled(ID, enabled = false))

    private val HomeViewModel.cardOn: Boolean
        get() =
            state.value.alarms
                .single()
                .enabled

    private fun ringingIn(duration: Duration) = clock.set(sevenThirty - duration)

    @Test
    fun `at 7 h 59 min the dialog asks first and nothing is written yet`() =
        runTest(dispatcher) {
            val viewModel = home()

            viewModel.switchOff()

            assertEquals(DisableUnderLock(ID, LocalTime(7, 30), Countdown.HoursMinutes(7, 59)), viewModel.state.value.disableDialog)
            assertTrue(viewModel.cardOn)
            assertTrue(repository.current.single().enabled)
            assertEquals(emptyList(), events.current)
        }

    @Test
    fun `at 8 h 01 min the switch turns off at once with no dialog and no event`() =
        runTest(dispatcher) {
            ringingIn(8.hours + 1.minutes)
            val viewModel = home()

            viewModel.switchOff()

            assertNull(viewModel.state.value.disableDialog)
            assertFalse(repository.current.single().enabled)
            assertEquals(emptyList(), events.current)
        }

    @Test
    fun `Keep it on, Back or a tap outside leaves the alarm on and armed`() =
        runTest(dispatcher) {
            val viewModel = home()
            viewModel.switchOff()

            viewModel.onIntent(HomeIntent.DisableCancelled)

            assertNull(viewModel.state.value.disableDialog)
            assertTrue(viewModel.cardOn)
            assertTrue(repository.current.single().enabled)
            assertTrue(scheduler.calls.none { it is SchedulerCall.Cancel })
            assertEquals(emptyList(), events.current)
        }

    @Test
    fun `Turn off disables the alarm, cancels its schedule and logs the ring it gave up`() =
        runTest(dispatcher) {
            val viewModel = home()
            viewModel.switchOff()

            viewModel.onIntent(HomeIntent.DisableConfirmed)
            viewModel.onIntent(HomeIntent.DisableConfirmed)

            assertNull(viewModel.state.value.disableDialog)
            assertFalse(viewModel.cardOn)
            assertFalse(repository.current.single().enabled)
            assertEquals(listOf<SchedulerCall>(SchedulerCall.Cancel(1001)), scheduler.calls.filterIsInstance<SchedulerCall.Cancel>())
            assertEquals(
                listOf(CommitmentEvent(FakeIdGenerator.fakeUuid(1), ID, sevenThirty, CommitmentAction.Disabled, clock.now())),
                events.current,
            )
        }

    @Test
    fun `a failed turn-off keeps the alarm on and armed and says Couldn't save`() =
        runTest(dispatcher) {
            val viewModel = home()
            viewModel.switchOff()
            repository.failure = DomainError.StorageFailure("disk full")

            viewModel.onIntent(HomeIntent.DisableConfirmed)

            assertTrue(viewModel.cardOn)
            assertTrue(repository.current.single().enabled)
            assertTrue(scheduler.calls.none { it is SchedulerCall.Cancel })
            assertEquals(emptyList(), events.current)
            assertTrue(viewModel.state.value.saveFailed)
        }

    @Test
    fun `under 1 h the dialog counts minutes, rounded down`() =
        runTest(dispatcher) {
            ringingIn(42.minutes + 30.seconds)
            val viewModel = home()

            viewModel.switchOff()

            assertEquals(
                Countdown.Minutes(42),
                viewModel.state.value.disableDialog
                    ?.ringsIn,
            )
        }

    @Test
    fun `the dialog's countdown rounds down to whole units`() {
        assertEquals(Countdown.HoursMinutes(7, 59), lockCountdownOf(7.hours + 59.minutes + 59.seconds))
        assertEquals(Countdown.HoursMinutes(8, 0), lockCountdownOf(8.hours))
        assertEquals(Countdown.HoursMinutes(1, 0), lockCountdownOf(1.hours))
        assertEquals(Countdown.Minutes(59), lockCountdownOf(59.minutes + 59.seconds))
        assertEquals(Countdown.Minutes(1), lockCountdownOf(30.seconds))
    }

    @Test
    fun `switched off before Home has read the alarms, it is read first and the dialog still asks (review fix 4)`() =
        runTest(dispatcher) {
            val viewModel = home(collect = false)

            viewModel.switchOff()
            backgroundScope.launch { viewModel.state.collect { } }

            assertEquals(DisableUnderLock(ID, LocalTime(7, 30), Countdown.HoursMinutes(7, 59)), viewModel.state.value.disableDialog)
            assertTrue(repository.current.single().enabled)
            assertEquals(emptyList(), events.current)
        }

    @Test
    fun `an alarm the store does not have is never turned off without the dialog`() =
        runTest(dispatcher) {
            val viewModel = home(collect = false)

            viewModel.onIntent(HomeIntent.AlarmToggled("unknown", enabled = false))

            assertTrue(scheduler.calls.none { it is SchedulerCall.Cancel })
            assertTrue(repository.current.single().enabled)
        }

    @Test
    fun `switched on and at once off again while the first write waits, the dialog asks and Turn off logs once (decision 7)`() =
        runTest(dispatcher) {
            repository.upsert(repository.current.single().copy(enabled = false))
            val gated = GatedAlarms(repository)
            val viewModel = home(store = gated)
            gated.gate = CompletableDeferred()

            viewModel.onIntent(HomeIntent.AlarmToggled(ID, enabled = true))
            viewModel.switchOff()
            assertEquals(
                Countdown.HoursMinutes(7, 59),
                viewModel.state.value.disableDialog
                    ?.ringsIn,
            )

            viewModel.onIntent(HomeIntent.DisableConfirmed)
            gated.gate?.complete(Unit)

            assertFalse(repository.current.single().enabled)
            assertEquals(listOf(CommitmentAction.Disabled), events.current.map { it.action })
        }

    @Test
    fun `on the Berlin spring-forward night the dialog counts real hours, and exactly 8 h asks`() =
        runTest(dispatcher) {
            zone.set(TimeZone.of("Europe/Berlin"))
            // 23:40 CET on Saturday 2027-03-27; the alarm rings at 07:30 CEST on Sunday, 6 h 50 min later.
            clock.set(Instant.parse("2027-03-27T22:40:00Z"))
            val viewModel = home()

            viewModel.switchOff()
            assertEquals(
                Countdown.HoursMinutes(6, 50),
                viewModel.state.value.disableDialog
                    ?.ringsIn,
            )

            viewModel.onIntent(HomeIntent.DisableCancelled)
            clock.set(Instant.parse("2027-03-28T05:30:00Z") - 8.hours)
            viewModel.switchOff()
            assertEquals(
                Countdown.HoursMinutes(8, 0),
                viewModel.state.value.disableDialog
                    ?.ringsIn,
            )
        }

    @Test
    fun `switching an alarm on never asks`() =
        runTest(dispatcher) {
            repository.upsert(repository.current.single().copy(enabled = false))
            val viewModel = home()

            viewModel.onIntent(HomeIntent.AlarmToggled(ID, enabled = true))

            assertNull(viewModel.state.value.disableDialog)
            assertTrue(repository.current.single().enabled)
        }

    @Test
    fun `deleting inside the window writes a Deleted event besides the AlarmDeleted log`() =
        runTest(dispatcher) {
            val viewModel = home()

            viewModel.onIntent(HomeIntent.DeleteClicked(ID))
            viewModel.onIntent(HomeIntent.DeleteConfirmed)

            assertEquals(listOf<LogEvent>(LogEvent.AlarmDeleted(ID, clock.now())), logger.events)
            assertEquals(
                listOf(CommitmentEvent(FakeIdGenerator.fakeUuid(1), ID, sevenThirty, CommitmentAction.Deleted, clock.now())),
                events.current,
            )
        }

    @Test
    fun `deleting outside the window writes no event`() =
        runTest(dispatcher) {
            ringingIn(8.hours + 1.minutes)
            val viewModel = home()

            viewModel.onIntent(HomeIntent.DeleteClicked(ID))
            viewModel.onIntent(HomeIntent.DeleteConfirmed)

            assertEquals(listOf<LogEvent>(LogEvent.AlarmDeleted(ID, clock.now())), logger.events)
            assertEquals(emptyList(), events.current)
        }

    @Test
    fun `the editor's saved-under-lock result shows its snackbar for 4 seconds`() =
        runTest(dispatcher) {
            val viewModel = home()
            val note = WeakeningNote(LocalTime(7, 30))

            viewModel.onIntent(HomeIntent.SavedUnderLock(note))

            assertEquals(note, viewModel.state.value.savedUnderLock)
            advanceTimeBy(4_001)
            assertNull(viewModel.state.value.savedUnderLock)
        }

    private companion object {
        const val ID = "a1"
    }
}

/** [inner] whose writes wait for [gate] while it is set, like a slow database (decision 7). */
private class GatedAlarms(
    private val inner: FakeAlarmRepository,
) : AlarmRepository by inner {
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun upsert(alarm: Alarm): Outcome<Unit, DomainError> {
        gate?.await()
        return inner.upsert(alarm)
    }
}
