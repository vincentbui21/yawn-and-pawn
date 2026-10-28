package com.yawnandpawn.app.android.recording

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.yawnandpawn.app.android.wake.ALARM_AUDIO_ATTRIBUTES
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.recording.MotivationPlayback
import com.yawnandpawn.app.core.recording.MotivationPlayer
import com.yawnandpawn.app.core.recording.Recording
import com.yawnandpawn.app.core.recording.RecordingRepository
import com.yawnandpawn.app.data.recording.RecordingFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * The motivation message "After I'm up" (Story 7.3), started by the wake runtime when a session completes and shown on
 * Success. It plays the recording once (no loop) on the alarm stream (`USAGE_ALARM`), so it is heard whatever the media
 * volume, at the user's own alarm volume: before each start it puts the user's alarm volume back ([restoreVolume],
 * `AlarmVolume.restore`, idempotent), since the ring's volume is restored only after the session's effects ran. It is
 * thread-safe (its own lock): the runtime calls it from the engine's step, the UI from the main thread, the alarm
 * player's ring-start hook and the focus listener from theirs, and every player callback checks it still belongs to the
 * message playing now.
 *
 * - **Never during a ring:** while an alarm rings ([ringing]) it never starts, resumes or replays, and a ring that starts
 *   stops it (`AndroidAlarmPlayer`'s ring-start hook, bound in `wakeModule`). [ringing] must not take the alarm player's
 *   lock.
 * - **Audio focus:** transient ([focus]) while it plays, so music pauses and resumes after; refused focus (a call) plays
 *   nothing, and a lost focus pauses it.
 * - **Files:** the row is read from [repository] on [scope] (never blocking the caller), then `recordings/{id}.m4a` is
 *   opened ([RecordingFiles]). A missing row, a missing or unreadable (locked) file, a failed prepare or an error while
 *   playing plays nothing and clears [state]. Only error codes are logged, never a name or a path.
 * - **State:** [state] appears once the message starts; after it played to its end it stays (position at the end), so
 *   the Success card can play it again; it reports the position about four times a second ([handler]).
 */
@Suppress("TooManyFunctions") // One small function per control and per player callback.
class AndroidMotivationPlayer(
    private val repository: RecordingRepository,
    private val files: RecordingFiles,
    private val ringing: () -> Boolean,
    private val restoreVolume: () -> Unit,
    private val logger: Logger,
    private val focus: RecordingFocus,
    private val scope: CoroutineScope,
    private val newPlayer: () -> MediaPlayer = ::MediaPlayer,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : MotivationPlayer {
    private val playback = MutableStateFlow<MotivationPlayback?>(null)
    override val state: StateFlow<MotivationPlayback?> = playback.asStateFlow()

    private var player: MediaPlayer? = null

    /** The session of the message being opened or played; null when none. */
    private var sessionId: String? = null

    /** Each [play] and [stop] moves it on, so a row read for an older request opens nothing. */
    private var request = 0L

    /** The focus taken for the message was lost before it started (a call while it prepared): it must not start. */
    private var focusLost = false

    /** The length of the message from its row, for a player that does not know its own length. */
    private var rowDurationMs = 0L

    /** Reads the playing message's position while it plays. */
    private val tick =
        object : Runnable {
            override fun run() = progress(this)
        }

    @Synchronized
    override fun play(
        sessionId: String,
        recordingId: String,
    ) {
        finish()
        val current = ++request
        if (ringing()) {
            fail("an alarm is ringing")
            return
        }
        this.sessionId = sessionId
        scope.launch {
            val row = read(recordingId)
            handler.post { open(current, row) }
        }
    }

    @Synchronized
    override fun pause() {
        val current = player
        val shown = playback.value?.takeIf { it.playing }
        var position = 0L
        val paused =
            current != null &&
                shown != null &&
                onPlayer("pause") {
                    current.pause()
                    position = current.currentPosition.toLong()
                }
        if (paused && shown != null) {
            playback.value = shown.copy(playing = false, positionMs = position.coerceAtMost(shown.durationMs))
            handler.removeCallbacks(tick)
            focus.abandon()
        }
    }

    @Synchronized
    override fun resume() {
        val shown = playback.value?.takeIf { !it.playing } ?: return
        start(fromStart = shown.positionMs >= shown.durationMs)
    }

    @Synchronized
    override fun replay() {
        if (playback.value == null) return
        start(fromStart = true)
    }

    @Synchronized
    override fun stop() {
        request++
        finish()
    }

    /** Plays the paused or ended message on, [fromStart] or where it is. Nothing while a ring plays or without focus. */
    private fun start(fromStart: Boolean) {
        val current = player
        val shown = playback.value
        if (current == null || shown == null || ringing()) return
        if ((shown.playing || requestFocus()) && onPlayer("play") { playFrom(current, fromStart) }) {
            playback.value = shown.copy(playing = true, positionMs = if (fromStart) 0 else shown.positionMs)
            ticking()
        }
    }

    /** Asks for transient focus; until it is lost again, [focusLost] is false. */
    private fun requestFocus(): Boolean = focus.request(onLoss = ::focusLoss).also { if (it) focusLost = false }

    /** Focus lost (a call, another player): a playing message pauses; one still preparing never starts ([prepared]). */
    @Synchronized
    private fun focusLoss() {
        if (playback.value?.playing == true) pause() else focusLost = true
    }

    private fun playFrom(
        current: MediaPlayer,
        fromStart: Boolean,
    ) {
        if (fromStart) current.seekTo(0)
        restoreVolume()
        if (!current.isPlaying) current.start()
    }

    /** Runs [call] on the player; a player in the wrong state fails the message (logged by its class) and gives false. */
    private inline fun onPlayer(
        what: String,
        call: () -> Unit,
    ): Boolean =
        try {
            call()
            true
        } catch (e: IllegalStateException) {
            fail("could not $what: ${e::class.simpleName}")
            false
        }

    private fun ticking() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MILLIS)
    }

    /** The row of [recordingId], or null when there is none or it cannot be read (logged by its error only). */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun read(recordingId: String): Recording? =
        try {
            when (val read = repository.get(recordingId)) {
                is Outcome.Success -> {
                    read.value
                }

                is Outcome.Failure -> {
                    logger.log(LogEvent.OperationFailed.of(OPERATION, read.error))
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName.orEmpty()))
            null
        }

    @Synchronized
    private fun open(
        current: Long,
        row: Recording?,
    ) {
        if (current != request) return
        val file = row?.takeUnless { it.missing }?.let(::fileOf)
        rowDurationMs = row?.durationMs ?: 0L
        when {
            ringing() -> fail("an alarm is ringing")
            file == null -> fail("the message is missing")
            !requestFocus() -> fail("no audio focus")
            else -> prepare(file)
        }
    }

    // MediaPlayer reports a source it cannot open with several exception types; each means "this message fails".
    @Suppress("TooGenericExceptionCaught")
    private fun prepare(file: File) {
        val opened = newPlayer()
        player = opened
        try {
            opened.setAudioAttributes(ALARM_AUDIO_ATTRIBUTES)
            opened.setDataSource(file.absolutePath)
            opened.isLooping = false
            opened.setOnPreparedListener(::prepared)
            opened.setOnCompletionListener(::completed)
            opened.setOnErrorListener { broken, what, extra ->
                failed(broken, what, extra)
                true
            }
            opened.prepareAsync()
        } catch (e: Exception) {
            fail("could not open the message: ${e::class.simpleName}")
        }
    }

    /**
     * [ready] is prepared: it starts, unless a newer message replaced it meanwhile, an alarm rings now, or the focus was
     * lost while it prepared. Its length is the player's, or the row's when the player does not know it.
     */
    @Synchronized
    private fun prepared(ready: MediaPlayer) {
        val session = sessionId
        when {
            player !== ready || session == null -> {
                Unit
            }

            ringing() -> {
                fail("an alarm is ringing")
            }

            focusLost -> {
                fail("audio focus lost before it started")
            }

            onPlayer("play") { playFrom(ready, fromStart = false) } -> {
                val length = ready.duration.toLong().takeIf { it > 0 } ?: rowDurationMs.coerceAtLeast(0)
                playback.value = MotivationPlayback(session, playing = true, durationMs = length)
                ticking()
            }
        }
    }

    /** [ended] played to its end: the card stays, at the end, unless a newer message replaced it meanwhile. */
    @Synchronized
    private fun completed(ended: MediaPlayer) {
        if (player !== ended) return
        handler.removeCallbacks(tick)
        focus.abandon()
        playback.value = playback.value?.let { it.copy(playing = false, positionMs = it.durationMs) }
    }

    /** [broken] failed while preparing or playing: it ends, unless a newer message replaced it meanwhile. */
    @Synchronized
    private fun failed(
        broken: MediaPlayer,
        what: Int,
        extra: Int,
    ) {
        if (player === broken) fail("the message failed while playing: $what/$extra")
    }

    @Suppress("SwallowedException")
    @Synchronized
    private fun progress(runnable: Runnable) {
        val shown = playback.value?.takeIf { it.playing }
        val position =
            try {
                player?.currentPosition?.toLong()
            } catch (e: IllegalStateException) {
                // Released or not started any more: the completion or error listener ends it.
                null
            }
        if (shown != null && position != null) {
            playback.value = shown.copy(positionMs = position.coerceAtMost(shown.durationMs))
            handler.postDelayed(runnable, TICK_MILLIS)
        }
    }

    /** The file of [recording], or null when it is not there or cannot be read (storage locked before the first unlock). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun fileOf(recording: Recording): File? =
        try {
            files.file(recording.fileName)?.takeIf { it.isFile && it.canRead() }
        } catch (e: Exception) {
            null
        }

    private fun fail(reason: String) {
        logger.log(LogEvent.OperationFailed(OPERATION, reason))
        finish()
    }

    private fun finish() {
        handler.removeCallbacks(tick)
        player?.release()
        player = null
        sessionId = null
        focusLost = false
        rowDurationMs = 0L
        focus.abandon()
        playback.value = null
    }

    companion object {
        const val OPERATION = "play motivation"
        private const val TICK_MILLIS = 250L
    }
}
