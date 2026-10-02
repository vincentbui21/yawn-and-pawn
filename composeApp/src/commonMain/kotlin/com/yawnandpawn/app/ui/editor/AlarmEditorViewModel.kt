package com.yawnandpawn.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmRule
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.alarm.nextOccurrence
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.errorOrNull
import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.format.countdownOf
import com.yawnandpawn.app.ui.home.AlarmActions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
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
import kotlin.time.Instant

/** The Koin parameter of [AlarmEditorViewModel]: which alarm to edit, `null` for a new one. */
data class AlarmEditorArgs(
    val alarmId: String?,
)

/**
 * The Alarm editor (Story 1.8): loads the alarm with [alarmId] (or starts from the defaults), keeps the form, and
 * saves it through [SaveAlarm] as an enabled alarm. Back with unsaved changes asks "Discard changes?" first; on a
 * sub-screen (Sound, Snooze) Back returns to the main screen. "Rings in ..." and "Rings tomorrow" are computed with
 * `nextOccurrence` from [clock] and [timeZoneProvider] (never the system clock). A stored alarm that cannot be read
 * closes the editor with [EditorEffect.OpenFailed] (Home shows "Couldn't open this alarm."); an alarm that was read
 * gets the overflow menu (Story 1.9): Duplicate and Delete through [actions]. The Sound sub-screen (Story 1.17) lists
 * the built-in sounds and the phone's alarm ringtones ([soundLibrary]) and previews one at a time ([soundPreview]); the
 * preview stops on leaving the sub-screen, on [EditorIntent.Backgrounded] and when the editor closes. "Test alarm"
 * (Story 1.18) rings the form as it is now, unsaved changes included, as a test 10 s later through [testAlarm], then
 * shows its snackbar.
 */
@Suppress("TooManyFunctions") // One small handler per editor action (save, back, repeat, menu, test).
class AlarmEditorViewModel(
    private val alarmId: String?,
    private val repository: AlarmRepository,
    private val saveAlarm: SaveAlarm,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val actions: AlarmActions,
    private val soundLibrary: SoundLibrary,
    private val soundPreview: SoundPreview,
    private val notificationPermission: NotificationPermission,
    private val testAlarm: ScheduleTestAlarm,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorUiState(isNew = alarmId == null, isLoading = alarmId != null))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _effects = Channel<EditorEffect>(Channel.BUFFERED)
    val effects: Flow<EditorEffect> = _effects.receiveAsFlow()

    /** The form as it was opened; any difference is an unsaved change. */
    private var initialForm = EditorForm()

    /** The stored alarm being edited: supplies the fields the editor does not show yet (grace window). */
    private var stored: Alarm? = null

    /** The Sound row, the Sound sub-screen's list and its preview (Story 1.17). */
    private val sounds = EditorSounds(soundLibrary, soundPreview, viewModelScope, _state)

    /** A "Test alarm" is being armed (Story 1.18). */
    private var testInFlight = false

    init {
        // A preview never outlives the editor.
        addCloseable { sounds.stopPreview() }
        if (alarmId == null) {
            _state.update { it.withRings(it.form, clock.now(), timeZoneProvider.current()) }
            sounds.opened()
        } else {
            viewModelScope.launch { load(alarmId) }
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
                close()
            }

            EditorIntent.KeepEditing -> {
                _state.update { it.copy(showDiscardDialog = false) }
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

            else -> {
                onMenuIntent(intent)
            }
        }
    }

    private suspend fun load(id: String) {
        when (val result = repository.get(id)) {
            is Outcome.Success -> {
                val alarm = result.value
                stored = alarm
                val form = alarm.toForm()
                initialForm = form
                val custom = form.repeatDays.isNotEmpty() && form.repeatDays != Weekdays
                _state.update {
                    it
                        .copy(isLoading = false, form = form, customRepeat = custom, hasOverflowMenu = true)
                        .withRings(form, clock.now(), timeZoneProvider.current())
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
            current.copy(form = form, fieldError = fieldError).withRings(form, clock.now(), timeZoneProvider.current())
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
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            when (val result = saveAlarm(current.form.toDraft(alarmId, stored))) {
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
            val result = withContext(NonCancellable) { testAlarm(current.form.toDraft(alarmId, stored)) }
            testInFlight = false
            when (result) {
                is Outcome.Success -> _effects.trySend(EditorEffect.ShowTestScheduled)
                is Outcome.Failure -> actions.logFailure("schedule test alarm", result.error)
            }
        }
    }

    private suspend fun showFailure(error: DomainError) {
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
                _state.update { it.copy(showDiscardDialog = false) }
            }

            current.pane != EditorPane.Main -> {
                // Leaving the Sound sub-screen stops its preview.
                sounds.stopPreview()
                _state.update { it.copy(pane = EditorPane.Main) }
            }

            !current.isLoading && current.form != initialForm -> {
                _state.update { it.copy(showDiscardDialog = true) }
            }

            else -> {
                close()
            }
        }
    }

    private fun close() {
        _state.update { it.copy(showDiscardDialog = false) }
        _effects.trySend(EditorEffect.Close)
    }

    /**
     * The overflow menu of a stored alarm. Duplicate copies the stored alarm (not unsaved changes) and opens the copy;
     * Delete asks with the stored time, then deletes (logged) and closes. While either runs nothing else is accepted.
     */
    private fun onMenuIntent(intent: EditorIntent) {
        val id = alarmId?.takeIf { _state.value.hasOverflowMenu } ?: return
        when (intent) {
            EditorIntent.DuplicateClicked -> {
                _state.update { it.copy(isSaving = true) }
                viewModelScope.launch {
                    val result = actions.duplicate(id)
                    if (result is Outcome.Success) {
                        _effects.send(EditorEffect.OpenCopy(result.value.id))
                    } else {
                        _state.update { it.copy(isSaving = false) }
                    }
                }
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
                    if (gone) close() else _state.update { it.copy(isSaving = false) }
                }
            }

            else -> {
                Unit
            }
        }
    }
}

/** Asks once for notifications; a failure to ask is logged (the Home banner still shows the missing permission). */
@Suppress("TooGenericExceptionCaught")
private fun NotificationPermission.askOnce(actions: AlarmActions) {
    try {
        if (shouldRequest()) request()
    } catch (e: Exception) {
        actions.logFailure("request notification permission", e)
    }
}

private fun EditorForm.toDraft(
    alarmId: String?,
    stored: Alarm?,
): AlarmDraft =
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
        graceSeconds = (stored?.graceSeconds ?: Alarm.DEFAULT_GRACE_SECONDS).coerceIn(Alarm.GRACE_SECONDS_RANGE),
    )

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

/** The stored alarm as the form shows it; out-of-range stored values open as the nearest valid value. */
private fun Alarm.toForm(): EditorForm =
    EditorForm(
        time = time,
        repeatDays = repeatDays,
        label = label.orEmpty(),
        snoozeLengthMinutes = snoozeLengthMinutes.takeIf { it in Alarm.SNOOZE_LENGTHS_MINUTES } ?: Alarm.DEFAULT_SNOOZE_LENGTH_MINUTES,
        volumePercent = volumePercent.coerceIn(Alarm.PERCENT_RANGE),
        gradualVolume = gradualVolume,
        rampStartPercent = rampStartPercent.coerceIn(Alarm.PERCENT_RANGE),
        vibration = vibration,
        soundRef = soundRef.ifBlank { Alarm.DEFAULT_SOUND_REF },
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
