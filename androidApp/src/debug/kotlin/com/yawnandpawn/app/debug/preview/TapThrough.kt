package com.yawnandpawn.app.debug.preview

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.home.AlarmCard
import com.yawnandpawn.app.ui.home.HomeIntent
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.shell.TabPlaceholder
import com.yawnandpawn.app.ui.sound.SoundPickerIntent
import com.yawnandpawn.app.ui.sound.SoundPickerScreen

/** A pushed screen of the tap-through (the shell with its tabs is the root). */
private sealed interface Pushed {
    data class Editor(
        val alarmId: String?,
    ) : Pushed

    data object Sound : Pushed

    data object Wake : Pushed
}

/** The tap-through's fake app state and what each tap does to it. Nothing is stored, scheduled, played or charged. */
private class TapThroughState(
    startInSession: Boolean,
) {
    var tab by mutableStateOf(AppTab.Alarms)
    val stack = mutableStateListOf<Pushed>()
    var home by mutableStateOf(PreviewSamples.homeList.copy(sessionInProgress = startInSession))
    var editor by mutableStateOf(EditorUiState())
    var sound by mutableStateOf(PreviewSamples.soundPicker)
    val wake = PreviewWakeFlow()

    private fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    private fun openWake(test: Boolean) {
        wake.start(test)
        stack.add(Pushed.Wake)
    }

    /** Back: pops a pushed screen (wake screens ignore it); `false` at the root. */
    fun back(): Boolean {
        when (stack.lastOrNull()) {
            null -> return false
            Pushed.Wake -> Unit
            else -> pop()
        }
        return true
    }

    fun onHome(intent: HomeIntent) {
        when (intent) {
            HomeIntent.AddAlarm -> {
                editor = PreviewSamples.editorNew
                stack.add(Pushed.Editor(null))
            }

            is HomeIntent.EditAlarm -> {
                editor = editorFor(home, intent.id)
                stack.add(Pushed.Editor(intent.id))
            }

            HomeIntent.BackToAlarm -> {
                openWake(test = false)
            }

            else -> {
                home = reduceHome(home, intent)
            }
        }
    }

    fun onEditor(
        alarmId: String?,
        intent: EditorIntent,
    ) {
        when (intent) {
            EditorIntent.SaveClicked -> {
                home = home.saved(alarmId, editor)
                pop()
            }

            EditorIntent.BackRequested, EditorIntent.DiscardConfirmed -> {
                pop()
            }

            EditorIntent.SoundClicked -> {
                stack.add(Pushed.Sound)
            }

            EditorIntent.TestAlarmClicked -> {
                openWake(test = true)
            }

            else -> {
                editor = reduceEditor(editor, intent)
            }
        }
    }

    fun onSound(intent: SoundPickerIntent) {
        when (intent) {
            is SoundPickerIntent.Selected -> {
                sound = sound.copy(selectedId = intent.id)
                val name = sound.options.first { it.id == intent.id }.name
                editor = editor.copy(full = editor.full?.copy(soundName = name, soundMissing = false))
            }

            is SoundPickerIntent.PreviewToggled -> {
                sound =
                    sound.copy(previewingId = if (sound.previewingId == intent.id) null else intent.id)
            }

            SoundPickerIntent.BackRequested -> {
                sound = sound.copy(previewingId = null)
                pop()
            }

            SoundPickerIntent.PickFileClicked -> {
                Unit
            }
        }
    }

    fun onWakeFinished() {
        home = home.copy(sessionInProgress = false)
        stack.clear()
    }
}

/**
 * The daily loop, tappable like the finished app with fake state only: bottom navigation, Home, the full editor
 * (every control responds; Save updates the card), the Sound picker, and the wake flow (see [PreviewWakeFlow]).
 * [startInSession] opens Home in the session lock ("Back to alarm" opens Ringing). Back walks back; at the root
 * [onExit] returns to the preview menu.
 */
