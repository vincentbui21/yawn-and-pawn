package com.yawnandpawn.app.debug.preview

import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.FullEditorSections
import com.yawnandpawn.app.ui.editor.RepeatChoice
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.sound.SoundPickerIntent

/** Every editor control responds; nothing is validated or stored. */
internal fun reduceEditor(
    state: EditorUiState,
    intent: EditorIntent,
): EditorUiState {
    val form = state.form
    val full = state.full
    return when (intent) {
        is EditorIntent.TimeChanged -> {
            state.copy(form = form.copy(time = intent.time))
        }

        is EditorIntent.DayToggled -> {
            state.copy(form = form.copy(repeatDays = form.repeatDays.toggle(intent.day)))
        }

        is EditorIntent.LabelChanged -> {
            state.copy(form = form.copy(label = intent.label))
        }

        is EditorIntent.SnoozeLengthSelected -> {
            state.copy(form = form.copy(snoozeLengthMinutes = intent.minutes))
        }

        is EditorIntent.VolumeChanged -> {
            state.copy(form = form.copy(volumePercent = intent.percent))
        }

        is EditorIntent.GradualVolumeToggled -> {
            state.copy(form = form.copy(gradualVolume = intent.enabled))
        }

        is EditorIntent.VibrationToggled -> {
            state.copy(form = form.copy(vibration = intent.enabled))
        }

        else -> {
            state.copy(full = full?.let { reduceFull(it, intent) }).let { reduceNavigation(it, intent) }
        }
    }
}

/** The full-editor rows (checks, quiet time, motivation). */
private fun reduceFull(
    full: FullEditorSections,
    intent: EditorIntent,
): FullEditorSections =
    when (intent) {
        is EditorIntent.GraceChanged -> {
            full.copy(graceSeconds = intent.seconds)
        }

        is EditorIntent.VibrateInGraceToggled -> {
            full.copy(vibrateInGrace = intent.enabled)
        }

        is EditorIntent.CheckModeSelected -> {
            full.copy(checkMode = intent.mode)
        }

        is EditorIntent.MotivationTimingSelected -> {
            full.copy(motivationTiming = intent.timing)
        }

        is EditorIntent.MotivationChosen -> {
            full.copy(motivation = intent.choice)
        }

        is EditorIntent.CheckToggled -> {
            full.withCheck(intent.type, intent.selected)
        }

        is EditorIntent.DifficultySelected -> {
            full.copy(checks = full.checks.map { it.copy(difficulty = intent.difficulty) })
        }

        is EditorIntent.CheckMoved -> {
            full.copy(checks = full.checks.moved(intent.type, intent.up))
        }

        else -> {
            full
        }
    }

/** Sub-screens and the repeat quick choices. */
private fun reduceNavigation(
    state: EditorUiState,
    intent: EditorIntent,
): EditorUiState =
    when (intent) {
        is EditorIntent.PaneOpened -> {
            state.copy(pane = intent.pane)
        }

        is EditorIntent.RepeatChosen -> {
            when (intent.choice) {
                RepeatChoice.Once -> state.copy(form = state.form.copy(repeatDays = emptySet()), customRepeat = false)
                RepeatChoice.Weekdays -> state.copy(form = state.form.copy(repeatDays = Weekdays), customRepeat = false)
                RepeatChoice.Custom -> state.copy(customRepeat = true)
            }
        }

        else -> {
            state
        }
    }

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

/** Selecting a check adds it at the current difficulty; the "Pick at least one check." error follows the selection. */
private fun FullEditorSections.withCheck(
    type: CheckType,
    selected: Boolean,
): FullEditorSections {
    val difficulty = checks.firstOrNull()?.difficulty ?: Difficulty.Medium
    val updated =
        if (selected) {
            (checks + CheckChip(type, difficulty)).sortedBy { it.type.ordinal }
        } else {
            checks.filterNot { it.type == type }
        }
    return copy(checks = updated, noCheckError = updated.isEmpty())
}

/** The Sound sub-screen's list: selecting sets the editor's sound; the play button toggles a (silent) preview. */
internal fun EditorUiState.withSound(intent: SoundPickerIntent): EditorUiState {
    val full = full ?: return this
    val sounds = full.sounds
    return when (intent) {
        is SoundPickerIntent.Selected -> {
            val name = sounds.options.first { it.id == intent.id }.name
            copy(full = full.copy(sounds = sounds.copy(selectedId = intent.id), soundName = name, soundMissing = false))
        }

        is SoundPickerIntent.PreviewToggled -> {
            copy(full = full.copy(sounds = sounds.copy(previewingId = if (sounds.previewingId == intent.id) null else intent.id)))
        }

        SoundPickerIntent.PickFileClicked -> {
            this
        }
    }
}
