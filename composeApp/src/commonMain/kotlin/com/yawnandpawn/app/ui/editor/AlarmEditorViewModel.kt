package com.yawnandpawn.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmRule
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.alarm.nextOccurrence
import com.yawnandpawn.app.core.alarm.orderedEntries
import com.yawnandpawn.app.core.checks.AccessibilityState
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.errorOrNull
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.checks.CheckRegistry
import com.yawnandpawn.app.ui.checks.CheckTrial
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.PickableCheckTypes
import com.yawnandpawn.app.ui.checks.core
import com.yawnandpawn.app.ui.checks.defaultCount
import com.yawnandpawn.app.ui.checks.toCore
import com.yawnandpawn.app.ui.checks.toUi
import com.yawnandpawn.app.ui.checksetup.CheckSetupIntent
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.format.countdownOf
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.wake.WakeIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.time.Instant
import com.yawnandpawn.app.core.checks.CheckMode as CoreCheckMode

/**
 * The Koin parameter of [AlarmEditorViewModel]: which alarm to edit, `null` for a new one; [copyOf] prefills a new one
 * from that stored alarm (Duplicate).
 */
data class AlarmEditorArgs(
    val alarmId: String?,
    val copyOf: String? = null,
)

/**
 * The Alarm editor (Story 1.8): loads the alarm with [alarmId] (or starts from the defaults, or, for Duplicate, from the
 * settings of the stored alarm [copyOf] as a new alarm that is stored only on Save), keeps the form, and saves it
 * through [SaveAlarm] as an enabled alarm (a new alarm identical to a stored one switches that one on instead). Back
 * (or Duplicate) with unsaved changes asks "Discard changes?" first; on a sub-screen (Sound, Snooze) Back returns to the
 * main screen. "Rings in ..." and "Rings tomorrow" are computed with
 * `nextOccurrence` from [clock] and [timeZoneProvider] (never the system clock). A stored alarm that cannot be read
 * closes the editor with [EditorEffect.OpenFailed] (Home shows "Couldn't open this alarm."); an alarm that was read
 * gets the overflow menu (Story 1.9): Duplicate and Delete through [actions]. The Sound sub-screen (Story 1.17) lists
 * the built-in sounds and the phone's alarm ringtones ([soundLibrary]) and previews one at a time ([soundPreview]); the
 * preview stops on leaving the sub-screen, on [EditorIntent.Backgrounded] and when the editor closes. "Test alarm"
 * (Story 1.18) rings the form as it is now, unsaved changes included, as a test 10 s later through [testAlarm], then
 * shows its snackbar. The Wake-up check row (Story 3.5) edits the alarm's checks ([checkConfigs]) in the form: tick a
 * check, its setup (difficulty and count within the type's range), Random / All and the order; with none, Save shows
 * "Pick at least one check." and stores nothing.
 */
