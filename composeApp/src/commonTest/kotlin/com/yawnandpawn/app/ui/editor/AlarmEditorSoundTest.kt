package com.yawnandpawn.app.ui.editor

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.sound.SoundCatalog
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundRef
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakeRequestCodeSequence
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.sound.BUILT_IN_SOUND_NAMES
import com.yawnandpawn.app.ui.sound.SoundPickerIntent
import com.yawnandpawn.app.ui.sound.SoundSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Story 1.17: the editor's Sound row, the Sound sub-screen's list and its preview. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorSoundTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.of("Europe/Helsinki"))
    private val repository = FakeAlarmRepository()
    private val alarms =
        AlarmUseCasesFixture(
            repository = repository,
            clock = clock,
            timeZoneProvider = zone,
            requestCodes = FakeRequestCodeSequence(lastUsed = RequestCodes.FIRST_ALARM),
        )
    private val argon = SoundRef.System("content://ringtones/argon", "Argon")
    private val oxygen = SoundRef.System("content://ringtones/oxygen", "Oxygen")
    private val library = FakeSoundLibrary(system = listOf(argon, oxygen))
    private val preview = FakeSoundPreview()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        alarmId: String? = null,
        library: SoundLibrary = this.library,
    ) = AlarmEditorViewModel(
        alarmId = alarmId,
        repository = repository,
        checkConfigs = alarms.checkConfigs,
        saveAlarm = alarms.save,
        clock = clock,
        timeZoneProvider = zone,
        actions = AlarmActions(alarms.setEnabled, alarms.delete, clock, FakeLogger()),
        soundLibrary = library,
        soundPreview = preview,
        notificationPermission = FakeNotificationPermission(),
        testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), clock, FakeLogger()),
    )

    private val AlarmEditorViewModel.sound: EditorSound
        get() = assertNotNull(state.value.sound)

    private fun AlarmEditorViewModel.sound(intent: SoundPickerIntent) = onIntent(EditorIntent.Sound(intent))

    @Test
    fun `a new alarm lists every built-in sound and the phone's alarm ringtones, with the default chosen`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            val sound = viewModel.sound
            val picker = sound.picker
            assertEquals(Alarm.DEFAULT_SOUND_REF, picker.selectedId)
            assertEquals(BUILT_IN_SOUND_NAMES.getValue("default"), sound.nameRes, "the Sound row reads Sunrise")
            assertFalse(sound.missing)
            assertFalse(picker.showFiles, "Your files arrives in Story 7.4")
            assertEquals(
                SoundCatalog.sounds.map { it.ref.encode() },
                picker.options.filter { it.source == SoundSource.BuiltIn }.map { it.id },
            )
            assertTrue(picker.options.filter { it.source == SoundSource.BuiltIn }.all { it.nameRes != null })
            assertEquals(listOf("Argon", "Oxygen"), picker.options.filter { it.source == SoundSource.System }.map { it.name })
        }

    @Test
    fun `choosing a ringtone names it on the Sound row, is an unsaved change and is saved with the alarm`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Sound))

            viewModel.sound(SoundPickerIntent.Selected(oxygen.encode()))
            viewModel.onIntent(EditorIntent.BackRequested)

            assertEquals(EditorPane.Main, viewModel.state.value.pane)
            assertEquals("Oxygen", viewModel.sound.name)
            assertNull(viewModel.sound.nameRes)
            assertEquals(oxygen.encode(), viewModel.sound.picker.selectedId)
            viewModel.onIntent(EditorIntent.BackRequested)
            assertTrue(viewModel.state.value.showDiscardDialog, "a new sound is an unsaved change")
            viewModel.onIntent(EditorIntent.KeepEditing)
            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()
            assertEquals(oxygen.encode(), repository.current.single().soundRef)
        }

    @Test
    fun `a stored built-in sound opens chosen and is kept on save`() =
        runTest(dispatcher) {
            repository.upsert(anAlarm(id = "a1", time = LocalTime(6, 30)).copy(soundRef = "builtin:chimes"))
            val viewModel = viewModel("a1")
            advanceUntilIdle()

            assertEquals("builtin:chimes", viewModel.state.value.form.soundRef)
            assertEquals(BUILT_IN_SOUND_NAMES.getValue("chimes"), viewModel.sound.nameRes)
            assertFalse(viewModel.sound.missing)
            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()
            assertEquals("builtin:chimes", repository.current.single().soundRef)
        }

    @Test
    fun `a ringtone that is gone shows File missing with its own row, its name and no preview`() =
        runTest(dispatcher) {
            val gone = SoundRef.System("content://ringtones/helium", "Helium")
            repository.upsert(anAlarm(id = "a1", time = LocalTime(6, 30)).copy(soundRef = gone.encode()))
            val viewModel = viewModel("a1")
            advanceUntilIdle()

            val sound = viewModel.sound
            assertTrue(sound.missing)
            assertEquals("Helium", sound.name)
            val row = sound.picker.options.single { it.id == gone.encode() }
            assertTrue(row.missing)
            assertEquals(SoundSource.System, row.source)
            assertEquals(gone.encode(), sound.picker.selectedId)
            // Choosing another sound clears it.
            viewModel.sound(SoundPickerIntent.Selected(Alarm.DEFAULT_SOUND_REF))
            assertFalse(viewModel.sound.missing)
            assertTrue(
                viewModel.sound.picker.options
                    .none { it.missing },
            )
        }

    @Test
    fun `a listed ringtone that no longer opens is marked missing on its own row`() =
        runTest(dispatcher) {
            library.missing += argon
            repository.upsert(anAlarm(id = "a1", time = LocalTime(6, 30)).copy(soundRef = argon.encode()))
            val viewModel = viewModel("a1")
            advanceUntilIdle()

            assertTrue(viewModel.sound.missing)
            assertEquals(
                listOf(argon.encode()),
                viewModel.sound.picker.options
                    .filter { it.missing }
                    .map { it.id },
            )
        }

    @Test
    fun `an unknown built-in is missing and named as the default, which is what rings`() =
        runTest(dispatcher) {
            repository.upsert(anAlarm(id = "a1", time = LocalTime(6, 30)).copy(soundRef = "builtin:birds"))
            val viewModel = viewModel("a1")
            advanceUntilIdle()

            assertTrue(viewModel.sound.missing)
            assertEquals(BUILT_IN_SOUND_NAMES.getValue("default"), viewModel.sound.nameRes)
            assertTrue(
                viewModel.sound.picker.options
                    .none { it.missing },
                "no row for a built-in this version lacks",
            )
        }

    @Test
    fun `preview plays one sound at a time at the form volume, follows the slider and stops on a second tap`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Sound))
            viewModel.onIntent(EditorIntent.VolumeChanged(60))

            viewModel.sound(SoundPickerIntent.PreviewToggled("builtin:bell"))
            advanceUntilIdle()
            assertEquals("builtin:bell", viewModel.sound.picker.previewingId)
            viewModel.sound(SoundPickerIntent.PreviewToggled(argon.encode()))
            advanceUntilIdle()
            assertEquals(argon.encode(), viewModel.sound.picker.previewingId, "the second preview replaces the first")
            viewModel.onIntent(EditorIntent.VolumeChanged(70))
            viewModel.sound(SoundPickerIntent.PreviewToggled(argon.encode()))
            advanceUntilIdle()

            assertEquals(listOf("builtin:bell" to 60, argon.encode() to 60), preview.played)
            assertEquals(listOf(70), preview.volumes)
            assertEquals(1, preview.stops)
            assertNull(viewModel.sound.picker.previewingId)
        }

    @Test
    fun `the volume never goes below 10 percent, and a stored 0 percent opens and saves as 10 (Epic 3 device check)`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Sound))

            viewModel.onIntent(EditorIntent.VolumeChanged(0))
            assertEquals(Alarm.MIN_VOLUME_PERCENT, viewModel.state.value.form.volumePercent)
            viewModel.onIntent(EditorIntent.VolumeChanged(5))
            assertEquals(10, viewModel.state.value.form.volumePercent)
            viewModel.onIntent(EditorIntent.VolumeChanged(15))
            assertEquals(15, viewModel.state.value.form.volumePercent)

            repository.upsert(anAlarm(id = "silent", time = LocalTime(6, 30)).copy(volumePercent = 0))
            val stored = viewModel("silent")
            advanceUntilIdle()
            assertEquals(10, stored.state.value.form.volumePercent, "coerced when read")
            stored.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()
            assertEquals(10, repository.current.single { it.id == "silent" }.volumePercent)
        }

    @Test
    fun `a preview that ends by itself clears the playing row`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.sound(SoundPickerIntent.PreviewToggled("builtin:bell"))
            advanceUntilIdle()

            preview.finish()
            advanceUntilIdle()

            assertNull(viewModel.sound.picker.previewingId)
        }

    @Test
    fun `leaving the Sound sub-screen or the app going to the background stops the preview`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Sound))
            viewModel.sound(SoundPickerIntent.PreviewToggled("builtin:bell"))

            viewModel.onIntent(EditorIntent.BackRequested)
            assertEquals(1, preview.stops)
            assertNull(preview.previewing.value)

            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Sound))
            viewModel.sound(SoundPickerIntent.PreviewToggled("builtin:bell"))
            viewModel.onIntent(EditorIntent.Backgrounded)
            assertEquals(2, preview.stops)
            assertEquals(EditorPane.Sound, viewModel.state.value.pane, "backgrounding keeps the sub-screen")
            viewModel.onIntent(EditorIntent.Backgrounded)
            assertEquals(2, preview.stops, "nothing to stop")
        }

    @Test
    fun `opening another pane from the Sound sub-screen stops the preview`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Sound))
            viewModel.sound(SoundPickerIntent.PreviewToggled("builtin:bell"))

            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Snooze))

            assertEquals(1, preview.stops)
            assertNull(preview.previewing.value)
        }

    @Test
    fun `going to the background stops the preview even while a save runs`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.sound(SoundPickerIntent.PreviewToggled("builtin:bell"))
            viewModel.onIntent(EditorIntent.SaveClicked)
            assertTrue(viewModel.state.value.isSaving)

            viewModel.onIntent(EditorIntent.Backgrounded)

            assertEquals(1, preview.stops)
            assertNull(preview.previewing.value)
        }

    @Test
    fun `a listed ringtone that is gone by the time it is chosen is marked missing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            library.missing += oxygen

            viewModel.sound(SoundPickerIntent.Selected(oxygen.encode()))
            assertFalse(viewModel.sound.missing, "not known yet")
            advanceUntilIdle()

            assertTrue(viewModel.sound.missing)
            assertEquals(
                listOf(oxygen.encode()),
                viewModel.sound.picker.options
                    .filter { it.missing }
                    .map { it.id },
            )
        }

    @Test
    fun `a chosen ringtone that plays but is not an alarm ringtone keeps a selected row`() =
        runTest(dispatcher) {
            val notification = SoundRef.System("content://ringtones/ding", "Ding")
            repository.upsert(anAlarm(id = "a1", time = LocalTime(6, 30)).copy(soundRef = notification.encode()))
            // The alarm list does not have it, but its file opens.
            val viewModel =
                viewModel(
                    "a1",
                    object : SoundLibrary {
                        override suspend fun systemSounds() = listOf(argon, oxygen)

                        override suspend fun isAvailable(ref: SoundRef) = true
                    },
                )
            advanceUntilIdle()

            assertFalse(viewModel.sound.missing)
            val row =
                viewModel.sound.picker.options
                    .single { it.id == notification.encode() }
            assertFalse(row.missing)
            assertEquals("Ding", row.name)
            assertEquals(notification.encode(), viewModel.sound.picker.selectedId)
        }

    @Test
    fun `closing the editor stops the preview`() =
        runTest(dispatcher) {
            val store = ViewModelStore()
            val viewModel =
                ViewModelProvider.create(store, viewModelFactory { initializer { viewModel() } })[AlarmEditorViewModel::class]
            advanceUntilIdle()
            viewModel.sound(SoundPickerIntent.PreviewToggled("builtin:bell"))

            store.clear()

            assertNull(preview.previewing.value)
            assertEquals(1, preview.stops)
        }
}
