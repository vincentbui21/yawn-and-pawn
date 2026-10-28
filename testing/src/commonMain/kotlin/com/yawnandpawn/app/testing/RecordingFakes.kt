package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.recording.AudioRecorder
import com.yawnandpawn.app.core.recording.MotivationPlayback
import com.yawnandpawn.app.core.recording.MotivationPlayer
import com.yawnandpawn.app.core.recording.PreviewItem
import com.yawnandpawn.app.core.recording.PreviewPlayback
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.core.recording.Recording
import com.yawnandpawn.app.core.recording.RecordingPreview
import com.yawnandpawn.app.core.recording.RecordingRepository
import com.yawnandpawn.app.core.recording.RecordingSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.time.Duration
import kotlin.time.Instant

/** A stored recording for tests: "Message 1" of 9 s, its file `{id}.m4a`. */
fun aRecording(
    id: String = "recording-1",
    name: String = "Message 1",
    durationMs: Long = 9_000,
    createdAt: Instant = DEFAULT_FAKE_INSTANT,
    missing: Boolean = false,
): Recording = Recording(id, name, durationMs, Recording.fileNameFor(id), createdAt, missing)

/**
 * In-memory [RecordingRepository] (Story 7.1). [files] holds the file names that exist: a saved take adds its file,
 * a delete removes it, and [markMissing] removes one as a restore would, so [Recording.missing] follows them. [takes]
 * holds the take each recording's file came from. Set [failure] to make every call fail.
 */
class FakeRecordingRepository(
    initial: List<Recording> = emptyList(),
) : RecordingRepository {
    private val rows = MutableStateFlow(initial.map { it.copy(missing = false) })
    val files = MutableStateFlow(initial.filterNot { it.missing }.map { it.fileName }.toSet())
    val takes = mutableMapOf<String, RecordedTake>()
    var failure: DomainError? = null

    /** The stored rows as they are, without [Recording.missing]. */
    val stored: List<Recording>
        get() = rows.value

    override fun observeAll(): Flow<List<Recording>> = combine(rows, files) { all, present -> all.map { it.withFile(present) } }

    override suspend fun get(id: String): Outcome<Recording?, DomainError> =
        guarded { rows.value.firstOrNull { it.id == id }?.withFile(files.value) }

    override suspend fun save(
        recording: Recording,
        take: RecordedTake,
    ): Outcome<Unit, DomainError> =
        guarded {
            val row = recording.copy(missing = false)
            rows.value = rows.value.filterNot { it.id == row.id } + row
            files.value += row.fileName
            takes[row.id] = take
        }

    override suspend fun delete(id: String): Outcome<Unit, DomainError> {
        val row = rows.value.firstOrNull { it.id == id }
        val failed = failure
        return when {
            failed != null -> {
                Outcome.Failure(failed)
            }

            row == null -> {
                Outcome.Failure(DomainError.NotFound(id))
            }

            else -> {
                rows.value -= row
                files.value -= row.fileName
                takes -= id
                Outcome.Success(Unit)
            }
        }
    }

    /** The file of [id] is gone (a restored phone, or storage locked before the first unlock). */
    fun markMissing(id: String) {
        rows.value.firstOrNull { it.id == id }?.let { files.value -= it.fileName }
    }

    private fun Recording.withFile(present: Set<String>): Recording = copy(missing = fileName !in present)

    private fun <T> guarded(block: () -> T): Outcome<T, DomainError> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(block())
}

/**
 * Scripted [AudioRecorder] (Story 7.1). [start] answers [startResult] (by default a session for the next take id
 * `take-1`, `take-2`, ...); [stop] answers [stopResult] or, by default, a take of [nextDurationMs] for the take being
 * recorded. [emitAutoStop] ends the take as the recorder would by itself (60 s, background, call, screen off). [level]
 * and [elapsed] are the flows the screen reads; set them through [levels] and [elapsedTime].
 */
class FakeAudioRecorder : AudioRecorder {
    private var nextTake = 1
    private val autoStops = Channel<Outcome<RecordedTake, DomainError>>(Channel.UNLIMITED)

    /** What the next [start] answers instead of a new session. */
    var startResult: Outcome<RecordingSession, DomainError>? = null

    /** What the next [stop] answers instead of the take being recorded. */
    var stopResult: Outcome<RecordedTake, DomainError>? = null

    var nextDurationMs: Long = 9_000

    val levels = MutableStateFlow(0f)
    val elapsedTime = MutableStateFlow(Duration.ZERO)

    /** The take being recorded, or null. */
    var recording: RecordingSession? = null
        private set
    var starts = 0
        private set
    var cancels = 0
        private set
    val discarded = mutableListOf<RecordedTake>()