@Suppress("TooManyFunctions") // One small handler per editor action (save, back, repeat, menu, test, checks).
class AlarmEditorViewModel(
    private val alarmId: String?,
    private val repository: AlarmRepository,
    private val checkConfigs: CheckConfigRepository,
    private val saveAlarm: SaveAlarm,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val actions: AlarmActions,
    private val soundLibrary: SoundLibrary,
    private val soundPreview: SoundPreview,
    private val notificationPermission: NotificationPermission,
    private val testAlarm: ScheduleTestAlarm,
    private val copyOf: String? = null,
    /** The checks the Wake-up check sub-screen offers; tests widen it to exercise several checks. */
    private val pickable: List<CheckType> = PickableCheckTypes,
    /** The seed of each "Try it" (Story 3.6): random in the app (a practice run, not a session), fixed in tests. */
    private val previewSeed: () -> Long = { Random.nextLong() },
    /** TalkBack (Story 3.8): the Memory Sequence note and its numbered "Try it". */
    private val accessibility: AccessibilityState = AccessibilityState { false },
) : ViewModel() {
    /** The pending tick of the running preview (Memory's playback). */
    private var trialTicks: Job? = null

    /** The running "Try it" preview, if any (Story 3.6). */
    private var trial: CheckTrial? = null

    private val _state =
        MutableStateFlow(
            EditorUiState(
                isNew = alarmId == null,
                isLoading = alarmId != null || copyOf != null,
                full = wakeCheckOnly(pickable, talkBackOn = accessibility.isScreenReaderOn()),
            ).withChecks(),
        )
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _effects = Channel<EditorEffect>(Channel.BUFFERED)
    val effects: Flow<EditorEffect> = _effects.receiveAsFlow()

    /** The form as it was opened; any difference is an unsaved change. */
    private var initialForm = EditorForm()

    /** The stored alarm being edited, or the one a duplicate is prefilled from (its time names it in the delete dialog). */
    private var stored: Alarm? = null

    /** The Sound row, the Sound sub-screen's list and its preview (Story 1.17). */
    private val sounds = EditorSounds(soundLibrary, soundPreview, viewModelScope, _state)

    /** A "Test alarm" is being armed (Story 1.18). */
    private var testInFlight = false

    /** What "Discard" in the open "Discard changes?" dialog does. */
    private var afterDiscard = AfterDiscard.Close

    init {
        // A preview never outlives the editor.
        addCloseable { sounds.stopPreview() }
        when {
            alarmId != null -> {
                viewModelScope.launch { load(alarmId, asCopy = false) }
            }

            copyOf != null -> {
                viewModelScope.launch { load(copyOf, asCopy = true) }
            }

            else -> {
                _state.update { it.withRings(it.form, clock.now(), timeZoneProvider.current()) }
                sounds.opened()
            }
        }
    }

    fun onIntent(intent: EditorIntent) {
        // Going to the background always stops a preview, even while a save runs.
        if (intent == EditorIntent.Backgrounded) {
            sounds.stopPreview()
            return
        }
        // While a save runs (and after it succeeded, until the editor closes) nothing else is accepted: an edit would be
        // lost, and Back or Discard would close with the alarm stored anyway.
        if (_state.value.isSaving) return
        when (intent) {
            EditorIntent.SaveClicked -> {
                save()
            }

            EditorIntent.BackRequested -> {
                requestBack()
            }

            EditorIntent.DiscardConfirmed -> {
                confirmDiscard()
            }

            EditorIntent.KeepEditing -> {
                hideDiscardDialog()
            }

            is EditorIntent.PaneOpened -> {
                if (intent.pane != EditorPane.Sound) sounds.stopPreview()
                _state.update { if (it.isLoading) it else it.copy(pane = intent.pane) }
            }

            is EditorIntent.RepeatChosen -> {
                chooseRepeat(intent.choice)
            }

            EditorIntent.TestAlarmClicked -> {
                scheduleTest()
            }

            else -> {
                onFormIntent(intent)
            }
        }
    }

    private fun onFormIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.TimeChanged -> {
                editForm { it.copy(time = intent.time) }
            }

            is EditorIntent.DayToggled -> {
                editForm { it.copy(repeatDays = it.repeatDays.toggle(intent.day)) }
            }

            is EditorIntent.LabelChanged -> {
                editLabel(intent.label)
            }

            is EditorIntent.SnoozeLengthSelected -> {
                editForm { it.copy(snoozeLengthMinutes = intent.minutes) }
            }

            is EditorIntent.VolumeChanged -> {
                editForm { it.copy(volumePercent = intent.percent) }
                sounds.volumeChanged(intent.percent)
            }

            is EditorIntent.Sound -> {
                sounds.onIntent(intent.intent, ::editForm)
            }

            is EditorIntent.GradualVolumeToggled -> {
                editForm { it.copy(gradualVolume = intent.enabled) }
            }

            is EditorIntent.VibrationToggled -> {
                editForm { it.copy(vibration = intent.enabled) }
            }

            // Story 3.4: the Quiet time sub-screen.
            is EditorIntent.GraceChanged -> {
                editForm { it.copy(graceSeconds = intent.seconds.coerceIn(Alarm.GRACE_SECONDS_RANGE)) }
            }

            is EditorIntent.VibrateInGraceToggled -> {
                editForm { it.copy(vibrateInGrace = intent.enabled) }
            }

            else -> {
                if (!onCheckIntent(intent)) onMenuIntent(intent)
            }
        }
    }

    /**
     * The Wake-up check sub-screen and Check setup (Story 3.5); false for any other intent. A ticked check is added last
     * at Medium with its type's default count, and only a check the picker offers can be added.
     */
    private fun onCheckIntent(intent: EditorIntent): Boolean {
        when (intent) {
            is EditorIntent.CheckToggled -> {
                toggleCheck(intent.type, intent.selected)
            }

            is EditorIntent.CheckModeSelected -> {
                editForm { it.copy(checkMode = intent.mode) }
            }

            is EditorIntent.CheckMoved -> {
                editForm { it.copy(checks = it.checks.moved(intent.type, intent.up)) }
            }

            is EditorIntent.CheckSetupClicked -> {
                _state.update { if (it.isLoading) it else it.copy(pane = EditorPane.CheckSetup, setupType = intent.type) }
            }

            is EditorIntent.CheckSetup -> {
                onSetupIntent(intent.intent)
            }

            is EditorIntent.TryIt -> {
                onTryItIntent(intent.intent)
            }

            else -> {
                return false
            }
        }
        return true
    }

    /** Ticks or unticks [type]: added last when the picker offers it; any check left clears "Pick at least one check." */
    private fun toggleCheck(
        type: CheckType,
        selected: Boolean,
    ) {
        editForm { form ->
            val has = form.checks.any { it.type == type }
            when {
                selected && !has && type in pickable -> form.copy(checks = form.checks + newCheck(type))
                !selected -> form.copy(checks = form.checks.filterNot { it.type == type })
                else -> form
            }
        }
        _state.update { if (it.form.checks.isEmpty()) it else it.copy(full = it.full?.copy(noCheckError = false)) }
    }

    /** Check setup of [EditorUiState.setupType]: difficulty, count (within the type's range) and Back. */
    private fun onSetupIntent(intent: CheckSetupIntent) {
        val type = _state.value.setupType ?: return
        when (intent) {
            CheckSetupIntent.Back -> {
                requestBack()
            }

            is CheckSetupIntent.DifficultySelected -> {
                editCheck(type) { it.copy(difficulty = intent.difficulty) }
            }

            is CheckSetupIntent.CountChanged -> {
                val range = type.core?.countRange ?: return
                editCheck(type) { it.copy(count = intent.count.coerceIn(range)) }
            }

            CheckSetupIntent.TryItClicked -> {
                startTryIt(type)
            }

            // The camera checks' rows arrive with their stories.
            else -> {
                Unit
            }
        }
    }

    /**
     * "Try it" (Story 3.6): [type] at the difficulty set now, one item, from a fresh [previewSeed]. It runs in this
     * ViewModel's state only (no engine, sound, history or scheduler), and never changes the form.
     */
    private fun startTryIt(type: CheckType) {
        val chip =
            _state.value.form.checks
                .firstOrNull { it.type == type } ?: return
        // TalkBack on: the accessible variant (Memory Sequence's numbered tiles, Story 3.8).
        val started = CheckRegistry.startTrial(type, chip.difficulty, previewSeed(), accessibility.isScreenReaderOn()) ?: return
        _state.update { it.copy(pane = EditorPane.TryIt) }
        showTrial(started)
    }

    /** A tap in the preview: "Done" returns to Check setup, anything else goes to the trial. */
    private fun onTryItIntent(intent: WakeIntent) {
        val current = trial ?: return
        if (intent == WakeIntent.DoneClicked) {
            closeTryIt()
            return
        }
        showTrial(current.onIntent(intent))
    }

    /**
     * Shows [next] and, when it changes by itself (Memory's playback, Story 3.8), ticks it after its delay on this
     * ViewModel's scope: virtual time in tests, and cancelled by the next change or by leaving the preview.
     */
    private fun showTrial(next: CheckTrial) {
        if (next === trial) return
        trial = next
        _state.update { it.copy(tryIt = next.state) }
        trialTicks?.cancel()
        trialTicks =
            next.nextTick?.let { wait ->
                viewModelScope.launch {
                    delay(wait)
                    if (trial === next) showTrial(next.tick())
                }
            }
    }

    /** Back or "Done": Check setup again, with the form as it was. */
    private fun closeTryIt() {
        trialTicks?.cancel()
        trialTicks = null
        trial = null
        _state.update { it.copy(pane = EditorPane.CheckSetup, tryIt = null) }
    }

    private fun editCheck(
        type: CheckType,
        change: (CheckChip) -> CheckChip,
    ) = editForm { form -> form.copy(checks = form.checks.map { if (it.type == type) change(it) else it }) }

    /**
     * Opens the stored alarm [id]: to edit it, or ([asCopy], Duplicate) as a new alarm prefilled with its settings, which
     * stores nothing until Save and has no overflow menu (owner decision 2026-10-05).
     */
    private suspend fun load(
        id: String,
        asCopy: Boolean,
    ) {
        when (val result = repository.get(id).flatMap { alarm -> checkConfigs.forAlarm(id).map { alarm to it } }) {
            is Outcome.Success -> {
                val (alarm, checks) = result.value
                stored = alarm
                val form = alarm.toForm(checks)
                initialForm = form
                val custom = form.repeatDays.isNotEmpty() && form.repeatDays != Weekdays
                _state.update {
                    it
                        .copy(isLoading = false, form = form, customRepeat = custom, hasOverflowMenu = !asCopy)
                        .withRings(form, clock.now(), timeZoneProvider.current())
                        .withChecks()
                }
                sounds.opened()
            }

            // Deleted meanwhile, or storage unreadable: there is nothing to edit, and Home says so.
            is Outcome.Failure -> {
                actions.logFailure("open alarm", result.error)
                _effects.send(EditorEffect.OpenFailed)
            }
        }
    }

    private fun editForm(change: (EditorForm) -> EditorForm) {
        _state.update { current ->
            if (current.isLoading) return@update current
            val form = change(current.form)
            val fieldError = current.fieldError?.takeUnless { it != AlarmField.Label && form != current.form }
            current.copy(form = form, fieldError = fieldError).withRings(form, clock.now(), timeZoneProvider.current()).withChecks()
        }
    }

    private fun editLabel(label: String) {
        editForm { it.copy(label = label) }
        // The label error clears as soon as the label fits again.
        _state.update { current ->
            if (current.fieldError == AlarmField.Label && !isLabelTooLong(label)) current.copy(fieldError = null) else current
        }
    }

    private fun save() {
        val current = _state.value
        if (current.isLoading || current.isSaving) return
        // No check: "Pick at least one check." under the Wake-up check row and in its sub-screen; nothing is stored.
        if (current.form.checks.isEmpty()) {
            _state.update { it.copy(full = it.full?.copy(noCheckError = true)) }
            return
        }
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            when (val result = saveAlarm(current.form.toDraft(alarmId))) {
                // Stays saving until the screen leaves, so a quick second tap cannot store the alarm twice.
                is Outcome.Success -> {
                    // The first save of an enabled alarm (every editor save is one) asks for notifications, once
                    // (Story 1.19); the dialog shows over Home after the editor closes. The alarm is saved, so the
                    // editor closes even when asking fails.
                    notificationPermission.askOnce(actions)
                    close()
                }

                is Outcome.Failure -> {
                    if (result.error is DomainError.NotFound) {
                        // Deleted elsewhere while open: there is nothing left to save into.
                        close()
                    } else {
                        _state.update { it.copy(isSaving = false) }
                        showFailure(result.error)
                    }
                }
            }
        }
    }

    /**
     * "Test alarm": the form as it is now (unsaved changes included, built like a save) rings as a test 10 s later,
     * then "Lock your phone. We'll ring in 10 seconds." shows. A test that cannot be armed is logged and shows nothing.
     */
    private fun scheduleTest() {
        val current = _state.value
        // One test at a time: a second tap while one is being armed would interleave the store and the arming.
        if (current.isLoading || testInFlight) return
        // The same label rule as Save: an over-long label shows its field error and rings no test.
        if (isLabelTooLong(current.form.label)) {
            _state.update { it.copy(fieldError = AlarmField.Label) }
            return
        }
        testInFlight = true
        viewModelScope.launch {
            // Not cancelled by the editor closing: the config is stored and the alarm armed (or both taken back).
            val result = withContext(NonCancellable) { testAlarm(current.form.toDraft(alarmId)) }
            testInFlight = false
            when (result) {
                is Outcome.Success -> _effects.trySend(EditorEffect.ShowTestScheduled)
                is Outcome.Failure -> actions.logFailure("schedule test alarm", result.error)
            }
        }
    }

    private suspend fun showFailure(error: DomainError) {
        // A session started meanwhile (Story 2.6): the session lock closes the editor, nothing failed.
        if (error == DomainError.SessionActive) return
        // No check never gets here: Save shows "Pick at least one check." first, and the picker offers valid checks only.
        if (error is DomainError.InvalidAlarm) {
            _state.update { it.copy(fieldError = error.field) }
            // Only the label has its own message on the field; the other controls cannot produce an invalid value.
            if (error.field == AlarmField.Label) return
        }
        _effects.send(EditorEffect.ShowSaveFailed)
    }

    /** "Once" clears the days, "Weekdays" sets Monday to Friday, "Custom" shows the day chips and keeps the days. */
    private fun chooseRepeat(choice: RepeatChoice) {
        when (choice) {
            RepeatChoice.Once -> editForm { it.copy(repeatDays = emptySet()) }
            RepeatChoice.Weekdays -> editForm { it.copy(repeatDays = Weekdays) }
            RepeatChoice.Custom -> Unit
        }
        _state.update { if (it.isLoading) it else it.copy(customRepeat = choice == RepeatChoice.Custom) }
    }

    private fun requestBack() {
        val current = _state.value
        when {
            current.showDiscardDialog -> {
                hideDiscardDialog()
            }

            current.pane == EditorPane.TryIt -> {
                closeTryIt()
            }

            current.pane == EditorPane.CheckSetup -> {
                // Check setup returns to the Wake-up check sub-screen it was opened from.
                _state.update { it.copy(pane = EditorPane.WakeCheck, setupType = null) }
            }

            current.pane != EditorPane.Main -> {
                // Leaving the Sound sub-screen stops its preview.
                sounds.stopPreview()
                _state.update { it.copy(pane = EditorPane.Main) }
            }

            hasUnsavedChanges() -> {
                askDiscard(AfterDiscard.Close)
            }

            else -> {
                close()
            }
        }
    }

    private fun hasUnsavedChanges(): Boolean = _state.value.let { !it.isLoading && it.form != initialForm }

    /** "Discard changes?" before [then]: Back closes the editor, Duplicate opens a new alarm prefilled from the stored one. */
    private fun askDiscard(then: AfterDiscard) {
        afterDiscard = then
        _state.update { it.copy(showDiscardDialog = true) }
    }

    private fun hideDiscardDialog() {
        afterDiscard = AfterDiscard.Close
        _state.update { it.copy(showDiscardDialog = false) }
    }

    /** "Discard": does what asked the dialog (Back closes, Duplicate opens a new alarm prefilled from the stored one). */
    private fun confirmDiscard() {
        // A second tap after the dialog went (or a stale event) does nothing.
        if (!_state.value.showDiscardDialog) return
        val then = afterDiscard
        hideDiscardDialog()
        when (then) {
            AfterDiscard.Close -> close()
            AfterDiscard.Duplicate -> alarmId?.let(::duplicate)
        }
    }

    private fun close() {
        _state.update { it.copy(showDiscardDialog = false) }
        _effects.trySend(EditorEffect.Close)
    }

    /**
     * The overflow menu of a stored alarm. Duplicate opens a new, unsaved alarm prefilled from the stored alarm (not
     * from unsaved changes; owner decision 2026-10-05);
     * with unsaved changes it asks "Discard changes?" first (owner decision 2026-10-02). Delete asks with the stored
     * time, then deletes (logged) and closes. While either runs nothing else is accepted; a failure shows "Couldn't save
     * the alarm. Try again." (owner decision 2026-10-02).
     */
    private fun onMenuIntent(intent: EditorIntent) {
        val id = alarmId?.takeIf { _state.value.hasOverflowMenu } ?: return
        when (intent) {
            EditorIntent.DuplicateClicked -> {
                if (hasUnsavedChanges()) askDiscard(AfterDiscard.Duplicate) else duplicate(id)
            }

            EditorIntent.DeleteClicked -> {
                _state.update { it.copy(deleteDialogTime = stored?.time ?: it.form.time) }
            }

            EditorIntent.DeleteCancelled -> {
                _state.update { it.copy(deleteDialogTime = null) }
            }

            EditorIntent.DeleteConfirmed -> {
                // Cleared first, so a second tap on "Delete" cannot delete (and log) twice.
                if (_state.value.deleteDialogTime == null) return
                _state.update { it.copy(deleteDialogTime = null, isSaving = true) }
                viewModelScope.launch {
                    val result = actions.delete(id)
                    // Gone already (deleted elsewhere) also leaves nothing to edit.
                    val gone = result is Outcome.Success || result.errorOrNull() is DomainError.NotFound
                    if (gone) {
                        close()
                    } else {
                        _state.update { it.copy(isSaving = false) }
                        showFailure(result.errorOrNull() ?: return@launch)
                    }
                }
            }

            else -> {
                Unit
            }
        }
    }

    /** Opens a new, unsaved alarm prefilled from the stored alarm [id] in place of this editor (nothing is stored). */
    private fun duplicate(id: String) {
        // From now on this editor only leaves: a Save tapped before the navigation must not store the discarded edits.
        _state.update { it.copy(isSaving = true) }
        _effects.trySend(EditorEffect.OpenCopy(id))
    }
}

