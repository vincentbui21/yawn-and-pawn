package com.yawnandpawn.app.ui.onboarding

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yawnandpawn.app.core.onboarding.RememberOnboardingAlarm
import com.yawnandpawn.app.ui.editor.AlarmEditorViewModel
import com.yawnandpawn.app.ui.editor.EditorEffect
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Builds onboarding's first-alarm editor (Story 5.11): the app's Alarm editor on [create]'s `alarmId` (null for a new
 * alarm with the Settings defaults), calling `onSaved` with the stored alarm's id after each successful save.
 */
fun interface FirstAlarmEditors {
    fun create(
        alarmId: String?,
        onSaved: (String) -> Unit,
    ): AlarmEditorViewModel
}

/** What onboarding's steps 4 and 5 need (Story 5.11): the editor, and where the first alarm's id is remembered. */
class FirstAlarmSources(
    val editors: FirstAlarmEditors,
    val remember: RememberOnboardingAlarm,
)

/**
 * Onboarding's first alarm (Story 5.11): a child [AlarmEditorViewModel] whose form steps 4 and 5 show, so the time,
 * repeat, check picker, Check setup, "Try it", QR registration, save validation and the notification ask behave exactly as
 * in the editor, and the first alarm is the editor's alarm.
 *
 * The child lives in its own [ViewModelStore], cleared when a fresh child replaces it and when onboarding closes, so its
 * coroutines end with it. A child that saved is left alone (it finishes its notification ask, and stays "saving", so it
 * accepts nothing more); [ensureOpen] then opens a fresh child on the stored id. Its effects go to [events]: a save that
 * failed, and a stored alarm that cannot be opened (or was deleted meanwhile, so a save closed it without storing).
 * [state] keeps the last loaded form while a fresh child reads its alarm, so step 4 never flashes the defaults.
 */
internal class OnboardingFirstAlarm(
    private val editors: FirstAlarmEditors,
    private val scope: CoroutineScope,
    private val events: Events,
) : AutoCloseable {
    /** What the child reports to onboarding. */
    interface Events {
        /** The first alarm was stored as [id]. */
        fun saved(id: String)

        /** The stored alarm cannot be read (or is gone): onboarding opens a new alarm and forgets the id. */
        fun openFailed()

        /** "Couldn't save the alarm. Try again." */
        fun saveFailed()
    }

    private val shown = MutableStateFlow<EditorUiState?>(null)

    /** The child's form as shown; null before the first child opens. */
    val state: StateFlow<EditorUiState?> = shown.asStateFlow()

    private var store: ViewModelStore? = null
    private var editor: AlarmEditorViewModel? = null
    private var watch: Job? = null

    /** Numbers the children, so a replaced child's late report is ignored. */
    private var generation = 0

    /** The current child stored the alarm. */
    private var saved = false

    /** Step 5 is open: the child shows its Wake-up check pane (Check setup's Back returns there, TalkBack is read again). */
    private var checksOpen = false

    /** The current child is storing the alarm (until it fails; after a save it is done). */
    val saving: Boolean
        get() = !saved && editor?.state?.value?.isSaving == true

    /** Opens a child on [alarmId] unless one is open that has not saved yet (its unsaved form stays). */
    fun ensureOpen(alarmId: String?) {
        if (editor == null || saved) open(alarmId)
    }

    /** Replaces the child with a fresh one on [alarmId] (null: a new alarm). */
    fun open(alarmId: String?) {
        release()
        saved = false
        val current = ++generation
        val owner = ViewModelStore()
        val factory =
            viewModelFactory {
                initializer {
                    editors.create(alarmId) { id ->
                        if (current == generation) {
                            saved = true
                            events.saved(id)
                        }
                    }
                }
            }
        val child = ViewModelProvider.create(owner, factory)[AlarmEditorViewModel::class]
        store = owner
        editor = child
        watch =
            scope.launch {
                launch { child.state.collect { show(child, it) } }
                child.effects.collect { onEffect(it, current) }
            }
    }

    /** Shows the child's [state] (a fresh child's loading form only when nothing was shown yet). */
    private fun show(
        child: AlarmEditorViewModel,
        state: EditorUiState,
    ) {
        if (!state.isLoading || shown.value == null) shown.value = state
        if (checksOpen && !state.isLoading && state.pane == EditorPane.Main) {
            child.onIntent(EditorIntent.PaneOpened(EditorPane.WakeCheck))
        }
    }

    private fun onEffect(
        effect: EditorEffect,
        current: Int,
    ) {
        when (effect) {
            EditorEffect.ShowSaveFailed -> {
                events.saveFailed()
            }

            // Launched apart: opening the fresh child cancels this child's watch, which reports it.
            EditorEffect.OpenFailed -> {
                scope.launch { if (current == generation) events.openFailed() }
            }

            // A save closes the editor after onSaved; a close without it is an alarm deleted meanwhile.
            EditorEffect.Close -> {
                if (!saved) scope.launch { if (current == generation) events.openFailed() }
            }

            else -> {
                Unit
            }
        }
    }

    /** Sends [intent] to the child, unless it already saved (a fresh child opens on the id first). */
    fun send(intent: EditorIntent) {
        if (!saved) editor?.onIntent(intent)
    }

    /** Step 5 opened: the child's Wake-up check pane (once it has loaded). */
    fun openChecks() {
        checksOpen = true
        send(EditorIntent.PaneOpened(EditorPane.WakeCheck))
    }

    /** Step 5 left. */
    fun leaveChecks() {
        checksOpen = false
    }

    private fun release() {
        watch?.cancel()
        watch = null
        store?.clear()
        store = null
        editor = null
    }

    override fun close() = release()
}
