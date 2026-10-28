package com.yawnandpawn.app.ui.recordings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.recording.AudioRecorder
import com.yawnandpawn.app.core.recording.DeleteRecording
import com.yawnandpawn.app.core.recording.PreviewItem
import com.yawnandpawn.app.core.recording.PreviewPlayback
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.core.recording.Recording
import com.yawnandpawn.app.core.recording.RecordingPreview
import com.yawnandpawn.app.core.recording.RecordingRepository
import com.yawnandpawn.app.core.recording.SaveRecording
import com.yawnandpawn.app.core.reliability.ReliabilityRecords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** The ports and use cases Recordings works with (Story 7.2); in the app each comes from Koin. */
data class RecordingsSources(
    val recorder: AudioRecorder,
    val recordings: RecordingRepository,
    val saveRecording: SaveRecording,
    val deleteRecording: DeleteRecording,
    val preview: RecordingPreview,
    val microphone: MicrophonePermission,
    val records: ReliabilityRecords,
    val logger: Logger,
)

/**
 * Recordings' state (Story 7.2), a plain state holder over [scope] (the pane's own): the recorder, the take, the saved
 * messages and their previews. Nothing here is part of the editor's form: a saved message is reported through [onSaved]
 * and a deleted one through [onDeleted], and the editor decides what its form does with them.
 *
 * - **Record:** the first tap is remembered for the reliability checklist (its Microphone row), then asks for the
 *   microphone only when it is missing (the only place the app asks). Granted, recording starts; refused (once or for
 *   good), "Microphone is off. Turn it on in Settings." shows with "Fix" and the record button is disabled, until the
 *   permission is read again as granted when the screen comes back ([onResumed]). A recorder that cannot start shows
 *   "Couldn't start recording. Try again."; "Re-record" keeps the old take until the new one has started.
 * - **Stop:** a tap, or the recorder by itself (60 s, the app in the background, a call, the screen off), offers the
 *   take: play, "Re-record", "Delete", "Save". A take under 1 s shows "Too short. Try again." for 4 s; a take that cannot
 *   be stored "Couldn't save. Try again.".
 * - **Previews:** one at a time ([RecordingPreview]); one stops when another starts, when the take changes, when the
 *   screen stops ([onStopped]) and when it is left ([leave]); none plays while recording or saving. A message whose file
 *   is missing cannot play.
 * - **Delete:** asks first; "Delete" removes the message and its file and clears it from the alarms (`DeleteRecording`);
 *   a failure shows "Couldn't delete this message. Try again.".
 * - **Leaving:** a recording is cancelled (also one whose start was still on its way), an unsaved take is deleted. A save
 *   or a delete already running finishes, and still reaches the editor, after the pane is gone.
 */
