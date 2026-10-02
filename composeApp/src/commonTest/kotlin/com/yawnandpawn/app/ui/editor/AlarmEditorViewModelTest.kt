package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
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
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.ui.home.AlarmActions
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
    private val logger = FakeLogger()
    private val ids = FakeIdGenerator()
    private val scheduler = FakeAlarmScheduler()
    private val permission = FakeNotificationPermission()

    // The mark already covers [stored], as the app run that stored it would have left it.
    private val alarms =
        AlarmUseCasesFixture(
            repository = repository,
            clock = clock,
            timeZoneProvider = zone,
            ids = ids,
            requestCodes = FakeRequestCodeSequence(lastUsed = RequestCodes.FIRST_ALARM),
            scheduler = scheduler,
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
            saveAlarm = alarms.save,
            clock = clock,
            timeZoneProvider = zone,
            actions = AlarmActions(alarms.setEnabled, alarms.duplicate, alarms.delete, clock, logger),
            soundLibrary = FakeSoundLibrary(),
            soundPreview = FakeSoundPreview(),
            notificationPermission = permission,
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
            assertEquals(20, alarm.rampStartPercent, "the ramp start is the fixed default")
            assertFalse(alarm.vibration)
            assertEquals(Alarm.DEFAULT_SOUND_REF, alarm.soundRef)
            assertEquals(Alarm.DEFAULT_GRACE_SECONDS, alarm.graceSeconds)
            assertEquals(RequestCodes.FIRST_ALARM + 1, alarm.requestCode, "the next code above the high-water mark")
            assertEquals(
                mapOf(alarm.requestCode to Instant.parse("2027-03-05T04:45:00Z").toEpochMilliseconds()),
                scheduler.armed,
                "Friday 06:45 in Helsinki",
            )
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
                    soundRef = "builtin:birds",
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
    fun `editing an alarm that no longer exists closes the editor so Home can say so, and logs it`() =
        runTest(dispatcher) {
            val viewModel = viewModel("missing")

            assertEquals(EditorEffect.OpenFailed, viewModel.effects.first())
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("open alarm", "not found: missing")), logger.events)
        }

    @Test
    fun `an alarm that cannot be read closes the editor with OpenFailed and is logged`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            repository.failure = DomainError.StorageFailure("disk I/O error")
            val viewModel = viewModel(stored.id)

            assertEquals(EditorEffect.OpenFailed, viewModel.effects.first())
            assertFalse(viewModel.state.value.hasOverflowMenu)
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed("open alarm", "storage failure: disk I/O error")),
                logger.events,
            )
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
    fun `the first successful save asks for notifications once, and later saves do not`() =
        runTest(dispatcher) {
            permission.needed = true

            viewModel().onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()
            assertEquals(1, permission.requests)
            viewModel().onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(1, permission.requests, "asked once only")
            assertEquals(2, repository.current.size)
        }

    @Test
    fun `a request that fails after a successful save still closes the editor and is logged`() =
        runTest(dispatcher) {
            permission.needed = true
            permission.failure = IllegalStateException("no activity to launch from")
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
            assertEquals(1, repository.current.size)
            assertEquals(LogEvent.OperationFailed("request notification permission", "no activity to launch from"), logger.events.last())
        }

    @Test
    fun `a failed save does not ask for notifications`() =
        runTest(dispatcher) {
            permission.needed = true
            repository.failure = DomainError.StorageFailure("disk I/O error")

            viewModel().onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(0, permission.requests)
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
    fun `the ramp start is saved as the fixed 20 percent of the set volume, even at a low volume`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.VolumeChanged(15))

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertEquals(20, repository.current.single().rampStartPercent, "20% of the set volume, not clamped to it")
        }

    @Test
    fun `turning the gradual volume off keeps the fixed ramp start`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.onIntent(EditorIntent.GradualVolumeToggled(false))
            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()

            assertFalse(repository.current.single().gradualVolume)
            assertEquals(20, repository.current.single().rampStartPercent)
        }

    @Test
    fun `a row opens its sub-screen and Back returns to the main screen without closing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)

            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Snooze))
            assertEquals(EditorPane.Snooze, viewModel.state.value.pane)
            viewModel.onIntent(EditorIntent.SnoozeLengthSelected(15))
            viewModel.onIntent(EditorIntent.BackRequested)
            advanceUntilIdle()

            assertEquals(EditorPane.Main, viewModel.state.value.pane)
            assertEquals(15, viewModel.state.value.form.snoozeLengthMinutes)
            assertFalse(viewModel.state.value.showDiscardDialog)
            assertEquals(emptyList(), effects)
        }

    @Test
    fun `the repeat quick choices set the days, and Custom shows the chips`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            assertEquals(RepeatChoice.Once, viewModel.state.value.repeatChoice)

            viewModel.onIntent(EditorIntent.RepeatChosen(RepeatChoice.Weekdays))
            assertEquals(com.yawnandpawn.app.ui.format.Weekdays, viewModel.state.value.form.repeatDays)
            assertEquals(RepeatChoice.Weekdays, viewModel.state.value.repeatChoice)

            viewModel.onIntent(EditorIntent.RepeatChosen(RepeatChoice.Custom))
            assertEquals(RepeatChoice.Custom, viewModel.state.value.repeatChoice, "Custom stays chosen with the weekdays set")
            viewModel.onIntent(EditorIntent.DayToggled(DayOfWeek.FRIDAY))
            assertEquals(4, viewModel.state.value.form.repeatDays.size)

            viewModel.onIntent(EditorIntent.RepeatChosen(RepeatChoice.Once))
            assertEquals(emptySet(), viewModel.state.value.form.repeatDays)
            assertEquals(RepeatChoice.Once, viewModel.state.value.repeatChoice)
        }

    @Test
    fun `a stored alarm on custom days opens with Custom chosen`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            advanceUntilIdle()

            assertEquals(RepeatChoice.Custom, viewModel.state.value.repeatChoice)
        }

    @Test
    fun `the header counts down to the next ring`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            // 08:00 now; 07:00 tomorrow is 23 h away.
            assertEquals(
                com.yawnandpawn.app.ui.format.Countdown
                    .HoursMinutes(23, 0),
                viewModel.state.value.ringsIn,
            )
            viewModel.onIntent(EditorIntent.TimeChanged(LocalTime(8, 45)))
            assertEquals(
                com.yawnandpawn.app.ui.format.Countdown
                    .Minutes(45),
                viewModel.state.value.ringsIn,
            )
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
    fun `in a DST gap the tomorrow note shows the time the alarm actually rings`() =
        runTest(dispatcher) {
            // Helsinki skips 03:00 to 04:00 on Sunday 2027-03-28; now is Saturday 12:00 local.
            clock.set(Instant.parse("2027-03-27T10:00:00Z"))
            val viewModel = viewModel()

            viewModel.onIntent(EditorIntent.TimeChanged(LocalTime(3, 30)))

            assertEquals(LocalTime(4, 30), viewModel.state.value.ringsTomorrowAt)
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
    fun `Duplicate copies the stored alarm and opens the copy`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            ids.newId() // the stored alarm's id; the copy gets the next one
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.hasOverflowMenu)

            viewModel.onIntent(EditorIntent.LabelChanged("Unsaved"))
            viewModel.onIntent(EditorIntent.DuplicateClicked)
            advanceUntilIdle()

            val copy = repository.current.single { it.id != stored.id }
            assertEquals("Gym", copy.label, "the copy is of the stored alarm")
            assertEquals(listOf<EditorEffect>(EditorEffect.OpenCopy(copy.id)), effects)
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
    fun `a failed Duplicate is logged, opens nothing and leaves the editor usable`() =
        runTest(dispatcher) {
            repository.upsert(stored)
            val viewModel = viewModel(stored.id)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            repository.failure = DomainError.StorageFailure("disk full")

            viewModel.onIntent(EditorIntent.DuplicateClicked)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isSaving)
            assertTrue(effects.isEmpty())
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("duplicate alarm", "storage failure: disk full")), logger.events)

            viewModel.onIntent(EditorIntent.BackRequested)
            advanceUntilIdle()

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
    fun `a failed delete keeps the editor open and logs the error`() =
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
            assertTrue(effects.isEmpty())
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("delete alarm", "storage failure: disk full")), logger.events)
        }
}
