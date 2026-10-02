package com.yawnandpawn.app.ui.editor

import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.session.SessionConfig
import com.yawnandpawn.app.core.session.TestAlarmStore
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeRequestCodeSequence
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.ui.home.AlarmActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Story 1.18: the editor's "Test alarm" rings the form as it is now, as a test 10 s later. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorTestAlarmTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val repository = FakeAlarmRepository()
    private val logger = FakeLogger()
    private val testAlarmScheduler = FakeAlarmScheduler()
    private val testAlarmStore = FakeTestAlarmStore()
    private val alarms =
        AlarmUseCasesFixture(
            repository = repository,
            clock = clock,
            timeZoneProvider = FakeTimeZoneProvider(TimeZone.UTC),
            requestCodes = FakeRequestCodeSequence(lastUsed = RequestCodes.FIRST_ALARM),
        )
    private val stored = anAlarm(label = "Gym").copy(soundRef = "builtin:birds")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(alarmId: String? = null) =
        AlarmEditorViewModel(
            alarmId = alarmId,
            repository = repository,
            saveAlarm = alarms.save,
            clock = clock,
            timeZoneProvider = FakeTimeZoneProvider(TimeZone.UTC),
            actions = AlarmActions(alarms.setEnabled, alarms.duplicate, alarms.delete, clock, logger),
            testAlarm = ScheduleTestAlarm(testAlarmScheduler, testAlarmStore, clock, logger),
        )

    private fun TestScope.effectsOf(viewModel: AlarmEditorViewModel): List<EditorEffect> {
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        return effects
    }

    @Test
    fun `Test alarm rings the unsaved values as a test 10 s later, shows its snackbar and saves nothing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)
            viewModel.onIntent(EditorIntent.LabelChanged("Gym"))
            viewModel.onIntent(EditorIntent.VolumeChanged(70))
            viewModel.onIntent(EditorIntent.VibrationToggled(false))

            viewModel.onIntent(EditorIntent.TestAlarmClicked)
            advanceUntilIdle()

            assertEquals(listOf<EditorEffect>(EditorEffect.ShowTestScheduled), effects)
            assertEquals(mapOf(RequestCodes.TEST_ALARM to clock.now().toEpochMilliseconds() + 10_000), testAlarmScheduler.armed)
            val config = checkNotNull(testAlarmStore.pending)
            assertTrue(config.testMode)
            assertEquals("Gym", config.label)
            assertEquals(70, config.volumePercent)
            assertFalse(config.vibration)
            assertTrue(repository.current.isEmpty(), "a test saves nothing")
            assertEquals("Gym", viewModel.state.value.form.label, "the unsaved form is kept")
        }

    @Test
    fun `a test of a stored alarm keeps its id and sound`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            advanceUntilIdle()

            viewModel.onIntent(EditorIntent.TestAlarmClicked)
            advanceUntilIdle()

            assertEquals(stored.id, testAlarmStore.pending?.alarmId)
            assertEquals(stored.soundRef, testAlarmStore.pending?.soundRef)
        }

    @Test
    fun `a second tap while a test is being armed arms nothing more`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)

            viewModel.onIntent(EditorIntent.TestAlarmClicked)
            viewModel.onIntent(EditorIntent.TestAlarmClicked)
            advanceUntilIdle()

            assertEquals(1, testAlarmScheduler.calls.size)
            assertEquals(listOf<EditorEffect>(EditorEffect.ShowTestScheduled), effects)

            viewModel.onIntent(EditorIntent.TestAlarmClicked)
            advanceUntilIdle()
            assertEquals(2, testAlarmScheduler.calls.size, "a later tap arms a new test")
        }

    @Test
    fun `an over-long label shows the field error, like Save, and rings no test`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onIntent(EditorIntent.LabelChanged("x".repeat(41)))

            viewModel.onIntent(EditorIntent.TestAlarmClicked)
            advanceUntilIdle()

            assertEquals(AlarmField.Label, viewModel.state.value.fieldError)
            assertTrue(testAlarmScheduler.calls.isEmpty())
            assertNull(testAlarmStore.pending)
        }

    @Test
    fun `closing the editor while the test is being armed still arms it`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val gatedStore = GatedStore(testAlarmStore, gate)
            val viewModel =
                AlarmEditorViewModel(
                    alarmId = null,
                    repository = repository,
                    saveAlarm = alarms.save,
                    clock = clock,
                    timeZoneProvider = FakeTimeZoneProvider(TimeZone.UTC),
                    actions = AlarmActions(alarms.setEnabled, alarms.duplicate, alarms.delete, clock, logger),
                    testAlarm = ScheduleTestAlarm(testAlarmScheduler, gatedStore, clock, logger),
                )
            viewModel.onIntent(EditorIntent.TestAlarmClicked)
            advanceUntilIdle()

            viewModel.viewModelScope.cancel()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(setOf(RequestCodes.TEST_ALARM), testAlarmScheduler.armed.keys, "armed after the editor closed")
            assertTrue(testAlarmStore.pending?.testMode == true)
        }

    /** A [TestAlarmStore] whose [put] waits for [gate]. */
    private class GatedStore(
        private val inner: TestAlarmStore,
        private val gate: CompletableDeferred<Unit>,
    ) : TestAlarmStore by inner {
        override suspend fun put(config: SessionConfig): Outcome<Unit, DomainError> {
            gate.await()
            return inner.put(config)
        }
    }

    @Test
    fun `a test alarm that cannot be armed shows nothing and is logged`() =
        runTest(dispatcher) {
            testAlarmScheduler.failure = DomainError.ExactAlarmNotPermitted
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)

            viewModel.onIntent(EditorIntent.TestAlarmClicked)
            advanceUntilIdle()

            assertTrue(effects.isEmpty())
            assertNull(testAlarmStore.pending)
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed.of("schedule test alarm", DomainError.ExactAlarmNotPermitted)),
                logger.events,
            )
        }
}