@Suppress("TooManyFunctions") // One small handler per Recordings action.
class RecordingsModel(
    private val scope: CoroutineScope,
    private val sources: RecordingsSources,
    private val onSaved: (String) -> Unit,
    private val onDeleted: (String) -> Unit,
    private val onBack: () -> Unit,
) {
    private val mutableState = MutableStateFlow(RecordingsUiState())
    val state: StateFlow<RecordingsUiState> = mutableState.asStateFlow()

    private var messages: List<Recording> = emptyList()
    private var playback: PreviewPlayback? = null
    private var take: RecordedTake? = null
    private var recording = false
    private var recordedSeconds = 0
    private var levels: List<Float> = emptyList()
    private var micDenied = false
    private var snackbar: RecordingsSnackbar? = null
    private var deleteDialogFor: String? = null

    /** A start, stop, save or delete is running: another tap waits for it (no double take, no double save). */
    private var busy = false

    /** The take being stored now: never discarded by [leave]. */
    private var saving: RecordedTake? = null

    /** The pane was left: a start still on its way is cancelled, and a take arriving now is deleted. */
    private var left = false
    private var tapRemembered = false
    private var ticks: Job? = null
    private var snackbarJob: Job? = null
    private var started = false

    /** Starts following the saved messages, the preview and the recorder's own stops; once. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            sources.recordings
                .observeAll()
                .catch { cause ->
                    // The exception class only: never a name or path in a log.
                    log(LOAD, DomainError.StorageFailure("${cause::class.simpleName}"))
                    emit(emptyList())
                }.collect {
                    messages = it
                    render()
                }
        }
        scope.launch {
            sources.preview.playing.collect {
                playback = it
                render()
            }
        }
        scope.launch { sources.recorder.autoStopped().collect(::onAutoStopped) }
    }

    /** The screen came back (from the system settings after "Fix", say): a granted microphone clears the banner. */
    fun onResumed() {
        if (micDenied && sources.microphone.isGranted()) {
            micDenied = false
            render()
        }
    }

    /** The app went to the background or another screen covered it: a preview stops (the recorder stops by itself). */
    fun onStopped() = stopPreview()

    /**
     * Recordings was left (Back, or the session lock took the screen): the preview stops, a take being recorded is
     * cancelled and a take that was not saved is deleted (one being saved is kept: its save finishes).
     */
    fun leave() {
        left = true
        stopPreview()
        ticks?.cancel()
        if (recording) sources.recorder.cancel()
        recording = false
        val unsaved = take?.takeIf { it != saving }
        take = null
        unsaved?.let(::discard)
    }

    fun onIntent(intent: RecordingsIntent) {
        when (intent) {
            RecordingsIntent.Back -> {
                // Nothing records or plays while the pane slides out.
                leave()
                onBack()
            }

            RecordingsIntent.RecordClicked, RecordingsIntent.ReRecordClicked -> {
                record()
            }

            RecordingsIntent.StopClicked -> {
                stop()
            }

            RecordingsIntent.TakePlayToggled -> {
                take?.takeIf { previewAllowed() }?.let { toggle(PreviewItem.Take(it)) }
            }

            RecordingsIntent.SaveTakeClicked -> {
                saveTake()
            }

            RecordingsIntent.DeleteTakeClicked -> {
                deleteTake()
            }

            is RecordingsIntent.PlayToggled -> {
                playMessage(intent.id)
            }

            is RecordingsIntent.DeleteClicked -> {
                showDeleteDialog(intent.id)
            }

            RecordingsIntent.DeleteConfirmed -> {
                deleteMessage()
            }

            RecordingsIntent.DeleteCancelled -> {
                showDeleteDialog(null)
            }

            RecordingsIntent.FixMicrophone -> {
                sources.microphone.openSettings()
            }
        }
    }

    /** Nothing else runs, the microphone is not refused and the pane is still shown. */
    private fun canRecord(): Boolean = !busy && !recording && !micDenied && !left

    /** Record (or "Re-record", whose old take is deleted once the new one has started): the microphone first. */
    private fun record() {
        if (!canRecord()) return
        busy = true
        scope.launch {
            try {
                rememberTap()
                if (!microphoneAllowed()) {
                    micDenied = true
                } else if (!left) {
                    stopPreview()
                    startTake()
                }
            } finally {
                busy = false
                render()
            }
        }
    }

    private suspend fun startTake() {
        when (val started = sources.recorder.start()) {
            is Outcome.Success -> {
                if (left) {
                    // Left while the start was on its way: nothing may keep recording.
                    sources.recorder.cancel()
                    return
                }
                take?.let(::discard)
                take = null
                recording = true
                recordedSeconds = 0
                levels = emptyList()
                followTicks()
            }

            // The recorder logged its error code; refused for want of the permission, the banner says so.
            is Outcome.Failure -> {
                micDenied = started.error == DomainError.MicrophoneUnavailable && !sources.microphone.isGranted()
                if (!micDenied) show(RecordingsSnackbar.StartFailed)
            }
        }
    }

    /** The first record tap of this screen is remembered: the reliability checklist then shows its Microphone row. */
    private suspend fun rememberTap() {
        if (tapRemembered) return
        tapRemembered = true
        val written = sources.records.recordTapped()
        if (written is Outcome.Failure) log(REMEMBER_TAP, written.error)
    }

    /** Granted already, or granted in the dialog asked now. */
    private suspend fun microphoneAllowed(): Boolean =
        sources.microphone.isGranted() || sources.microphone.request() == MicrophoneAnswer.Granted

    /** The elapsed time and the level meter while recording. */
    private fun followTicks() {
        ticks?.cancel()
        ticks =
            scope.launch {
                launch {
                    sources.recorder.elapsed().collect {
                        recordedSeconds = it.inWholeSeconds.toInt()
                        render()
                    }
                }
                launch {
                    sources.recorder.level().collect {
                        levels = (levels + it).takeLast(LEVEL_BARS)
                        render()
                    }
                }
            }
    }

    private fun stop() {
        if (busy || !recording) return
        busy = true
        scope.launch {
            try {
                endTake(sources.recorder.stop())
            } finally {
                busy = false
                render()
            }
        }
    }

    /**
     * The recorder stopped by itself (60 s, background, a call, screen off). A take arriving when one is already offered
     * (it cannot be shown) or after the pane was left is deleted; one arriving just after a stop tap that found nothing to
     * stop is offered.
     */
    private fun onAutoStopped(result: Outcome<RecordedTake, DomainError>) {
        if (take != null || left) {
            (result as? Outcome.Success)?.value?.let(::discard)
            return
        }
        endTake(result)
        render()
    }

    private fun endTake(result: Outcome<RecordedTake, DomainError>) {
        recording = false
        ticks?.cancel()
        levels = emptyList()
        when (result) {
            is Outcome.Success -> {
                if (left) discard(result.value) else take = result.value
            }

            is Outcome.Failure -> {
                if (result.error == DomainError.RecordingTooShort) show(RecordingsSnackbar.TooShort)
            }
        }
    }

    /** "Save": runs to its end even when the pane is left meanwhile, and the editor still hears of the new message. */
    private fun saveTake() {
        val current = take ?: return
        if (busy) return
        busy = true
        saving = current
        stopPreview()
        scope.launch(NonCancellable) {
            try {
                val saved = sources.saveRecording(current)
                // Stored, or refused and deleted by SaveRecording: either way the take is gone.
                if (take == current) take = null
                when (saved) {
                    is Outcome.Success -> {
                        onSaved(saved.value.id)
                    }

                    is Outcome.Failure -> {
                        val tooShort = saved.error == DomainError.RecordingTooShort
                        show(if (tooShort) RecordingsSnackbar.TooShort else RecordingsSnackbar.SaveFailed)
                    }
                }
            } finally {
                saving = null
                busy = false
                render()
            }
        }
    }

    private fun deleteTake() {
        val current = take ?: return
        if (busy) return
        stopPreview()
        take = null
        render()
        discard(current)
    }

    /** Deletes [unsaved]'s file, to its end even when the pane is left meanwhile. */
    private fun discard(unsaved: RecordedTake) {
        scope.launch(NonCancellable) { sources.recorder.discard(unsaved) }
    }

    private fun showDeleteDialog(id: String?) {
        deleteDialogFor = id
        render()
    }

    /** "Delete" in the dialog: the message, its file and its use by alarms; the editor hears of it, also after leaving. */
    private fun deleteMessage() {
        val id = deleteDialogFor ?: return
        deleteDialogFor = null
        if (playback?.key == id) stopPreview()
        render()
        scope.launch(NonCancellable) {
            // A failure (a wake session started, storage) is logged by DeleteRecording; the message stays listed.
            if (sources.deleteRecording(id) is Outcome.Success) onDeleted(id) else show(RecordingsSnackbar.DeleteFailed)
        }
    }

    /** No preview while recording (the speaker would be recorded) or while a take is being saved. */
    private fun previewAllowed(): Boolean = !recording && saving == null

    /** A saved message's play / pause; a message whose file is missing never plays. */
    private fun playMessage(id: String) {
        if (!previewAllowed()) return
        messages.firstOrNull { it.id == id && !it.missing }?.let { toggle(PreviewItem.Saved(it)) }
    }

    private fun toggle(item: PreviewItem) {
        if (playback?.key == item.key) sources.preview.stop() else sources.preview.play(item)
    }

    private fun stopPreview() {
        if (playback != null || sources.preview.playing.value != null) sources.preview.stop()
    }

    /** Shows [message] for 4 s (`snackbar`); a newer one replaces it. */
    private fun show(message: RecordingsSnackbar) {
        snackbar = message
        render()
        snackbarJob?.cancel()
        snackbarJob =
            scope.launch {
                delay(SNACKBAR_MILLIS)
                snackbar = null
                render()
            }
    }

    private fun log(
        operation: String,
        error: DomainError,
    ) = sources.logger.log(LogEvent.OperationFailed.of(operation, error))

    private fun render() {
        val playing = playback
        val current = take
        mutableState.value =
            RecordingsUiState(
                messages =
                    messages.map {
                        val plays = playing?.key == it.id
                        RecordedMessage(
                            id = it.id,
                            name = it.name,
                            seconds = secondsOf(it.durationMs),
                            missing = it.missing,
                            playing = plays,
                            playedSeconds = if (plays) ((playing?.positionMs ?: 0L) / MILLIS_PER_SECOND).toInt() else 0,
                        )
                    },
                recorder =
                    when {
                        recording -> RecorderState.Recording(recordedSeconds, levels)
                        current != null -> RecorderState.Recorded(secondsOf(current.durationMs), playing = playing?.key == current.takeId)
                        else -> RecorderState.Idle
                    },
                micDenied = micDenied,
                snackbar = snackbar,
                deleteDialogFor = deleteDialogFor,
            )
    }

    companion object {
        /** How long a snackbar ("Too short. Try again.", a failure) shows. */
        const val SNACKBAR_MILLIS = 4_000L

        /** The log operation of a first record tap that could not be remembered. */
        const val REMEMBER_TAP = "remember record tap"

        /** The log operation of saved messages that could not be read. */
        const val LOAD = "load recordings"

        private const val MILLIS_PER_SECOND = 1_000L

        /** [ms] in whole seconds, rounded to the nearest (a 9.4 s take reads "0:09"), never 0 for a kept take. */
        private fun secondsOf(ms: Long): Int = ((ms + MILLIS_PER_SECOND / 2) / MILLIS_PER_SECOND).toInt().coerceAtLeast(1)
    }
}

