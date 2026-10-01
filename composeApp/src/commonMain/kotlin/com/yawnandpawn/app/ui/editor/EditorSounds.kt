package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.sound.SoundCatalog
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.sound.SoundRef
import com.yawnandpawn.app.ui.sound.BUILT_IN_SOUND_NAMES
import com.yawnandpawn.app.ui.sound.SoundOption
import com.yawnandpawn.app.ui.sound.SoundPickerIntent
import com.yawnandpawn.app.ui.sound.SoundPickerUiState
import com.yawnandpawn.app.ui.sound.SoundSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The editor's sound behaviour (Story 1.17), owned by `AlarmEditorViewModel`: it keeps [state]'s sound section in step
 * with the form's `soundRef`, the phone's alarm ringtones ([library]) and the playing preview ([preview]), checks
 * whether the chosen sound is still there, and plays one preview at a time at the form's volume.
 */
internal class EditorSounds(
    private val library: SoundLibrary,
    private val preview: SoundPreview,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<EditorUiState>,
) {
    private var system: List<SoundRef.System> = emptyList()

    /** The chosen sound could not be found when it was last checked. */
    private var missing = false

    init {
        scope.launch {
            system = library.systemSounds()
            refresh()
        }
        scope.launch { preview.previewing.collect { refresh() } }
    }

    /** The form was opened (a new alarm, or a stored one loaded): show its sound and check it is still there. */
    fun opened() {
        refresh()
        val chosen = state.value.form.soundRef
        scope.launch {
            val available = SoundRef.parse(chosen)?.let { library.isAvailable(it) } ?: false
            // A choice made meanwhile was picked from the list, so it is there.
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
            }

            is SoundPickerIntent.PreviewToggled -> {
                val ref = SoundRef.parse(intent.id)
                when {
                    preview.previewing.value == intent.id -> preview.stop()
                    ref != null -> preview.play(ref, state.value.form.volumePercent)
                }
            }

            // "Your files" is hidden until Story 7.4.
            SoundPickerIntent.PickFileClicked -> {
                Unit
            }
        }
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
                current.copy(sound = editorSound(current.form.soundRef, system, missing, preview.previewing.value))
            }
        }
    }
}

/**
 * The editor's sound section for the chosen [soundRef] (Story 1.17): every catalog sound under "Built-in", the phone's
 * alarm ringtones ([system]) under "System", and no "Your files" yet (Story 7.4).
 *
 * A [missing] choice shows "File missing. Default sound will play.": a ringtone that is still listed gets the missing
 * caption on its row; one that is gone gets a row of its own with its stored title. A built-in this version does not
 * have is named as the default sound, which is what rings. A ringtone is matched to the list by its URI, so a renamed
 * ringtone stays selected.
 */
fun editorSound(
    soundRef: String,
    system: List<SoundRef.System>,
    missing: Boolean,
    previewing: String?,
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
    val goneRow =
        (chosen as? SoundRef.System)
            ?.takeIf { missing && listed == null }
            ?.let { SoundOption(id = soundRef, name = it.title, source = SoundSource.System, missing = true) }
    val builtIn = SoundCatalog.find(chosen) ?: SoundCatalog.default.takeIf { chosen !is SoundRef.System }
    return EditorSound(
        name = (chosen as? SoundRef.System)?.title.orEmpty(),
        nameRes = builtIn?.let { BUILT_IN_SOUND_NAMES[it.id] },
        missing = missing,
        picker =
            SoundPickerUiState(
                options = builtIns + systemRows + listOfNotNull(goneRow),
                selectedId = selectedId,
                previewingId = previewing,
                showFiles = false,
            ),
    )
}