/** What asked "Discard changes?": Back (close the editor) or the overflow menu's Duplicate (open the copy). */
private enum class AfterDiscard { Close, Duplicate }

/** Asks once for notifications; a failure to ask is logged (the Home banner still shows the missing permission). */
@Suppress("TooGenericExceptionCaught")
private fun NotificationPermission.askOnce(actions: AlarmActions) {
    try {
        if (shouldRequest()) request()
    } catch (e: Exception) {
        actions.logFailure("request notification permission", e)
    }
}

private fun EditorForm.toDraft(alarmId: String?): AlarmDraft =
    AlarmDraft(
        id = alarmId,
        time = time,
        repeatDays = repeatDays,
        label = label,
        enabled = true,
        // Fields the editor does not show are made valid here, so the only field error a user can meet is the label.
        // The chosen sound, kept even when it is missing: the alarm then rings the default (never silent).
        soundRef = soundRef.ifBlank { Alarm.DEFAULT_SOUND_REF },
        volumePercent = volumePercent,
        gradualVolume = gradualVolume,
        // Not editable (owner decision 2026-09-27): the ramp starts at the fixed 20% of the set volume, at any volume.
        rampStartPercent = Alarm.DEFAULT_RAMP_START_PERCENT,
        vibration = vibration,
        snoozeLengthMinutes = snoozeLengthMinutes,
        graceSeconds = graceSeconds.coerceIn(Alarm.GRACE_SECONDS_RANGE),
        vibrateInGrace = vibrateInGrace,
        checks = checks.mapNotNull { chip -> chip.type.core?.let { CheckEntry(it, chip.difficulty.toCore(), chip.count) } },
        checkMode = CoreCheckMode.valueOf(checkMode.name),
    )

