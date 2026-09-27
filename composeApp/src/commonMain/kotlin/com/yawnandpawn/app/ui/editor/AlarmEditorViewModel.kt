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
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime

/** The Koin parameter of [AlarmEditorViewModel]: which alarm to edit, `null` for a new one. */
data class AlarmEditorArgs(
    val alarmId: String?,
)

/**
 * The Alarm editor (Story 1.8): loads the alarm with [alarmId] (or starts from the defaults), keeps the form, and
 * saves it through [SaveAlarm] as an enabled alarm. Back with unsaved changes asks "Discard changes?" first.
 * "Rings tomorrow" is computed with `nextOccurrence` from [clock] and [timeZoneProvider] (never the system clock).
 */
class AlarmEditorViewModel(
    private val alarmId: String?,
    private val repository: AlarmRepository,
    private val saveAlarm: SaveAlarm,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorUiState(isNew = alarmId == null, isLoading = alarmId != null))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _effects = Channel<EditorEffect>(Channel.BUFFERED)
    val effects: Flow<EditorEffect> = _effects.receiveAsFlow()

    /** The form as it was opened; any difference is an unsaved change. */
    private var initialForm = EditorForm()

    /** The stored alarm being edited: supplies the fields the editor does not show yet (sound, grace window). */
    private var stored: Alarm? = null

    init {
        if (alarmId == null) {
            _state.update { it.copy(ringsTomorrowAt = ringsTomorrowAt(it.form)) }
        } else {
            viewModelScope.launch { load(alarmId) }
        }
    }

    fun onIntent(intent: EditorIntent) {
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

            // A gradual ramp never starts above the target volume (SaveAlarm would reject it), so it follows it down.
            // With gradual volume off the start level is hidden and left alone; toDraft clamps it if it is turned back on.
            is EditorIntent.VolumeChanged -> {
                editForm {
                    val rampStart = if (it.gradualVolume) minOf(it.rampStartPercent, intent.percent) else it.rampStartPercent
                    it.copy(volumePercent = intent.percent, rampStartPercent = rampStart)
                }
            }

            is EditorIntent.GradualVolumeToggled -> {
                editForm { it.copy(gradualVolume = intent.enabled) }
            }

            is EditorIntent.RampStartChanged -> {
                editForm { it.copy(rampStartPercent = minOf(intent.percent, it.volumePercent)) }
            }

            is EditorIntent.VibrationToggled -> {
                editForm { it.copy(vibration = intent.enabled) }
            }

            else -> {
                Unit
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
                _state.update { it.copy(isLoading = false, form = form, ringsTomorrowAt = ringsTomorrowAt(form)) }
            }

            // Deleted meanwhile, or storage unreadable: there is nothing to edit.
            is Outcome.Failure -> {
                close()
            }
        }
    }

    private fun editForm(change: (EditorForm) -> EditorForm) {
        _state.update { current ->
            if (current.isLoading) return@update current
            val form = change(current.form)
            val fieldError = current.fieldError?.takeUnless { it != AlarmField.Label && form != current.form }
            current.copy(form = form, ringsTomorrowAt = ringsTomorrowAt(form), fieldError = fieldError)
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
            when (val result = saveAlarm(current.form.toDraft())) {
                // Stays saving until the screen leaves, so a quick second tap cannot store the alarm twice.
                is Outcome.Success -> {
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

    private suspend fun showFailure(error: DomainError) {
        if (error is DomainError.InvalidAlarm) {
            _state.update { it.copy(fieldError = error.field) }
            // Only the label has its own message on the field; the other controls cannot produce an invalid value.
            if (error.field == AlarmField.Label) return
        }
        _effects.send(EditorEffect.ShowSaveFailed)
    }

    private fun requestBack() {
        val current = _state.value
        when {
            current.showDiscardDialog -> _state.update { it.copy(showDiscardDialog = false) }
            !current.isLoading && current.form != initialForm -> _state.update { it.copy(showDiscardDialog = true) }
            else -> close()
        }
    }

    private fun close() {
        _state.update { it.copy(showDiscardDialog = false) }
        _effects.trySend(EditorEffect.Close)
    }

    /** When a one-time alarm whose time has passed today actually rings tomorrow (shifted in a DST gap), else null. */
    private fun ringsTomorrowAt(form: EditorForm): LocalTime? {
        if (form.repeatDays.isNotEmpty()) return null
        val now = clock.now()
        val zone = timeZoneProvider.current()
        val next = nextOccurrence(AlarmRule(form.time), now, zone)
        val ring = next.toLocalDateTime(zone)
        return if (ring.date != now.toLocalDateTime(zone).date) ring.time else null
    }

    private fun EditorForm.toDraft(): AlarmDraft =
        AlarmDraft(
            id = alarmId,
            time = time,
            repeatDays = repeatDays,
            label = label,
            enabled = true,
            // Fields the editor does not show are made valid here, so the only field error a user can meet is the label.
            soundRef = stored?.soundRef?.takeUnless { it.isBlank() } ?: Alarm.DEFAULT_SOUND_REF,
            volumePercent = volumePercent,
            gradualVolume = gradualVolume,
            rampStartPercent = if (gradualVolume) minOf(rampStartPercent, volumePercent) else rampStartPercent,
            vibration = vibration,
            snoozeLengthMinutes = snoozeLengthMinutes,
            graceSeconds = (stored?.graceSeconds ?: Alarm.DEFAULT_GRACE_SECONDS).coerceIn(Alarm.GRACE_SECONDS_RANGE),
        )
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
