package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.alarm.MotivationMode
import com.yawnandpawn.app.core.recording.Recording
import com.yawnandpawn.app.core.recording.RecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The editor's Motivation row (Story 7.2), owned by `AlarmEditorViewModel`: keeps [state]'s list of recorded messages in
 * step with [recordings] (the Motivation sub-screen's radio rows; a list that cannot be read is logged through [onReadFailed]
 * and shows none). The choice itself is in the form (`EditorForm.motivation`, and when it plays,
 * `EditorForm.motivationTiming`, Story 7.5), saved only on Save; each list read
 * successfully goes to [onLoaded] with its ids, so the editor can drop a choice that no longer exists.
 */
internal class EditorMotivation(
    private val recordings: RecordingRepository,
    scope: CoroutineScope,
    private val state: MutableStateFlow<EditorUiState>,
    onReadFailed: (Throwable) -> Unit,
    onLoaded: (Set<String>) -> Unit,
) {
    init {
        scope.launch {
            recordings
                .observeAll()
                .map<List<Recording>, List<Recording>?> { it }
                .catch { cause ->
                    onReadFailed(cause)
                    emit(null)
                }.collect { all ->
                    val rows = all.orEmpty().map(Recording::toRow)
                    state.update { it.copy(full = it.full?.copy(recordings = rows)) }
                    // Only a list that was read: an unreadable one must not reset the choice.
                    all?.let { read -> onLoaded(read.map { it.id }.toSet()) }
                }
        }
    }
}

/** The log operation of a recordings list that could not be read (Story 7.2). */
internal const val LOAD_RECORDINGS = "load recordings"

/** The Motivation sub-screen's row of this recording. */
private fun Recording.toRow(): MotivationRecording = MotivationRecording(id = id, name = name, missing = missing)

/** The chosen message as the alarm stores it. */
internal fun MotivationChoice.toCore(): Motivation =
    when (this) {
        MotivationChoice.None -> Motivation.None
        MotivationChoice.Random -> Motivation.Random
        is MotivationChoice.Recording -> Motivation.Recording(id)
    }

/** The stored message as the form shows it. */
internal fun Motivation.toChoice(): MotivationChoice =
    when (this) {
        Motivation.None -> MotivationChoice.None
        Motivation.Random -> MotivationChoice.Random
        is Motivation.Recording -> MotivationChoice.Recording(id)
    }

/** When the chosen message plays, as the alarm stores it (Story 7.5). */
internal fun MotivationTiming.toCore(): MotivationMode =
    when (this) {
        MotivationTiming.AfterImUp -> MotivationMode.AfterImUp
        MotivationTiming.MixIntoAlarm -> MotivationMode.MixIntoAlarm
    }

/** The stored mode as the form shows it (Story 7.5). */
internal fun MotivationMode.toTiming(): MotivationTiming =
    when (this) {
        MotivationMode.AfterImUp -> MotivationTiming.AfterImUp
        MotivationMode.MixIntoAlarm -> MotivationTiming.MixIntoAlarm
    }

/** The Motivation row and sub-screen show the form's choice and when it plays. */
internal fun EditorUiState.withMotivation(): EditorUiState =
    copy(full = full?.copy(motivation = form.motivation, motivationTiming = form.motivationTiming))

/**
 * The form with a chosen message that is not among [ids] (the messages read from storage; null before a successful
 * read) set to None: it was deleted elsewhere and would play nothing, so Save must not store its id again.
 */
internal fun EditorForm.withoutGone(ids: Set<String>?): EditorForm {
    val chosen = motivation as? MotivationChoice.Recording
    return if (ids != null && chosen != null && chosen.id !in ids) copy(motivation = MotivationChoice.None) else this
}

/** The form after Recordings deleted the message [id]: a choice of it is None, as `DeleteRecording` stored for the alarms. */
internal fun EditorForm.afterRecordingDeleted(id: String): EditorForm =
    if (motivation == MotivationChoice.Recording(id)) copy(motivation = MotivationChoice.None) else this