    override fun start(): Outcome<RecordingSession, DomainError> {
        starts++
        return startResult ?: if (recording != null) {
            Outcome.Failure(DomainError.RecorderFailure("already recording"))
        } else {
            val session = RecordingSession("take-${nextTake++}")
            recording = session
            Outcome.Success(session)
        }
    }

    override suspend fun stop(): Outcome<RecordedTake, DomainError> {
        val session = recording
        recording = null
        return stopResult ?: session?.let { Outcome.Success(RecordedTake(it.takeId, nextDurationMs)) }
            ?: Outcome.Failure(DomainError.RecorderFailure("not recording"))
    }

    override fun cancel() {
        cancels++
        recording = null
    }

    override fun level(): Flow<Float> = levels

    override fun elapsed(): Flow<Duration> = elapsedTime

    override fun autoStopped(): Flow<Outcome<RecordedTake, DomainError>> = autoStops.receiveAsFlow()

    override suspend fun discard(take: RecordedTake) {
        discarded += take
    }

    /**
     * The recorder stops by itself: the take being recorded with [durationMs] (60 s by default), or [result] when
     * given (an error, or a too-short take).
     */
    fun emitAutoStop(
        durationMs: Long = Recording.MAX_DURATION_MS,
        result: Outcome<RecordedTake, DomainError>? = null,
    ) {
        val session = recording
        recording = null
        val outcome =
            result ?: session?.let { Outcome.Success(RecordedTake(it.takeId, durationMs)) }
                ?: Outcome.Failure(DomainError.RecorderFailure("not recording"))
        autoStops.trySend(outcome)
    }
}

/**
 * In-memory [RecordingPreview] (Story 7.2): [play] records the item and reports it playing at 0 ms (a missing saved
 * recording plays nothing, like the adapter); [advance] moves the position, [finish] ends it as the player would.
 */
class FakeRecordingPreview : RecordingPreview {
    private val state = MutableStateFlow<PreviewPlayback?>(null)
    override val playing: StateFlow<PreviewPlayback?> = state.asStateFlow()

    /** Each item [play] was asked for, in order. */
    val played = mutableListOf<PreviewItem>()
    var stops = 0
        private set

    override fun play(item: PreviewItem) {
        played += item
        state.value = if (item is PreviewItem.Saved && item.recording.missing) null else PreviewPlayback(item.key)
    }

    override fun stop() {
        stops++
        state.value = null
    }

    /** The playing preview is [positionMs] in. */
    fun advance(positionMs: Long) {
        state.value = state.value?.copy(positionMs = positionMs)
    }

    /** The preview ended by itself. */
    fun finish() {
        state.value = null
    }
}

/**
 * In-memory [MotivationPlayer] (Story 7.3): [play] records the request and reports the message playing at 0 ms of
 * [durationMs] (or nothing, when [playable] is false, like the adapter for a missing file); [advance] moves it, [finish]
 * plays it to its end, [fail] clears it as an error would. [pause], [resume], [replay] and [stop] are counted.
 */
class FakeMotivationPlayer(
    var durationMs: Long = 9_000,
) : MotivationPlayer {
    private val playback = MutableStateFlow<MotivationPlayback?>(null)
    override val state: StateFlow<MotivationPlayback?> = playback.asStateFlow()

    /** Each `(sessionId, recordingId)` [play] was asked for, in order. */
    val played = mutableListOf<Pair<String, String>>()

    /** False: [play] plays nothing (a missing row or file). */
    var playable = true
    var pauses = 0
        private set
    var resumes = 0
        private set
    var replays = 0
        private set
    var stops = 0
        private set

    override fun play(
        sessionId: String,
        recordingId: String,
    ) {
        played += sessionId to recordingId
        playback.value = if (playable) MotivationPlayback(sessionId, playing = true, durationMs = durationMs) else null
    }

    override fun pause() {
        pauses++
        playback.value = playback.value?.copy(playing = false)
    }

    override fun resume() {
        resumes++
        playback.value =
            playback.value?.let { if (it.positionMs >= it.durationMs) it.copy(playing = true, positionMs = 0) else it.copy(playing = true) }
    }

    override fun replay() {
        replays++
        playback.value = playback.value?.copy(playing = true, positionMs = 0)
    }

    override fun stop() {
        stops++
        playback.value = null
    }

    /** The message is [positionMs] in. */
    fun advance(positionMs: Long) {
        playback.value = playback.value?.copy(positionMs = positionMs)
    }

    /** The message played to its end. */
    fun finish() {
        playback.value = playback.value?.let { it.copy(playing = false, positionMs = it.durationMs) }
    }

    /** The message failed while playing. */
    fun fail() {
        playback.value = null
    }
}
