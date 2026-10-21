package com.yawnandpawn.app.ui.onboarding

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yawnandpawn.app.core.alarm.AlarmRepository
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

/**
 * What onboarding's steps 4 and 5 need (Story 5.11): the editor, where the first alarm's id is remembered, and (review
 * fix) the stored [alarms]: while onboarding is required every stored alarm is onboarding's, so an id that was never
 * stored, or that could not be read once, is found again there instead of starting a second alarm.
 */
class FirstAlarmSources(
    val editors: FirstAlarmEditors,
    val remember: RememberOnboardingAlarm,
    val alarms: AlarmRepository,
)

/**
 * Onboarding's first alarm (Story 5.11): a child [AlarmEditorViewModel] whose form steps 4 and 5 show, so the time,
 * repeat, check picker, Check setup, "Try it", QR registration, save validation and the notification ask behave exactly as
 * in the editor, and the first alarm is the editor's alarm.
 *
 * The child lives in its own [ViewModelStore], cleared when a fresh child replaces it and when onboarding closes, so its
 * coroutines end with it. A child that saved is left alone (it finishes its notification ask, and stays "saving", so it
 * accepts nothing more), and so is one that could not open its alarm; [ensureOpen] then opens a fresh child. Its effects
 * go to [events]:
 * - a save that failed;
 * - "gone" only for an alarm the child could not open (`OpenFailed`) or a save that found it deleted (a close while
 *   saving, without `onSaved`); onboarding decides from the stored alarms what opens next (review fix);
 * - a plain close (Back on the editor's main screen, never meant as "deleted") re-opens a child on the same alarm.
 *
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

        /** The child could not open [id] (or a save found it deleted); null for a new alarm. */
        fun gone(id: String?)

        /** "Couldn't save. Try again." */
        fun saveFailed()
    }

    private val shown = MutableStateFlow<EditorUiState?>(null)

    /** The child's form as shown; null before the first child opens. */
    val state: StateFlow<EditorUiState?> = shown.asStateFlow()

    private var store: ViewModelStore? = null
    private var editor: AlarmEditorViewModel? = null
    private var watch: Job? = null

    /** The alarm the current child edits; null for a new alarm. */
    private var openedOn: String? = null

    /** Numbers the children, so a replaced child's late report is ignored. */
    private var generation = 0

    /** The current child stored the alarm. */
    private var saved = false

    /** The current child could not open its alarm: it accepts nothing, and [ensureOpen] replaces it. */
    private var failed = false

    /** Step 5 is open: the child shows its Wake-up check pane (Check setup's Back returns there, TalkBack is read again). */
    private var checksOpen = false

    /** The current child could not open its alarm (a fresh one opens on the next step 4 / 5 or "Continue"). */
    val failedToOpen: Boolean
        get() = failed

    /** The current child is storing the alarm (until it fails; after a save it is done). */
    val saving: Boolean
        get() = !saved && editor?.state?.value?.isSaving == true

    /** Opens a child on [alarmId] unless one is open that can still be edited (its unsaved form stays). */
    fun ensureOpen(alarmId: String?) {
        val editable = editor != null && !saved && !failed
        if (!editable || openedOn != alarmId) open(alarmId)
    }

    /** Replaces the child with a fresh one on [alarmId] (null: a new alarm). */
    fun open(alarmId: String?) {
        release()
        saved = false
        failed = false
        openedOn = alarmId
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
                child.effects.collect { onEffect(it, child, current) }
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
        child: AlarmEditorViewModel,
        current: Int,
    ) {
        when (effect) {
            EditorEffect.ShowSaveFailed -> {
                events.saveFailed()
            }

            // Launched apart: what opens next replaces this child, whose watch reports it.
            EditorEffect.OpenFailed -> {
                failed = true
                val id = openedOn
                scope.launch { if (current == generation) events.gone(id) }
            }

            // A save closes the editor after onSaved. Without it, a close while saving is a save that found the alarm
            // deleted; any other close (Back on the editor's main screen) leaves the alarm as it is: open it again.
            EditorEffect.Close -> {
                if (!saved) {
                    val id = openedOn
                    val deleted = child.state.value.isSaving
                    scope.launch {
                        when {
                            current != generation -> Unit
                            deleted -> events.gone(id)
                            else -> open(id)
                        }
                    }
                }
            }

            else -> {
                Unit
            }
        }
    }

    /** Sends [intent] to the child, unless it already saved or could not open (a fresh child opens first). */
    fun send(intent: EditorIntent) {
        if (!saved && !failed) editor?.onIntent(intent)
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
