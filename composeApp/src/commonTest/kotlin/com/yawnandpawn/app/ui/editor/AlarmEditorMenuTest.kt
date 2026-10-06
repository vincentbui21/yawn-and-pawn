package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakeRequestCodeSequence
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.ui.home.AlarmActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import kotlin.time.Instant

/**
 * The editor's overflow menu (Story 1.9): Duplicate and Delete of a stored alarm. Owner decisions 2026-10-02: Duplicate
 * with unsaved changes asks "Discard changes?" first, and a Delete that cannot be stored shows "Couldn't save the alarm.
 * Try again.". Owner decisions 2026-10-05: Duplicate opens a new, unsaved alarm prefilled from the stored one, and
 * saving one identical to a stored alarm switches that alarm on instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorMenuTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.of("Europe/Helsinki"))
    private val repository = FakeAlarmRepository()
    private val logger = FakeLogger()
    private val ids = FakeIdGenerator()

    // The mark already covers [stored], as the app run that stored it would have left it.
    private val alarms =
        AlarmUseCasesFixture(
            repository = repository,
            clock = clock,
            timeZoneProvider = zone,
            ids = ids,
            requestCodes = FakeRequestCodeSequence(lastUsed = RequestCodes.FIRST_ALARM),
            scheduler = FakeAlarmScheduler(),
        )

    private val stored =
        anAlarm(time = LocalTime(6, 30), repeatDays = setOf(DayOfWeek.MONDAY), label = "Gym").copy(
            snoozeLengthMinutes = 5,
            volumePercent = 60,
            rampStartPercent = 10,
            vibration = false,
            soundRef = "builtin:birds",
            graceSeconds = 25,
        )

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
            checkConfigs = alarms.checkConfigs,
            saveAlarm = alarms.save,
            clock = clock,
            timeZoneProvider = zone,
            actions = AlarmActions(alarms.setEnabled, alarms.delete, clock, logger),
            soundLibrary = FakeSoundLibrary(),
            soundPreview = FakeSoundPreview(),
            notificationPermission = FakeNotificationPermission(),
            testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), clock, logger),
        )

    private fun TestScope.effectsOf(viewModel: AlarmEditorViewModel): List<EditorEffect> {
        val effects = mutableListOf<EditorEffect>()
        // Unconfined: collects each effect as soon as it is sent (background work is not run by advanceUntilIdle).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        return effects
    }

    @Test
    fun `a new alarm has no overflow menu and ignores its intents`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)

            viewModel.onIntent(EditorIntent.DuplicateClicked)
            viewModel.onIntent(EditorIntent.DeleteClicked)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.hasOverflowMenu)
            assertNull(viewModel.state.value.deleteDialogTime)
            assertTrue(effects.isEmpty())
        }

    @Test
    fun `Duplicate without unsaved changes opens a new alarm prefilled from the stored one at once, storing nothing`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.hasOverflowMenu)

            viewModel.onIntent(EditorIntent.DuplicateClicked)
            assertFalse(viewModel.state.value.showDiscardDialog)
            advanceUntilIdle()

            // Owner decision 2026-10-05: nothing is stored until the new alarm is saved.
            assertEquals(listOf(stored), repository.current)
            assertEquals(listOf<EditorEffect>(EditorEffect.OpenCopy(stored.id)), effects)
        }

    @Test
    fun `Duplicate with unsaved changes asks to discard, and Discard opens a new alarm prefilled from the saved one`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.LabelChanged("Unsaved"))

            // Owner decision 2026-10-02: the same "Discard changes?" dialog as Back.
            viewModel.onIntent(EditorIntent.DuplicateClicked)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.showDiscardDialog)
            assertTrue(effects.isEmpty())

            viewModel.onIntent(EditorIntent.DiscardConfirmed)
            viewModel.onIntent(EditorIntent.DiscardConfirmed)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.showDiscardDialog)
            assertEquals(listOf(stored), repository.current, "the changes are discarded, not saved, and nothing is copied")
            assertEquals(listOf<EditorEffect>(EditorEffect.OpenCopy(stored.id)), effects, "opened once")
        }

    @Test
    fun `after Discard for Duplicate a Save before the navigation stores nothing`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.LabelChanged("Unsaved"))
            viewModel.onIntent(EditorIntent.DuplicateClicked)
            viewModel.onIntent(EditorIntent.DiscardConfirmed)

            viewModel.onIntent(EditorIntent.SaveClicked)
            viewModel.onIntent(EditorIntent.BackRequested)
            advanceUntilIdle()

            assertEquals(listOf(stored), repository.current, "the discarded edits are not stored")
            assertEquals(listOf<EditorEffect>(EditorEffect.OpenCopy(stored.id)), effects, "only the duplicate opens")
        }

    private fun copyOf(alarmId: String) =
        AlarmEditorViewModel(
            alarmId = null,
            repository = repository,
            checkConfigs = alarms.checkConfigs,
            saveAlarm = alarms.save,
            clock = clock,
            timeZoneProvider = zone,
            actions = AlarmActions(alarms.setEnabled, alarms.delete, clock, logger),
            soundLibrary = FakeSoundLibrary(),
            soundPreview = FakeSoundPreview(),
            notificationPermission = FakeNotificationPermission(),
            testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), clock, logger),
            copyOf = alarmId,
        )

    @Test
    fun `a duplicate opens as a new alarm with every setting of the stored one, and Save stores a second alarm`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            ids.newId() // the stored alarm's id; the new alarm gets the next one
            val viewModel = copyOf(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.isNew, "the header says New alarm")
            assertFalse(state.hasOverflowMenu)
            assertEquals(LocalTime(6, 30), state.form.time)
            assertEquals(setOf(DayOfWeek.MONDAY), state.form.repeatDays)
            assertEquals("Gym", state.form.label)
            assertEquals(5, state.form.snoozeLengthMinutes)
            assertEquals(60, state.form.volumePercent)
            assertFalse(state.form.vibration)
            assertEquals("builtin:birds", state.form.soundRef)
            assertEquals(listOf(stored), repository.current, "nothing stored on opening")

            viewModel.onIntent(EditorIntent.TimeChanged(LocalTime(6, 45)))
            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            val copy = repository.current.single { it.id != stored.id }
            assertEquals(LocalTime(6, 45), copy.time)
            assertEquals("Gym", copy.label)
            assertEquals(25, copy.graceSeconds, "the quiet time comes from the stored alarm")
            assertTrue(copy.enabled)
            assertEquals(stored, repository.current.single { it.id == stored.id }, "the original is untouched")
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
        }

    @Test
    fun `Back on an unchanged duplicate closes without asking and leaves no copy`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = copyOf(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()

            viewModel.onIntent(EditorIntent.BackRequested)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.showDiscardDialog)
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
            assertEquals(listOf(stored), repository.current)
        }

    @Test
    fun `saving an unchanged duplicate of a switched-off alarm switches that alarm on instead of storing a second`() =
        runTest(dispatcher) {
            repository.upsert(stored.copy(enabled = false))
            val viewModel = copyOf(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            val only = repository.current.single()
            assertEquals(stored.id, only.id)
            assertTrue(only.enabled)
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects, "the editor closes back to Home")
        }

    @Test
    fun `a duplicate of an alarm that cannot be read closes, and Home says it could not open`() =
        runTest(dispatcher) {
            val viewModel = copyOf("missing")
            val effects = effectsOf(viewModel)
            advanceUntilIdle()

            assertEquals(listOf<EditorEffect>(EditorEffect.OpenFailed), effects)
            assertTrue(repository.current.isEmpty())
        }

    @Test
    fun `Keep editing after Duplicate does nothing, and a later Back still asks to close`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.LabelChanged("Unsaved"))
            viewModel.onIntent(EditorIntent.DuplicateClicked)

            viewModel.onIntent(EditorIntent.KeepEditing)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.showDiscardDialog)
            assertEquals("Unsaved", viewModel.state.value.form.label)
            assertEquals(listOf(stored), repository.current)
            assertTrue(effects.isEmpty())

            // The dialog Back asks next closes the editor, it does not duplicate.
            viewModel.onIntent(EditorIntent.BackRequested)
            assertTrue(viewModel.state.value.showDiscardDialog)
            viewModel.onIntent(EditorIntent.DiscardConfirmed)
            advanceUntilIdle()

            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
            assertEquals(listOf(stored), repository.current)
        }

    @Test
    fun `Back while the Duplicate dialog is open is Keep editing`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.LabelChanged("Unsaved"))
            viewModel.onIntent(EditorIntent.DuplicateClicked)

            viewModel.onIntent(EditorIntent.BackRequested)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.showDiscardDialog)
            assertEquals(listOf(stored), repository.current)
            assertTrue(effects.isEmpty())
        }

    @Test
    fun `Delete asks with the stored time, Keep it keeps the alarm, and Delete deletes, logs once and closes`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.TimeChanged(LocalTime(9, 0)))

            viewModel.onIntent(EditorIntent.DeleteClicked)
            assertEquals(LocalTime(6, 30), viewModel.state.value.deleteDialogTime)
            viewModel.onIntent(EditorIntent.DeleteCancelled)
            assertNull(viewModel.state.value.deleteDialogTime)
            assertEquals(listOf(stored), repository.current)

            viewModel.onIntent(EditorIntent.DeleteClicked)
            viewModel.onIntent(EditorIntent.DeleteConfirmed)
            viewModel.onIntent(EditorIntent.DeleteConfirmed)
            advanceUntilIdle()

            assertTrue(repository.current.isEmpty())
            assertEquals(listOf<LogEvent>(LogEvent.AlarmDeleted(stored.id, clock.now())), logger.events)
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
        }

    @Test
    fun `Delete of an alarm deleted elsewhere closes the editor`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            repository.delete(stored.id)

            viewModel.onIntent(EditorIntent.DeleteClicked)
            viewModel.onIntent(EditorIntent.DeleteConfirmed)
            advanceUntilIdle()

            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
        }

    @Test
    fun `a failed delete keeps the editor open, says Couldn't save and logs the error`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            repository.failure = DomainError.StorageFailure("disk full")

            viewModel.onIntent(EditorIntent.DeleteClicked)
            viewModel.onIntent(EditorIntent.DeleteConfirmed)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isSaving)
            assertEquals(listOf<EditorEffect>(EditorEffect.ShowSaveFailed), effects)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("delete alarm", "storage failure: disk full")), logger.events)
        }
}
