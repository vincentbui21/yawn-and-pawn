package com.yawnandpawn.app.ui.home

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.reliability.ReliabilityItem
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMissedNoteDismissals
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.testing.FakeReliabilitySettings
import com.yawnandpawn.app.testing.FakeRequestCodeSequence
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aSessionHistoryRow
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.ui.format.Countdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The Story 1.9 I/O matrix for Home, plus list order, navigation, toggle, duplicate and delete. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /** Wednesday 2027-03-03, 06:00 UTC. */
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val signal = FakeTimeChangeSignal()
    private val logger = FakeLogger()
    private val ids = FakeIdGenerator()
    private val lock = AlarmWriteLock()
    private val history = FakeSessionHistoryRepository()
    private val dismissals = FakeMissedNoteDismissals()
    private val missedNotes = MissedNotes(history, dismissals)
    private val probe = FakeReliabilityProbe()
    private val settings = FakeReliabilitySettings()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun alarm(
        n: Long,
        time: LocalTime,
        enabled: Boolean = true,
        repeatDays: Set<DayOfWeek> = emptySet(),
        label: String? = null,
    ): Alarm =
        anAlarm(
            id = FakeIdGenerator.fakeUuid(n),
            time = time,
            enabled = enabled,
            repeatDays = repeatDays,
            label = label,
            requestCode = 1000 + n.toInt(),
        )

    private fun actions(repository: AlarmRepository): AlarmActions {
        // The seeded alarms use codes from 1001 up, so the mark starts above them.
        val alarms =
            AlarmUseCasesFixture(repository, clock, zone, ids, requestCodes = FakeRequestCodeSequence(lastUsed = 1999), lock = lock)
        return AlarmActions(alarms.setEnabled, alarms.duplicate, alarms.delete, clock, logger)
    }

    private fun TestScope.home(repository: AlarmRepository): HomeViewModel {
        val viewModel = HomeViewModel(repository, actions(repository), clock, zone, signal, missedNotes, probe, settings)
        backgroundScope.launch { viewModel.state.collect { } }
        return viewModel
    }

    private fun TestScope.effectsOf(viewModel: HomeViewModel): List<HomeEffect> {
        val effects = mutableListOf<HomeEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }
        return effects
    }

    @Test
    fun `the countdown to the soonest enabled alarm is in hours and minutes`() =
        runTest(dispatcher) {
            val viewModel = home(FakeAlarmRepository(listOf(alarm(1, LocalTime(13, 12)), alarm(2, LocalTime(20, 0)))))

            assertEquals(Countdown.HoursMinutes(7, 12), viewModel.state.value.nextAlarm)
        }

    @Test
    fun `under a minute away rounds up to 1 min`() =
        runTest(dispatcher) {
            clock.set(Instant.parse("2027-03-03T06:59:01Z"))
            val viewModel = home(FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0)))))

            assertEquals(Countdown.Minutes(1), viewModel.state.value.nextAlarm)
        }

    @Test
    fun `exactly 24 hours away is 1 d 0 h`() =
        runTest(dispatcher) {
            val viewModel = home(FakeAlarmRepository(listOf(alarm(1, LocalTime(6, 0)))))

            assertEquals(Countdown.DaysHours(1, 0), viewModel.state.value.nextAlarm)
        }

    @Test
    fun `in a DST gap the countdown targets the shifted time from nextOccurrence`() =
        runTest(dispatcher) {
            // Berlin springs forward 02:00 to 03:00 on Sunday 2027-03-28; now is 01:00 CET (00:00 UTC).
            zone.set(TimeZone.of("Europe/Berlin"))
            clock.set(Instant.parse("2027-03-28T00:00:00Z"))
            val viewModel = home(FakeAlarmRepository(listOf(alarm(1, LocalTime(2, 30)))))

            // 02:30 does not exist that day: it rings at 03:30 CEST (01:30 UTC), 1 h 30 min away.
            assertEquals(Countdown.HoursMinutes(1, 30), viewModel.state.value.nextAlarm)
        }

    @Test
    fun `a zone change is picked up on the next time signal`() =
        runTest(dispatcher) {
            zone.set(TimeZone.of("Europe/Berlin"))
            val viewModel = home(FakeAlarmRepository(listOf(alarm(1, LocalTime(8, 0)))))
            // 06:00 UTC is 07:00 in Berlin: 1 h to 08:00.
            assertEquals(Countdown.HoursMinutes(1, 0), viewModel.state.value.nextAlarm)

            zone.set(TimeZone.of("America/New_York"))
            assertEquals(Countdown.HoursMinutes(1, 0), viewModel.state.value.nextAlarm, "nothing recomputes before the signal")
            signal.emit()

            // 06:00 UTC is 01:00 in New York: 7 h to 08:00.
            assertEquals(Countdown.HoursMinutes(7, 0), viewModel.state.value.nextAlarm)
        }

    @Test
    fun `the countdown refreshes on each minute tick and when Home resumes`() =
        runTest(dispatcher) {
            val viewModel = home(FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0)))))
            assertEquals(Countdown.HoursMinutes(1, 0), viewModel.state.value.nextAlarm)

            clock.set(Instant.parse("2027-03-03T06:01:00Z"))
            signal.emit()
            assertEquals(Countdown.Minutes(59), viewModel.state.value.nextAlarm)

            clock.set(Instant.parse("2027-03-03T06:30:00Z"))
            viewModel.onIntent(HomeIntent.Resumed)
            assertEquals(Countdown.Minutes(30), viewModel.state.value.nextAlarm)
        }

    @Test
    fun `with every alarm off the cards show and the countdown is hidden`() =
        runTest(dispatcher) {
            val viewModel = home(FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0), enabled = false))))

            assertEquals(1, viewModel.state.value.alarms.size)
            assertNull(viewModel.state.value.nextAlarm)
        }

    @Test
    fun `before the first list arrives Home is loading, with no empty state and no list`() =
        runTest(dispatcher) {
            val neverEmits =
                object : AlarmRepository by FakeAlarmRepository() {
                    override fun observeAll(): Flow<List<Alarm>> = flow { awaitCancellation() }
                }
            val viewModel = home(neverEmits)

            assertEquals(HomeUiState(isLoading = true), viewModel.state.value)
        }

    @Test
    fun `no alarms is the empty state`() =
        runTest(dispatcher) {
            val viewModel = home(FakeAlarmRepository())

            assertEquals(HomeUiState(), viewModel.state.value)
        }

    @Test
    fun `a load failure is logged and shown as failed, and Try again resubscribes`() =
        runTest(dispatcher) {
            val repository = FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0)))).apply { failure = DomainError.StorageFailure("closed") }
            val viewModel = home(repository)

            assertEquals(HomeUiState(loadFailed = true), viewModel.state.value)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("load alarms", "storage failure: closed")), logger.events)

            repository.failure = null
            viewModel.onIntent(HomeIntent.RetryLoad)

            assertFalse(viewModel.state.value.loadFailed)
            assertEquals(
                listOf(FakeIdGenerator.fakeUuid(1)),
                viewModel.state.value.alarms
                    .map { it.id },
            )
        }

    @Test
    fun `cards follow the time of day with the stored fields and no checks, and update after a save`() =
        runTest(dispatcher) {
            val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
            val repository = FakeAlarmRepository(listOf(alarm(1, LocalTime(22, 0), label = "Late", repeatDays = weekdays)))
            val viewModel = home(repository)

            repository.upsert(alarm(2, LocalTime(6, 30), enabled = false))

            assertEquals(
                listOf(
                    AlarmCard(FakeIdGenerator.fakeUuid(2), LocalTime(6, 30), emptySet(), null, emptyList(), enabled = false),
                    AlarmCard(FakeIdGenerator.fakeUuid(1), LocalTime(22, 0), weekdays, "Late", emptyList(), enabled = true),
                ),
                viewModel.state.value.alarms,
            )
        }

    @Test
    fun `add, edit and the nav bar plus open the editor through effects`() =
        runTest(dispatcher) {
            val viewModel = home(FakeAlarmRepository())
            val effects = effectsOf(viewModel)

            viewModel.onIntent(HomeIntent.AddAlarm)
            viewModel.onIntent(HomeIntent.EditAlarm("a"))

            assertEquals(listOf<HomeEffect>(HomeEffect.OpenEditor(null), HomeEffect.OpenEditor("a")), effects)
        }

    @Test
    fun `a switch turns the alarm off at once and the countdown follows`() =
        runTest(dispatcher) {
            val repository = FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0))))
            val viewModel = home(repository)

            viewModel.onIntent(HomeIntent.AlarmToggled(FakeIdGenerator.fakeUuid(1), enabled = false))

            assertFalse(repository.current.single().enabled)
            assertFalse(
                viewModel.state.value.alarms
                    .single()
                    .enabled,
            )
            assertNull(viewModel.state.value.nextAlarm)
            assertTrue(logger.events.isEmpty())
        }

    @Test
    fun `a failed toggle reverts the switch to the stored value and is logged`() =
        runTest(dispatcher) {
            val repository = FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0))))
            val viewModel = home(repository)
            repository.failure = DomainError.StorageFailure("disk full")

            viewModel.onIntent(HomeIntent.AlarmToggled(FakeIdGenerator.fakeUuid(1), enabled = false))

            assertTrue(
                viewModel.state.value.alarms
                    .single()
                    .enabled,
            )
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("turn alarm off", "storage failure: disk full")), logger.events)
        }

    @Test
    fun `a stored change after a toggle shows, so the switch never sticks`() =
        runTest(dispatcher) {
            val repository = FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0))))
            val viewModel = home(repository)
            viewModel.onIntent(HomeIntent.AlarmToggled(FakeIdGenerator.fakeUuid(1), enabled = false))

            // The editor saves it enabled again later.
            clock.advanceBy(60.seconds)
            repository.upsert(repository.current.single().copy(enabled = true, updatedAt = clock.now()))

            assertTrue(
                viewModel.state.value.alarms
                    .single()
                    .enabled,
            )
        }

    @Test
    fun `Duplicate stores a copy and opens it in the editor`() =
        runTest(dispatcher) {
            ids.newId() // fakeUuid(1) is taken by the stored alarm
            val repository = FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0), label = "Gym")))
            val viewModel = home(repository)
            val effects = effectsOf(viewModel)

            viewModel.onIntent(HomeIntent.DuplicateClicked(FakeIdGenerator.fakeUuid(1)))

            val copy = repository.current.single { it.id != FakeIdGenerator.fakeUuid(1) }
            assertEquals("Gym", copy.label)
            assertEquals(listOf<HomeEffect>(HomeEffect.OpenEditor(copy.id)), effects)
            assertEquals(2, viewModel.state.value.alarms.size)
        }

    @Test
    fun `Delete asks first, Keep it keeps the alarm`() =
        runTest(dispatcher) {
            val repository = FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0))))
            val viewModel = home(repository)

            viewModel.onIntent(HomeIntent.DeleteClicked(FakeIdGenerator.fakeUuid(1)))
            assertEquals(DeleteAlarmDialog(FakeIdGenerator.fakeUuid(1), LocalTime(7, 0)), viewModel.state.value.deleteDialog)

            viewModel.onIntent(HomeIntent.DeleteCancelled)

            assertNull(viewModel.state.value.deleteDialog)
            assertEquals(1, repository.current.size)
            assertTrue(logger.events.isEmpty())
        }

    @Test
    fun `confirming Delete removes the card and logs AlarmDeleted once`() =
        runTest(dispatcher) {
            val repository = FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0)), alarm(2, LocalTime(8, 0))))
            val viewModel = home(repository)

            viewModel.onIntent(HomeIntent.DeleteClicked(FakeIdGenerator.fakeUuid(1)))
            viewModel.onIntent(HomeIntent.DeleteConfirmed)
            viewModel.onIntent(HomeIntent.DeleteConfirmed)

            assertEquals(
                listOf(FakeIdGenerator.fakeUuid(2)),
                viewModel.state.value.alarms
                    .map { it.id },
            )
            assertNull(viewModel.state.value.deleteDialog)
            assertEquals(listOf<LogEvent>(LogEvent.AlarmDeleted(FakeIdGenerator.fakeUuid(1), clock.now())), logger.events)
        }

    @Test
    fun `a failed delete keeps the card and logs the error`() =
        runTest(dispatcher) {
            val repository = FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0))))
            val viewModel = home(repository)
            viewModel.onIntent(HomeIntent.DeleteClicked(FakeIdGenerator.fakeUuid(1)))
            repository.failure = DomainError.StorageFailure("disk full")

            viewModel.onIntent(HomeIntent.DeleteConfirmed)

            assertEquals(1, viewModel.state.value.alarms.size)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("delete alarm", "storage failure: disk full")), logger.events)
        }

    @Test
    fun `a delete dialog open when the list fails does not come back after Try again`() =
        runTest(dispatcher) {
            val repository = FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0))))
            val viewModel = home(repository)
            viewModel.onIntent(HomeIntent.DeleteClicked(FakeIdGenerator.fakeUuid(1)))
            repository.failure = DomainError.StorageFailure("closed")
            viewModel.onIntent(HomeIntent.RetryLoad)
            assertTrue(viewModel.state.value.loadFailed)

            repository.failure = null
            viewModel.onIntent(HomeIntent.RetryLoad)

            assertEquals(1, viewModel.state.value.alarms.size)
            assertNull(viewModel.state.value.deleteDialog)
        }

    @Test
    fun `an editor that could not open its alarm shows the snackbar for 4 seconds`() =
        runTest(dispatcher) {
            val viewModel = home(FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0)))))

            viewModel.onIntent(HomeIntent.EditorOpenFailed)
            assertTrue(viewModel.state.value.openFailed)

            advanceTimeBy(3_999)
            assertTrue(viewModel.state.value.openFailed)
            advanceTimeBy(2)
            assertFalse(viewModel.state.value.openFailed)
        }

    @Test
    fun `the time signal is only listened to while Home is shown`() =
        runTest(dispatcher) {
            assertEquals(0, signal.subscribers)
            val viewModel =
                HomeViewModel(FakeAlarmRepository(), actions(FakeAlarmRepository()), clock, zone, signal, missedNotes, probe, settings)
            val collector = launch { viewModel.state.collect { } }
            assertEquals(1, signal.subscribers)

            collector.cancel()
            advanceTimeBy(5_001)

            assertEquals(0, signal.subscribers)
        }

    // Story 1.16: the missed note ----------------------------------------------------------------------------------

    private fun missedRow(
        sessionId: String,
        scheduledAt: Instant = Instant.parse("2027-03-03T06:15:00Z"),
        endedAt: Instant = scheduledAt + 30.minutes,
    ) = aSessionHistoryRow(sessionId = sessionId).copy(
        scheduledAt = scheduledAt,
        firstRingAt = scheduledAt,
        endedAt = endedAt,
        timeToCompleteMs = null,
        outcome = SessionOutcome.Missed,
    )

    private suspend fun record(row: SessionHistoryRow) = assertEquals(Outcome.Success(Unit), history.upsert(row))

    @Test
    fun `a Missed session shows its alarm time in the note, and none shows without one`() =
        runTest(dispatcher) {
            val viewModel = home(FakeAlarmRepository())
            assertNull(viewModel.state.value.missedAlarmAt)

            record(missedRow("s1"))

            assertEquals(LocalTime(6, 15), viewModel.state.value.missedAlarmAt)
        }

    @Test
    fun `the note's time is the alarm time in the phone's zone`() =
        runTest(dispatcher) {
            zone.set(TimeZone.of("Europe/Berlin"))
            record(missedRow("s1"))

            val viewModel = home(FakeAlarmRepository())

            assertEquals(LocalTime(7, 15), viewModel.state.value.missedAlarmAt)
        }

    @Test
    fun `Dismiss stores the session id and hides the note`() =
        runTest(dispatcher) {
            record(missedRow("s1"))
            val viewModel = home(FakeAlarmRepository())

            viewModel.onIntent(HomeIntent.MissedNoteDismissed(viewModel.state.value.missedSessionId))

            assertEquals(setOf("s1"), dismissals.current)
            assertNull(viewModel.state.value.missedAlarmAt)
        }

    @Test
    fun `a newer Missed session shows again after an older one was dismissed`() =
        runTest(dispatcher) {
            record(missedRow("s1"))
            val viewModel = home(FakeAlarmRepository())
            viewModel.onIntent(HomeIntent.MissedNoteDismissed(viewModel.state.value.missedSessionId))

            record(missedRow("s2", scheduledAt = Instant.parse("2027-03-04T06:45:00Z")))

            assertEquals(LocalTime(6, 45), viewModel.state.value.missedAlarmAt)
        }

    @Test
    fun `a dismissal that cannot be stored is logged and the note stays`() =
        runTest(dispatcher) {
            record(missedRow("s1"))
            dismissals.dismissFailure = DomainError.StorageFailure("disk full")
            val viewModel = home(FakeAlarmRepository())

            viewModel.onIntent(HomeIntent.MissedNoteDismissed(viewModel.state.value.missedSessionId))

            assertEquals(LocalTime(6, 15), viewModel.state.value.missedAlarmAt)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("dismiss missed note", "storage failure: disk full")), logger.events)
        }

    @Test
    fun `a history read failure is logged and shows no note, and Home still lists the alarms`() =
        runTest(dispatcher) {
            history.observeFailure = IllegalStateException("closed")
            val viewModel = home(FakeAlarmRepository(listOf(alarm(1, LocalTime(7, 0)))))

            assertNull(viewModel.state.value.missedAlarmAt)
            assertEquals(1, viewModel.state.value.alarms.size)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("load missed note", "closed")), logger.events)
        }

    @Test
    fun `after a passing read failure the note comes back on a retry, each failure logged`() =
        runTest(dispatcher) {
            record(missedRow("s1"))
            history.observeFailure = IllegalStateException("closed")
            val viewModel = home(FakeAlarmRepository())
            advanceTimeBy(1_500) // the second read (after 1 s) fails too
            assertNull(viewModel.state.value.missedAlarmAt)

            history.observeFailure = null
            advanceTimeBy(2_000) // the third read, 2 s later, works

            assertEquals(LocalTime(6, 15), viewModel.state.value.missedAlarmAt)
            assertEquals(List(2) { LogEvent.OperationFailed("load missed note", "closed") }, logger.events)
        }

    @Test
    fun `Dismiss names the note the user saw, so a newer Missed session that arrived meanwhile stays`() =
        runTest(dispatcher) {
            record(missedRow("s1"))
            val viewModel = home(FakeAlarmRepository())
            val seen = viewModel.state.value.missedSessionId
            assertEquals("s1", seen)
            record(missedRow("s2", scheduledAt = Instant.parse("2027-03-04T06:45:00Z")))

            viewModel.onIntent(HomeIntent.MissedNoteDismissed(seen))

            assertEquals(setOf("s1"), dismissals.current)
            assertEquals("s2", viewModel.state.value.missedSessionId)
            assertEquals(LocalTime(6, 45), viewModel.state.value.missedAlarmAt)
        }

    @Test
    fun `the missed-note retry waits 1 s, then doubles up to a minute`() {
        val waits = (0L..8L).map { missedRetryDelay(it) }

        assertEquals(listOf(1, 2, 4, 8, 16, 32, 60, 60, 60).map { it.seconds }, waits)
    }

    // Story 1.19: the reliability banner.

    @Test
    fun `no banner while every reliability setting is on`() =
        runTest(dispatcher) {
            assertFalse(home(FakeAlarmRepository()).state.value.reliabilityProblem)
        }

    @Test
    fun `a setting that is off shows the banner and Fix opens the first failing one`() =
        runTest(dispatcher) {
            probe.status = ReliabilityStatus(notificationsAllowed = true, fullScreenIntentAllowed = false, exactAlarmsAllowed = false)
            val viewModel = home(FakeAlarmRepository())

            assertTrue(viewModel.state.value.reliabilityProblem)
            viewModel.onIntent(HomeIntent.FixSettings)
            assertEquals(listOf(ReliabilityItem.FullScreenIntent), settings.opened)
        }

    @Test
    fun `the banner is checked again on every start and clears once every setting is on`() =
        runTest(dispatcher) {
            probe.status = ReliabilityStatus.ALL_OK.copy(notificationsAllowed = false)
            val viewModel = home(FakeAlarmRepository())
            assertTrue(viewModel.state.value.reliabilityProblem)

            probe.status = ReliabilityStatus.ALL_OK
            viewModel.onIntent(HomeIntent.Started)

            assertFalse(viewModel.state.value.reliabilityProblem)
            viewModel.onIntent(HomeIntent.FixSettings)
            assertEquals(emptyList(), settings.opened, "nothing to fix")
        }

    @Test
    fun `the banner also shows when the alarms cannot be read`() =
        runTest(dispatcher) {
            probe.status = ReliabilityStatus.ALL_OK.copy(exactAlarmsAllowed = false)
            val repository = FakeAlarmRepository().apply { failure = DomainError.StorageFailure("disk I/O error") }

            val state = home(repository).state.value

            assertTrue(state.loadFailed)
            assertTrue(state.reliabilityProblem)
        }
}
