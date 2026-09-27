package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /** 08:00 in Helsinki on a Wednesday (06:00 UTC, EET = UTC+2 in March). */
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.of("Europe/Helsinki"))
    private val repository = FakeAlarmRepository()

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
            saveAlarm = SaveAlarm(repository, FakeIdGenerator(), clock, AlarmWriteLock()),
            clock = clock,
            timeZoneProvider = zone,
        )

    private fun TestScope.effectsOf(viewModel: AlarmEditorViewModel): List<EditorEffect> {
        val effects = mutableListOf<EditorEffect>()
        // Unconfined: collects each effect as soon as it is sent (background work is not run by advanceUntilIdle).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        return effects
    }

    private val stored =
        anAlarm(time = LocalTime(6, 30), repeatDays = setOf(DayOfWeek.MONDAY), label = "Gym").copy(
            snoozeLengthMinutes = 5,
            volumePercent = 60,
            rampStartPercent = 10,
            vibration = false,
            soundRef = "builtin:birds",
            graceSeconds = 25,
        )

    @Test
    fun `a new alarm opens with the defaults`() =
        runTest(dispatcher) {
            val state = viewModel().state.value

            assertTrue(state.isNew)
            assertFalse(state.isLoading)
            assertEquals(LocalTime(7, 0), state.form.time)
            assertEquals(emptySet(), state.form.repeatDays)
            assertEquals("", state.form.label)
            assertEquals(9, state.form.snoozeLengthMinutes)
            assertEquals(80, state.form.volumePercent)
            assertTrue(state.form.gradualVolume)
            assertEquals(20, state.form.rampStartPercent)
            assertTrue(state.form.vibration)
            assertEquals(listOf(5, 9, 10, 15), EditorForm.SNOOZE_OPTIONS)
        }

    @Test
    fun `a one-time alarm whose time has passed today rings tomorrow`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            // Now is 08:00 local; the default 07:00 has passed.
            assertEquals(viewModel.state.value.form.time, viewModel.state.value.ringsTomorrowAt)
            viewModel.onIntent(EditorIntent.TimeChanged(LocalTime(9, 0)))
            assertNull(viewModel.state.value.ringsTomorrowAt, "later today")
            viewModel.onIntent(EditorIntent.TimeChanged(LocalTime(8, 0)))
            assertEquals(viewModel.state.value.form.time, viewModel.state.value.ringsTomorrowAt, "exactly now is already past")
        }

    @Test
    fun `a repeating alarm never shows the tomorrow note`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.onIntent(EditorIntent.DayToggled(DayOfWeek.WEDNESDAY))

            assertNull(viewModel.state.value.ringsTomorrowAt)
            viewModel.onIntent(EditorIntent.DayToggled(DayOfWeek.WEDNESDAY))
            assertEquals(viewModel.state.value.form.time, viewModel.state.value.ringsTomorrowAt, "no days again: one-time")
        }

    @Test
    fun `the tomorrow note follows the time zone port`() =
        runTest(dispatcher) {
            zone.set(TimeZone.UTC) // 06:00 UTC, so 07:00 is still ahead
            assertNull(viewModel().state.value.ringsTomorrowAt)
        }

    @Test
    fun `saving a new alarm stores it enabled with every field and closes`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)
            viewModel.onIntent(EditorIntent.TimeChanged(LocalTime(6, 45)))
            viewModel.onIntent(EditorIntent.DayToggled(DayOfWeek.MONDAY))
            viewModel.onIntent(EditorIntent.DayToggled(DayOfWeek.FRIDAY))
            viewModel.onIntent(EditorIntent.LabelChanged("Stand-up"))
            viewModel.onIntent(EditorIntent.SnoozeLengthSelected(15))
            viewModel.onIntent(EditorIntent.VolumeChanged(70))
            viewModel.onIntent(EditorIntent.RampStartChanged(30))
            viewModel.onIntent(EditorIntent.VibrationToggled(false))

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            val alarm = repository.current.single()
            assertEquals(LocalTime(6, 45), alarm.time)
            assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), alarm.repeatDays)
            assertEquals("Stand-up", alarm.label)
            assertTrue(alarm.enabled)
            assertEquals(15, alarm.snoozeLengthMinutes)
            assertEquals(70, alarm.volumePercent)
            assertTrue(alarm.gradualVolume)
            assertEquals(30, alarm.rampStartPercent)
            assertFalse(alarm.vibration)
            assertEquals(Alarm.DEFAULT_SOUND_REF, alarm.soundRef)
            assertEquals(Alarm.DEFAULT_GRACE_SECONDS, alarm.graceSeconds)
            assertEquals(RequestCodes.FIRST_ALARM, alarm.requestCode)
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
        }

    @Test
    fun `an empty label is stored as no label`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onIntent(EditorIntent.LabelChanged("   "))

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertNull(repository.current.single().label)
        }

    @Test
    fun `editing loads the stored alarm and saving keeps its id, request code and hidden fields`() =
        runTest(dispatcher) {
            repository.upsert(stored.copy(enabled = false))
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            assertTrue(viewModel.state.value.isLoading)
            advanceUntilIdle()

            val form = viewModel.state.value.form
            assertFalse(viewModel.state.value.isNew)
            assertFalse(viewModel.state.value.isLoading)
            assertEquals(
                EditorForm(
                    time = LocalTime(6, 30),
                    repeatDays = setOf(DayOfWeek.MONDAY),
                    label = "Gym",
                    snoozeLengthMinutes = 5,
                    volumePercent = 60,
                    gradualVolume = true,
                    rampStartPercent = 10,
                    vibration = false,
                ),
                form,
            )

            viewModel.onIntent(EditorIntent.LabelChanged("Gym day"))
            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            val saved = repository.current.single()
            assertEquals(stored.id, saved.id)
            assertEquals(stored.requestCode, saved.requestCode)
            assertEquals("Gym day", saved.label)
            assertTrue(saved.enabled, "Save stores the alarm enabled")
            assertEquals("builtin:birds", saved.soundRef)
            assertEquals(25, saved.graceSeconds)
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
        }

    @Test
    fun `editing an alarm that no longer exists closes the editor`() =
        runTest(dispatcher) {
            val viewModel = viewModel("missing")

            assertEquals(EditorEffect.Close, viewModel.effects.first())
        }

    @Test
    fun `a label over 40 characters blocks Save with a field error until it fits`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)
            viewModel.onIntent(EditorIntent.LabelChanged("a".repeat(41)))

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(emptyList(), repository.current)
            assertEquals(AlarmField.Label, viewModel.state.value.fieldError)
            assertEquals(emptyList(), effects, "no close, no snackbar")

            viewModel.onIntent(EditorIntent.LabelChanged("a".repeat(42)))
            assertEquals(AlarmField.Label, viewModel.state.value.fieldError, "still too long")
            viewModel.onIntent(EditorIntent.LabelChanged("a".repeat(40)))
            assertNull(viewModel.state.value.fieldError)
        }

    @Test
    fun `40 characters with surrounding spaces or emoji count like SaveAlarm counts them`() {
        assertFalse(isLabelTooLong("  " + "a".repeat(40) + "  "))
        assertFalse(isLabelTooLong("😀".repeat(40)))
        assertTrue(isLabelTooLong("😀".repeat(41)))
    }

    @Test
    fun `a storage failure keeps the editor open and shows the generic error`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)
            repository.failure = DomainError.StorageFailure("disk I/O error")

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(listOf<EditorEffect>(EditorEffect.ShowSaveFailed), effects)
            assertFalse(viewModel.state.value.isSaving)
            assertNull(viewModel.state.value.fieldError)

            repository.failure = null
            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()
            assertEquals(1, repository.current.size, "a retry saves")
        }

    @Test
    fun `the starting volume never goes above the volume`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.onIntent(EditorIntent.RampStartChanged(95))
            assertEquals(80, viewModel.state.value.form.rampStartPercent)
            viewModel.onIntent(EditorIntent.VolumeChanged(15))
            assertEquals(15, viewModel.state.value.form.rampStartPercent)
            viewModel.onIntent(EditorIntent.VolumeChanged(90))
            assertEquals(15, viewModel.state.value.form.rampStartPercent, "raising the volume leaves the start level alone")
        }

    @Test
    fun `turning the gradual volume off hides the starting level but keeps its value`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.onIntent(EditorIntent.GradualVolumeToggled(false))
            advanceUntilIdle()

            assertFalse(viewModel.state.value.form.gradualVolume)
            assertEquals(20, viewModel.state.value.form.rampStartPercent)
        }

    @Test
    fun `Back with no changes closes at once`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            // The time input reports the loaded time once; that is not a change.
            viewModel.onIntent(EditorIntent.TimeChanged(stored.time))

            viewModel.onIntent(EditorIntent.BackRequested)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.showDiscardDialog)
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
        }

    @Test
    fun `Back after a change asks to discard, and Keep editing stays`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.SnoozeLengthSelected(10))

            viewModel.onIntent(EditorIntent.BackRequested)
            assertTrue(viewModel.state.value.showDiscardDialog)

            viewModel.onIntent(EditorIntent.KeepEditing)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.showDiscardDialog)
            assertEquals(10, viewModel.state.value.form.snoozeLengthMinutes, "the change is kept")
            assertEquals(emptyList(), effects)
        }

    @Test
    fun `Back while the dialog is open is Keep editing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onIntent(EditorIntent.VibrationToggled(false))
            viewModel.onIntent(EditorIntent.BackRequested)

            viewModel.onIntent(EditorIntent.BackRequested)

            assertFalse(viewModel.state.value.showDiscardDialog)
        }

    @Test
    fun `Discard closes without saving`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.LabelChanged("Changed"))
            viewModel.onIntent(EditorIntent.BackRequested)

            viewModel.onIntent(EditorIntent.DiscardConfirmed)
            advanceUntilIdle()

            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
            assertEquals(stored, repository.current.single(), "nothing saved")
        }

    @Test
    fun `a new alarm with changes asks to discard too, and one without closes`() =
        runTest(dispatcher) {
            val unchanged = viewModel()
            val closed = effectsOf(unchanged)
            unchanged.onIntent(EditorIntent.BackRequested)
            advanceUntilIdle()
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), closed)

            val changed = viewModel()
            changed.onIntent(EditorIntent.DayToggled(DayOfWeek.SUNDAY))
            changed.onIntent(EditorIntent.BackRequested)
            assertTrue(changed.state.value.showDiscardDialog)
        }

    @Test
    fun `a change undone by hand is no change`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)
            viewModel.onIntent(EditorIntent.DayToggled(DayOfWeek.SUNDAY))
            viewModel.onIntent(EditorIntent.DayToggled(DayOfWeek.SUNDAY))

            viewModel.onIntent(EditorIntent.BackRequested)
            advanceUntilIdle()

            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
        }

    @Test
    fun `a second Save while the first is running is ignored`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.onIntent(EditorIntent.SaveClicked)
            assertTrue(viewModel.state.value.isSaving)
            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(1, repository.current.size)
        }

    @Test
    fun `a second Save after the first succeeded is ignored`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(1, repository.current.size)
            assertTrue(viewModel.state.value.isSaving, "stays saving until the screen closes")
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
        }

    @Test
    fun `Back, Discard and edits are ignored while a save runs`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)
            viewModel.onIntent(EditorIntent.VibrationToggled(false))

            viewModel.onIntent(EditorIntent.SaveClicked)
            viewModel.onIntent(EditorIntent.LabelChanged("Late edit"))
            viewModel.onIntent(EditorIntent.BackRequested)
            viewModel.onIntent(EditorIntent.DiscardConfirmed)
            assertEquals("", viewModel.state.value.form.label)
            assertFalse(viewModel.state.value.showDiscardDialog)
            advanceUntilIdle()

            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects, "only the save closes")
            assertNull(repository.current.single().label)
        }

    @Test
    fun `saving an alarm deleted elsewhere closes the editor`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            repository.delete(stored.id)

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
            assertEquals(emptyList(), repository.current, "not re-created")
        }

    @Test
    fun `out-of-range stored values are made valid, so the alarm still saves`() =
        runTest(dispatcher) {
            repository.upsert(stored.copy(graceSeconds = 40, soundRef = " ", snoozeLengthMinutes = 7, volumePercent = 120))
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            assertEquals(9, viewModel.state.value.form.snoozeLengthMinutes)
            assertEquals(100, viewModel.state.value.form.volumePercent)

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            val saved = repository.current.single()
            assertEquals(30, saved.graceSeconds)
            assertEquals(Alarm.DEFAULT_SOUND_REF, saved.soundRef)
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
        }

    @Test
    fun `with gradual volume off the hidden start level is not clamped until saving`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onIntent(EditorIntent.GradualVolumeToggled(false))

            viewModel.onIntent(EditorIntent.VolumeChanged(10))
            assertEquals(20, viewModel.state.value.form.rampStartPercent)
            viewModel.onIntent(EditorIntent.GradualVolumeToggled(true))
            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(10, repository.current.single().rampStartPercent, "clamped at save once gradual is on")
        }

    @Test
    fun `in a DST gap the tomorrow note shows the time the alarm actually rings`() =
        runTest(dispatcher) {
            // Helsinki skips 03:00 to 04:00 on Sunday 2027-03-28; now is Saturday 12:00 local.
            clock.set(Instant.parse("2027-03-27T10:00:00Z"))
            val viewModel = viewModel()

            viewModel.onIntent(EditorIntent.TimeChanged(LocalTime(3, 30)))

            assertEquals(LocalTime(4, 30), viewModel.state.value.ringsTomorrowAt)
        }
}