/**
 * The production editor's full-editor rows (Story 3.5): only the Wake-up check, offering [pickable] (the Quiet time row
 * shows in every editor, Story 3.4); motivation and the fee ladder wait for their stories.
 */
private fun wakeCheckOnly(
    pickable: List<CheckType>,
    talkBackOn: Boolean,
): FullEditorSections = FullEditorSections(rows = setOf(EditorPane.WakeCheck), types = pickable, talkBackOn = talkBackOn)

/** The Wake-up check row and sub-screen show the form's checks and mode. */
private fun EditorUiState.withChecks(): EditorUiState = copy(full = full?.copy(checks = form.checks, checkMode = form.checkMode))

/** A newly ticked check: Medium, with its type's default count (Story 3.5). */
private fun newCheck(type: CheckType): CheckChip = CheckChip(type, Difficulty.Medium, type.core?.defaultCount ?: type.defaultCount)

/** [type] one place up or down, or the list unchanged at either end. */
private fun List<CheckChip>.moved(
    type: CheckType,
    up: Boolean,
): List<CheckChip> {
    val from = indexOfFirst { it.type == type }
    val to = if (up) from - 1 else from + 1
    if (from < 0 || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** "Rings in ..." and, for a one-time alarm whose time has passed today, when it rings tomorrow (shifted in a DST gap). */
private fun EditorUiState.withRings(
    form: EditorForm,
    now: Instant,
    zone: TimeZone,
): EditorUiState {
    val next = nextOccurrence(AlarmRule(form.time, form.repeatDays), now, zone)
    val ring = next.toLocalDateTime(zone)
    val tomorrow = form.repeatDays.isEmpty() && ring.date != now.toLocalDateTime(zone).date
    return copy(ringsIn = countdownOf(next - now), ringsTomorrowAt = if (tomorrow) ring.time else null)
}

/**
 * The stored alarm and its [checks] as the form shows them; out-of-range stored values open as the nearest valid value,
 * and an alarm stored without checks opens with the default ones.
 */
private fun Alarm.toForm(checks: List<CheckConfig>): EditorForm =
    EditorForm(
        checks =
            checks
                .orderedEntries()
                .mapNotNull { entry -> entry.type.toUi()?.let { CheckChip(it, entry.difficulty.toUi(), entry.count) } }
                .ifEmpty { EditorForm.DEFAULT_CHECKS },
        checkMode = CheckMode.valueOf(checkMode.name),
        time = time,
        repeatDays = repeatDays,
        label = label.orEmpty(),
        snoozeLengthMinutes = snoozeLengthMinutes.takeIf { it in Alarm.SNOOZE_LENGTHS_MINUTES } ?: Alarm.DEFAULT_SNOOZE_LENGTH_MINUTES,
        volumePercent = volumePercent.coerceIn(Alarm.PERCENT_RANGE),
        gradualVolume = gradualVolume,
        rampStartPercent = rampStartPercent.coerceIn(Alarm.PERCENT_RANGE),
        vibration = vibration,
        soundRef = soundRef.ifBlank { Alarm.DEFAULT_SOUND_REF },
        graceSeconds = graceSeconds.coerceIn(Alarm.GRACE_SECONDS_RANGE),
        vibrateInGrace = vibrateInGrace,
    )

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

/** Same rule as `SaveAlarm`: trimmed, counted in code points (a surrogate pair is one character). */
internal fun isLabelTooLong(label: String): Boolean {
    val trimmed = label.trim()
    var count = 0
    var index = 0
    while (index < trimmed.length) {
        val pair = trimmed[index].isHighSurrogate() && index + 1 < trimmed.length && trimmed[index + 1].isLowSurrogate()
        index += if (pair) 2 else 1
        count++
    }
    return count > Alarm.MAX_LABEL_LENGTH
}