/**
 * Recordings as an editor pane (Story 7.2, IA: the Motivation sub-screen's "Record a message"): [RecordingsScreen] over a
 * [RecordingsModel] that lives while the pane shows. [resumed] changes each time the editor comes back to the front, so
 * the microphone permission is read again after "Fix". A message saved goes to [onSaved], a deleted one to [onDeleted];
 * Back goes to [onBack] (the editor returns to the Motivation sub-screen).
 */
@Composable
fun RecordingsRoute(
    onSaved: (String) -> Unit,
    onDeleted: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    resumed: Int = 0,
    sources: RecordingsSources = rememberRecordingsSources(),
) {
    val saved by rememberUpdatedState(onSaved)
    val deleted by rememberUpdatedState(onDeleted)
    val back by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    val model =
        remember(sources, scope) {
            RecordingsModel(scope, sources, onSaved = { saved(it) }, onDeleted = { deleted(it) }, onBack = { back() })
        }
    DisposableEffect(model) {
        model.start()
        onDispose { model.leave() }
    }
    LaunchedEffect(model, resumed) { model.onResumed() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.onStopped() }
    val state by model.state.collectAsStateWithLifecycle()
    RecordingsScreen(state = state, onIntent = model::onIntent, modifier = modifier)
}

/** The app's [RecordingsSources], from Koin. */
@Composable
private fun rememberRecordingsSources(): RecordingsSources {
    val recorder = koinInject<AudioRecorder>()
    val recordings = koinInject<RecordingRepository>()
    val save = koinInject<SaveRecording>()
    val delete = koinInject<DeleteRecording>()
    val preview = koinInject<RecordingPreview>()
    val microphone = koinInject<MicrophonePermission>()
    val records = koinInject<ReliabilityRecords>()
    val logger = koinInject<Logger>()
    return remember(recorder, recordings, save, delete, preview, microphone, records, logger) {
        RecordingsSources(recorder, recordings, save, delete, preview, microphone, records, logger)
    }
}
