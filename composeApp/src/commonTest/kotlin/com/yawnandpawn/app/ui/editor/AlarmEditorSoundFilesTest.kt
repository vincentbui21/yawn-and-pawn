package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.sound.ImportResult
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeCustomSoundRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakeRequestCodeSequence
import com.yawnandpawn.app.testing.FakeSoundImporter
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aCustomSound
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.sound_your_file
import com.yawnandpawn.app.ui.sound.BUILT_IN_SOUND_NAMES
import com.yawnandpawn.app.ui.sound.SoundFilePicker
import com.yawnandpawn.app.ui.sound.SoundPickerIntent
import com.yawnandpawn.app.ui.sound.SoundSource
import kotlinx.coroutines.CompletableDeferred
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

/** Story 7.4: "Your files" in the editor's Sound sub-screen, "Pick a file" and its snackbars. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorSoundFilesTest {
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
    private val rain = aCustomSound(id = "rain", displayName = "Rain")
    private val song = aCustomSound(id = "song", displayName = "Morning song")
    private val sounds = FakeCustomSoundRepository(listOf(rain))
    private val library = FakeSoundLibrary(files = mutableSetOf("rain", "song"))
    private val picker = FakeSoundFilePicker()
    private val importer = FakeSoundImporter(Outcome.Success(ImportResult.Imported(song)), into = sounds)

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
            actions = AlarmActions(alarms.setEnabled, alarms.delete, FakeLogger()),
            soundLibrary = library,
            soundPreview = FakeSoundPreview(),
            notificationPermission = FakeNotificationPermission(),
            testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), clock, FakeLogger()),
            soundFiles = EditorSoundFiles(sounds, picker, importer),
        )

    private val AlarmEditorViewModel.sound: EditorSound
        get() = assertNotNull(state.value.sound)

    private fun AlarmEditorViewModel.sound(intent: SoundPickerIntent) = onIntent(EditorIntent.Sound(intent))

    private fun TestScope.effectsOf(viewModel: AlarmEditorViewModel): List<EditorEffect> {
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        return effects
    }

    @Test
    fun `after Built-in and System, Your files lists each imported sound with its name and Your file caption`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            val picker = viewModel.sound.picker
            assertTrue(picker.showFiles, "Your files and Pick a file are shown")
            assertEquals(listOf(SoundSource.BuiltIn, SoundSource.System, SoundSource.File), picker.options.map { it.source }.distinct())
            val row = picker.options.single { it.source == SoundSource.File }
            assertEquals("file:rain", row.id)
            assertEquals("Rain", row.name)
            assertFalse(row.missing)
        }

    @Test
    fun `a picked file is imported, selected and named on the Sound row, and Save stores file colon id`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onIntent(EditorIntent.PaneOpened(EditorPane.Sound))

            viewModel.sound(SoundPickerIntent.PickFileClicked)
            advanceUntilIdle()

            assertEquals(listOf("content://picked/1"), importer.imported)
            assertEquals("file:song", viewModel.state.value.form.soundRef)
            assertEquals("file:song", viewModel.sound.picker.selectedId)
            assertEquals("Morning song", viewModel.sound.name)
            assertNull(viewModel.sound.nameRes)
            assertFalse(viewModel.sound.missing)
            assertEquals(
                listOf("file:rain", "file:song"),
                viewModel.sound.picker.options
                    .filter { it.source == SoundSource.File }
                    .map { it.id },
            )
            viewModel.onIntent(EditorIntent.BackRequested)
            viewModel.onIntent(EditorIntent.BackRequested)
            assertTrue(viewModel.state.value.showDiscardDialog, "a new sound is an unsaved change")
            viewModel.onIntent(EditorIntent.KeepEditing)
            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()
            assertEquals("file:song", repository.current.single().soundRef)
        }

    @Test
    fun `a cancelled picker changes nothing and imports nothing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            val effects = effectsOf(viewModel)
            picker.answer = null

            viewModel.sound(SoundPickerIntent.PickFileClicked)
            advanceUntilIdle()

            assertEquals(1, picker.picks)
            assertEquals(emptyList(), importer.imported)
            assertEquals(Alarm.DEFAULT_SOUND_REF, viewModel.state.value.form.soundRef)
            assertEquals(emptyList(), effects)
        }

    @Test
    fun `a file that can't be played, a storage error and a file that is too big each show their snackbar and keep the choice`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            val effects = effectsOf(viewModel)

            importer.result = Outcome.Success(ImportResult.NotPlayable)
            viewModel.sound(SoundPickerIntent.PickFileClicked)
            advanceUntilIdle()
            importer.result = Outcome.Failure(DomainError.StorageFailure("import sound: IOException"))
            viewModel.sound(SoundPickerIntent.PickFileClicked)
            advanceUntilIdle()
            importer.result = Outcome.Success(ImportResult.TooBig)
            viewModel.sound(SoundPickerIntent.PickFileClicked)
            advanceUntilIdle()

            assertEquals(
                listOf(EditorEffect.ShowSoundFileNotPlayable, EditorEffect.ShowSoundFileNotPlayable, EditorEffect.ShowSoundFileTooBig),
                effects,
            )
            assertEquals(Alarm.DEFAULT_SOUND_REF, viewModel.state.value.form.soundRef, "the current choice stays")
            assertEquals(listOf(rain), sounds.stored, "nothing saved")
            assertFalse(viewModel.state.value.showDiscardDialog)
        }

    @Test
    fun `a second tap while the picker is open opens nothing more`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            picker.gate = gate

            viewModel.sound(SoundPickerIntent.PickFileClicked)
            advanceUntilIdle()
            viewModel.sound(SoundPickerIntent.PickFileClicked)
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, picker.picks)
            assertEquals(1, importer.imported.size)
        }

    @Test
    fun `a sound chosen while the picker is open is kept when the import finishes, and the file is still listed`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            picker.gate = gate

            viewModel.sound(SoundPickerIntent.PickFileClicked)
            advanceUntilIdle()
            viewModel.sound(SoundPickerIntent.Selected("builtin:chimes"))
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, importer.imported.size)
            assertEquals("builtin:chimes", viewModel.state.value.form.soundRef, "the newer choice stays")
            assertEquals("builtin:chimes", viewModel.sound.picker.selectedId)
            assertTrue(
                viewModel.sound.picker.options
                    .any { it.id == "file:song" },
                "the imported file is listed",
            )
        }

    @Test
    fun `a stored file whose copy is gone shows File missing on its row and the editor, and cannot be previewed`() =
        runTest(dispatcher) {
            sounds.markMissing("rain")
            library.files -= "rain"
            repository.upsert(anAlarm(id = "a1", time = LocalTime(6, 30)).copy(soundRef = "file:rain"))
            val viewModel = viewModel("a1")
            advanceUntilIdle()

            assertTrue(viewModel.sound.missing, "File missing. Default sound will play.")
            assertEquals("Rain", viewModel.sound.name)
            val row =
                viewModel.sound.picker.options
                    .single { it.id == "file:rain" }
            assertTrue(row.missing, "missing caption, preview disabled")
            assertEquals("file:rain", viewModel.sound.picker.selectedId)
        }

    @Test
    fun `a chosen file with no row is missing and named as the default sound`() =
        runTest(dispatcher) {
            repository.upsert(anAlarm(id = "a1", time = LocalTime(6, 30)).copy(soundRef = "file:gone"))
            val viewModel = viewModel("a1")
            advanceUntilIdle()

            assertTrue(viewModel.sound.missing)
            assertEquals(BUILT_IN_SOUND_NAMES.getValue("default"), viewModel.sound.nameRes)
            assertTrue(
                viewModel.sound.picker.options
                    .none { it.missing },
                "no row for an unknown file",
            )
        }

    @Test
    fun `an imported file without a name of its own is listed as Your file`() {
        val unnamed = aCustomSound(id = "x", displayName = "")

        val sound = editorSound("file:x", emptyList(), missing = false, previewing = null, files = listOf(unnamed))

        assertEquals(Res.string.sound_your_file, sound.nameRes)
        assertEquals(
            Res.string.sound_your_file,
            sound.picker.options
                .single { it.source == SoundSource.File }
                .nameRes,
        )
    }

    /** [SoundFilePicker] answering [answer] after [gate], if set; counts [picks]. */
    private class FakeSoundFilePicker : SoundFilePicker {
        var answer: String? = "content://picked/1"
        var gate: CompletableDeferred<Unit>? = null
        var picks = 0
            private set

        override suspend fun pick(): String? {
            picks++
            gate?.await()
            return answer
        }
    }
}
