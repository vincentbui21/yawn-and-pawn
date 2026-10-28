package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.sound.CustomSound
import com.yawnandpawn.app.core.sound.CustomSoundRepository
import com.yawnandpawn.app.core.sound.ImportResult
import com.yawnandpawn.app.core.sound.SoundCatalog
import com.yawnandpawn.app.core.sound.SoundImporter
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.sound.SoundRef
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.sound_your_file
import com.yawnandpawn.app.ui.sound.BUILT_IN_SOUND_NAMES
import com.yawnandpawn.app.ui.sound.SoundFilePicker
import com.yawnandpawn.app.ui.sound.SoundOption
import com.yawnandpawn.app.ui.sound.SoundPickerIntent
import com.yawnandpawn.app.ui.sound.SoundPickerUiState
import com.yawnandpawn.app.ui.sound.SoundSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * The user's own sound files in the editor's Sound sub-screen (Story 7.4): the imported sounds ([sounds]), the system
 * document picker ([picker]) and the importer that checks and copies a picked file ([importer]). Without it the "Your
 * files" section is hidden.
 */
class EditorSoundFiles(
    val sounds: CustomSoundRepository,
    val picker: SoundFilePicker,
    val importer: SoundImporter,
)

/**
 * The editor's sound behaviour (Story 1.17), owned by `AlarmEditorViewModel`: it keeps [state]'s sound section in step
 * with the form's `soundRef`, the phone's alarm ringtones ([library]) and the playing preview ([preview]), checks
 * whether the chosen sound is still there, and plays one preview at a time at the form's volume. With [files] (Story
 * 7.4) it lists the user's imported sounds under "Your files", and "Pick a file" opens the document picker, imports the
 * picked file and selects it; a refused file shows its snackbar through [effects].
 */
internal class EditorSounds(
    private val library: SoundLibrary,
    private val preview: SoundPreview,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<EditorUiState>,
    private val files: EditorSoundFiles? = null,
    private val effects: (EditorEffect) -> Unit = {},
) {
    private var system: List<SoundRef.System> = emptyList()

    /** The imported sounds last read (Story 7.4); empty until read, or when they cannot be read. */
    private var custom: List<CustomSound> = emptyList()

    /** The chosen sound could not be found when it was last checked. */
    private var missing = false

    /** "Pick a file" in progress: the picker is open or the picked file is being imported (one at a time). */
    private var picking: Job? = null

    init {
        scope.launch {
            system = library.systemSounds()
            refresh()
        }
        scope.launch { preview.previewing.collect { refresh() } }
        files?.let { source ->
            scope.launch {
                // A list that cannot be read shows no files; the chosen one is still checked on its own.
                source.sounds
                    .observeAll()
                    .catch { emit(emptyList()) }
                    .collect {
                        custom = it
                        refresh()
                    }
            }
        }
    }

    /** The form was opened (a new alarm, or a stored one loaded): show its sound and check it is still there. */
    fun opened() {
        refresh()
        checkChosen()
    }

    /** Checks the chosen sound can still be played; the answer for a sound chosen since then is dropped. */
    private fun checkChosen() {
        val chosen = state.value.form.soundRef
        scope.launch {
            val available = SoundRef.parse(chosen)?.let { library.isAvailable(it) } ?: false
            if (state.value.form.soundRef == chosen) {
                missing = !available
                refresh()
            }
        }
    }

    /**
     * A tap in the Sound list. Selecting puts the sound in the form through [edit] (an unsaved change); the play button
     * starts its preview, or stops it when it is the one playing.
     */
    fun onIntent(
        intent: SoundPickerIntent,
        edit: ((EditorForm) -> EditorForm) -> Unit,
    ) {
        when (intent) {
            is SoundPickerIntent.Selected -> {
                if (intent.id != state.value.form.soundRef) missing = false
                edit { it.copy(soundRef = intent.id) }
                refresh()
                // A listed ringtone can still be gone (deleted since the list was read).
                checkChosen()
            }

            is SoundPickerIntent.PreviewToggled -> {
                val ref = SoundRef.parse(intent.id)
                when {
                    preview.previewing.value == intent.id -> preview.stop()
                    ref != null -> preview.play(ref, state.value.form.volumePercent)
                }
            }

            SoundPickerIntent.PickFileClicked -> {
                pickFile(edit)
            }
        }
    }

    /**
     * "Pick a file" (Story 7.4): the document picker, then the import. An imported sound is selected (an unsaved change,
     * like a tap on its row); a refused one shows "This file can't be played. Pick another." or "This file is too big.
     * Pick one under 20 MB." and the choice stays. A cancelled picker changes nothing. A tap while a pick is in progress
     * is ignored.
     */
    private fun pickFile(edit: ((EditorForm) -> EditorForm) -> Unit) {
        val source = files ?: return
        if (picking?.isActive == true) return
        picking =
            scope.launch {
                // A sound chosen while the picker or the import runs is the user's newer choice: it is kept.
                val chosenBefore = state.value.form.soundRef
                val uri = source.picker.pick() ?: return@launch
                when (val result = source.importer.import(uri)) {
                    is Outcome.Success -> {
                        when (val imported = result.value) {
                            is ImportResult.Imported -> selectImported(imported.sound, edit, chosenBefore)
                            ImportResult.NotPlayable -> effects(EditorEffect.ShowSoundFileNotPlayable)
                            ImportResult.TooBig -> effects(EditorEffect.ShowSoundFileTooBig)
                        }
                    }

                    // A storage error while copying: nothing was kept, and the file is reported as one that can't be played.
                    is Outcome.Failure -> {
                        effects(EditorEffect.ShowSoundFileNotPlayable)
                    }
                }
            }
    }

    /** Lists the imported [sound] and selects it, unless the chosen sound changed from [chosenBefore] meanwhile. */
    private fun selectImported(
        sound: CustomSound,
        edit: ((EditorForm) -> EditorForm) -> Unit,
        chosenBefore: String,
    ) {
        // Listed at once; the stored list follows.
        if (custom.none { it.id == sound.id }) custom = custom + sound
        if (state.value.form.soundRef == chosenBefore) {
            missing = false
            edit { it.copy(soundRef = sound.ref.encode()) }
        }
        refresh()
    }

    /** The volume slider moved: a playing preview follows it. */
    fun volumeChanged(percent: Int) = preview.setVolume(percent)

    /** Leaving the Sound sub-screen, the app going to the background, or the editor closing. */
    fun stopPreview() {
        if (preview.previewing.value != null) preview.stop()
    }

    private fun refresh() {
        state.update { current ->
            if (current.isLoading) {
                current
            } else {
                current.copy(
                    sound =
                        editorSound(
                            current.form.soundRef,
                            system,
                            missing,
                            preview.previewing.value,
                            files = custom.takeIf { files != null },
                        ),
                )
            }
        }
    }
}