@Composable
fun TapThrough(
    is24Hour: Boolean,
    onExit: () -> Unit,
    startInSession: Boolean = false,
) {
    val state = remember { TapThroughState(startInSession) }
    BackHandler { if (!state.back()) onExit() }
    when (val top = state.stack.lastOrNull()) {
        null -> {
            AppShell(selected = state.tab, onSelect = { state.tab = it }, showNavBar = !state.home.sessionInProgress) {
                if (state.tab == AppTab.Alarms) {
                    HomeScreen(state = state.home, is24Hour = is24Hour, onIntent = state::onHome)
                } else {
                    TabPlaceholder(state.tab)
                }
            }
        }

        is Pushed.Editor -> {
            AlarmEditorScreen(state = state.editor, is24Hour = is24Hour, onIntent = { state.onEditor(top.alarmId, it) })
        }

        Pushed.Sound -> {
            SoundPickerScreen(state = state.sound, onIntent = state::onSound)
        }

        Pushed.Wake -> {
            PreviewWakeScreens(flow = state.wake, is24Hour = is24Hour, onFinished = state::onWakeFinished)
        }
    }
}

private fun reduceHome(
    home: HomeUiState,
    intent: HomeIntent,
): HomeUiState =
    when (intent) {
        // The 7:30 sample rings within 8 h, so turning it off asks first (commitment lock).
        is HomeIntent.AlarmToggled -> {
            if (!intent.enabled && intent.id == LOCKED_ALARM_ID) {
                home.copy(disableDialog = PreviewSamples.homeDisableDialog.disableDialog)
            } else {
                home.withEnabled(intent.id, intent.enabled)
            }
        }

        HomeIntent.DisableConfirmed -> {
            home.withEnabled(home.disableDialog?.alarmId, false).copy(disableDialog = null)
        }

        HomeIntent.DisableCancelled -> {
            home.copy(disableDialog = null)
        }

        HomeIntent.MissedNoteDismissed -> {
            home.copy(missedAlarmAt = null)
        }

        HomeIntent.ReregisterDismissed, HomeIntent.ReregisterClicked -> {
            home.copy(reregisterCheck = null)
        }

        else -> {
            home
        }
    }

private fun HomeUiState.withEnabled(
    id: String?,
    enabled: Boolean,
) = copy(alarms = alarms.map { if (it.id == id) it.copy(enabled = enabled) else it })

private fun editorFor(
    home: HomeUiState,
    id: String,
): EditorUiState {
    val card = home.alarms.first { it.id == id }
    val sample = PreviewSamples.editorEdit
    return sample.copy(form = sample.form.copy(time = card.time, repeatDays = card.repeatDays, label = card.label.orEmpty()))
}

private fun HomeUiState.saved(
    id: String?,
    editor: EditorUiState,
): HomeUiState {
    val form = editor.form
    val card =
        AlarmCard(
            id = id ?: "new-${alarms.size + 1}",
            time = form.time,
            repeatDays = form.repeatDays,
            label = form.label.ifBlank { null },
            checks = editor.full?.checks?.map { it.type } ?: listOf(CheckType.Math),
            enabled = true,
        )
    val updated = if (id == null) alarms + card else alarms.map { if (it.id == id) card else it }
    return copy(alarms = updated.sortedBy { it.time })
}

/** Every editor control responds; nothing is validated or stored. */
private fun reduceEditor(
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
            state.copy(form = form.copy(volumePercent = intent.percent, rampStartPercent = minOf(form.rampStartPercent, intent.percent)))
        }

        is EditorIntent.GradualVolumeToggled -> {
            state.copy(form = form.copy(gradualVolume = intent.enabled))
        }

        is EditorIntent.RampStartChanged -> {
            state.copy(form = form.copy(rampStartPercent = minOf(intent.percent, form.volumePercent)))
        }

        is EditorIntent.VibrationToggled -> {
            state.copy(form = form.copy(vibration = intent.enabled))
        }

        is EditorIntent.GraceChanged -> {
            state.copy(full = full?.copy(graceSeconds = intent.seconds))
        }

        is EditorIntent.VibrateInGraceToggled -> {
            state.copy(full = full?.copy(vibrateInGrace = intent.enabled))
        }

        is EditorIntent.CheckModeSelected -> {
            state.copy(full = full?.copy(checkMode = intent.mode))
        }

        is EditorIntent.MotivationTimingSelected -> {
            state.copy(full = full?.copy(motivationTiming = intent.timing))
        }

        else -> {
            state
        }
    }
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

private const val LOCKED_ALARM_ID = "2"
