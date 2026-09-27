package com.yawnandpawn.app.debug.preview

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.FullEditorSections
import com.yawnandpawn.app.ui.editor.RepeatChoice
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.home.AlarmCard
import com.yawnandpawn.app.ui.home.HomeIntent
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.shell.TabPlaceholder
import com.yawnandpawn.app.ui.sound.SoundPickerIntent

/** A pushed screen of the tap-through (the shell with its tabs is the root). */
private sealed interface Pushed {
    data class Editor(
        val alarmId: String?,
    ) : Pushed

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
    val wake = PreviewWakeFlow()

    private fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    private fun openWake(test: Boolean) {
        wake.start(test)
        stack.add(Pushed.Wake)
    }

    /**
     * Back: an editor sub-screen returns to the editor, a pushed screen pops; `false` at the root and on wake screens,
     * which in the preview go back to the menu (a real alarm ignores Back; design preview feedback item 6).
     */
    fun back(): Boolean {
        when (stack.lastOrNull()) {
            null, Pushed.Wake -> return false
            is Pushed.Editor -> if (editor.pane != EditorPane.Main) editor = editor.copy(pane = EditorPane.Main) else pop()
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

            EditorIntent.BackRequested -> {
                if (editor.pane != EditorPane.Main) editor = editor.copy(pane = EditorPane.Main) else pop()
            }

            EditorIntent.DiscardConfirmed -> {
                pop()
            }

            is EditorIntent.Sound -> {
                editor = editor.withSound(intent.intent)
            }

            EditorIntent.TestAlarmClicked -> {
                openWake(test = true)
            }

            else -> {
                editor = reduceEditor(editor, intent)
            }
        }
    }

    fun onWakeFinished() {
        home = home.copy(sessionInProgress = false)
        stack.clear()
    }
}

/**
 * The daily loop, tappable like the finished app with fake state only: bottom navigation, Home, the full editor and its
 * sub-screens (every control responds; Save updates the card), and the wake flow (see [PreviewWakeFlow]).
 * [startInSession] opens Home in the session lock ("Back to alarm" opens Ringing). Back walks back; at the root and on
 * wake screens [onExit] returns to the preview menu. Pushed screens slide in and out.
 */
@Composable
fun TapThrough(
    is24Hour: Boolean,
    onExit: () -> Unit,
    startInSession: Boolean = false,
) {
    val state = remember { TapThroughState(startInSession) }
    BackHandler { if (!state.back()) onExit() }
    AnimatedContent(
        targetState = state.stack.lastOrNull(),
        transitionSpec = {
            // Deeper (Home, editor, wake) slides in from the end; back slides the other way.
            if (targetState != null) {
                (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / PARALLAX } + fadeOut())
            } else {
                (slideInHorizontally { -it / PARALLAX } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "tap-through screen",
    ) { top -> Screen(top = top, state = state, is24Hour = is24Hour) }
}

@Composable
private fun Screen(
    top: Pushed?,
    state: TapThroughState,
    is24Hour: Boolean,
) {
    when (top) {
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

/** The outgoing screen moves a quarter of the way, the incoming one the whole width. */
private const val PARALLAX = 4

private const val LOCKED_ALARM_ID = "2"