/**
 * The editor's sound section for the chosen [soundRef] (Story 1.17): every catalog sound under "Built-in", the phone's
 * alarm ringtones ([system]) under "System", and (Story 7.4) the user's imported sounds ([files]) under "Your files"
 * with "Pick a file"; without [files] the section is hidden.
 *
 * A [missing] choice shows "File missing. Default sound will play.": a ringtone that is still listed gets the missing
 * caption on its row. A chosen ringtone not in the list (gone, or not an alarm ringtone) gets a row of its own with
 * its stored title. A built-in this version does not have is named as the default sound, which is what rings. A
 * ringtone is matched to the list by its URI, so a renamed ringtone stays selected. Every file row whose copy is gone
 * (a restored phone) has the missing caption and no preview; a chosen file with no row at all is missing and named as
 * the default sound. A file with no name of its own is named "Your file".
 */
fun editorSound(
    soundRef: String,
    system: List<SoundRef.System>,
    missing: Boolean,
    previewing: String?,
    files: List<CustomSound>? = null,
): EditorSound {
    val chosen = SoundRef.parse(soundRef)
    val builtIns =
        SoundCatalog.sounds.map {
            SoundOption(id = it.ref.encode(), name = "", source = SoundSource.BuiltIn, nameRes = BUILT_IN_SOUND_NAMES[it.id])
        }
    val listed = (chosen as? SoundRef.System)?.let { ref -> system.firstOrNull { it.uri == ref.uri } }
    val selectedId = listed?.encode() ?: soundRef
    val systemRows =
        system.map { SoundOption(id = it.encode(), name = it.title, source = SoundSource.System, missing = missing && it == listed) }
    // A chosen ringtone that is not in the list (gone, or not an alarm ringtone) still gets its row, so it shows selected.
    val unlistedRow =
        (chosen as? SoundRef.System)
            ?.takeIf { listed == null }
            ?.let { SoundOption(id = soundRef, name = it.title, source = SoundSource.System, missing = missing) }
    val chosenFile = (chosen as? SoundRef.File)?.let { ref -> files.orEmpty().firstOrNull { it.id == ref.id } }
    val fileRows = files.orEmpty().map { it.option(missing = it.missing || (missing && it == chosenFile)) }
    val builtIn = SoundCatalog.find(chosen) ?: SoundCatalog.default.takeIf { chosen !is SoundRef.System && chosenFile == null }
    return EditorSound(
        name = (chosen as? SoundRef.System)?.title ?: chosenFile?.displayName.orEmpty(),
        nameRes = builtIn?.let { BUILT_IN_SOUND_NAMES[it.id] } ?: chosenFile?.nameRes(),
        missing = missing || chosenFile?.missing == true,
        picker =
            SoundPickerUiState(
                options = builtIns + systemRows + listOfNotNull(unlistedRow) + fileRows,
                selectedId = selectedId,
                previewingId = previewing,
                showFiles = files != null,
            ),
    )
}

/** A user's file as its `sound-row` (Story 7.4). */
private fun CustomSound.option(missing: Boolean): SoundOption =
    SoundOption(id = ref.encode(), name = displayName, source = SoundSource.File, missing = missing, nameRes = nameRes())

/** "Your file" for a file with no name of its own, else none (its own name is shown). */
private fun CustomSound.nameRes(): StringResource? = if (displayName.isBlank()) Res.string.sound_your_file else null
