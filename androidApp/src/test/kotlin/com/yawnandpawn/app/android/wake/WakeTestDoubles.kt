package com.yawnandpawn.app.android.wake

import java.io.IOException

/** A [Playback] that records what the player did with it. */
class FakePlayback(
    val sound: AlarmSound,
    private val onError: () -> Unit,
) : Playback {
    var started = 0
        private set
    var playing = false
        private set
    var released = false
        private set
    val gains = mutableListOf<Float>()

    override fun start() {
        started++
        playing = true
    }

    override fun pause() {
        playing = false
    }

    override fun setGain(gain: Float) {
        gains += gain
    }

    override fun release() {
        playing = false
        released = true
    }

    /** The sound fails while ringing, as `MediaPlayer.OnErrorListener` would report it. */
    fun failWhilePlaying() = onError()
}

/**
 * A [PlaybackFactory] whose opens fail (`IOException`) for the sounds in [failing] and throw an unexpected `Error` for
 * those in [crashing]; every playback it opened is kept in [opened]. Opens are prepared at once, except for the sounds
 * in [stalling], which never report prepared (a stalled content provider).
 */
class FakePlaybackFactory : PlaybackFactory {
    val failing = mutableSetOf<AlarmSound>()
    val crashing = mutableSetOf<AlarmSound>()
    val stalling = mutableSetOf<AlarmSound>()
    val opened = mutableListOf<FakePlayback>()

    /** The playback opened last. */
    val current: FakePlayback?
        get() = opened.lastOrNull()

    override fun open(
        sound: AlarmSound,
        onPrepared: () -> Unit,
        onError: () -> Unit,
    ): Playback {
        if (sound in crashing) throw NotImplementedError("decoder missing for $sound")
        if (sound in failing) throw IOException("cannot open $sound")
        return FakePlayback(sound, onError).also {
            opened += it
            if (sound !in stalling) onPrepared()
        }
    }
}

/**
 * A [RecordingPlayback] that records what the player did with it (Story 7.5). With [throwOnStart] / [throwOnPause] those
 * calls throw, as a `MediaPlayer` already in its Error state does before its error report arrives.
 */
class FakeRecordingPlayback(
    val file: java.io.File,
    override val durationMs: Long,
    private val onPrepared: () -> Unit,
    private val onCompleted: () -> Unit,
    private val onError: () -> Unit,
) : RecordingPlayback {
    var throwOnStart = false
    var throwOnPause = false

    var started = 0
        private set
    var playing = false
        private set
    var released = false
        private set
    val gains = mutableListOf<Float>()

    override fun start() {
        if (throwOnStart) throw IllegalStateException("start in the Error state")
        started++
        playing = true
    }

    override fun pause() {
        if (throwOnPause) throw IllegalStateException("pause in the Error state")
        playing = false
    }

    override fun setGain(gain: Float) {
        gains += gain
    }

    override fun release() {
        playing = false
        released = true
    }

    /** It reports prepared now, after `open` returned as `MediaPlayer` does ([FakeRecordingPlaybackFactory.deferPrepare]). */
    fun prepare() = onPrepared()

    /** It played to its end, as `MediaPlayer.OnCompletionListener` would report it. */
    fun complete() {
        playing = false
        onCompleted()
    }

    /** It fails while playing, as `MediaPlayer.OnErrorListener` would report it. */
    fun fail() = onError()
}

/**
 * A [RecordingPlaybackFactory] whose recordings last [durationMs]; with [failing] the open throws, with [stalling] it
 * never reports prepared, with [failOnPrepare] it reports an error instead. Every recording it opened is in [opened].
 */
class FakeRecordingPlaybackFactory(
    var durationMs: Long = 8_000,
) : RecordingPlaybackFactory {
    var failing = false
    var stalling = false
    var failOnPrepare = false

    /** Prepared only when the test calls [FakeRecordingPlayback.prepare], after `open` returned. */
    var deferPrepare = false
    val opened = mutableListOf<FakeRecordingPlayback>()

    val current: FakeRecordingPlayback?
        get() = opened.lastOrNull()

    override fun open(
        file: java.io.File,
        onPrepared: () -> Unit,
        onCompleted: () -> Unit,
        onError: () -> Unit,
    ): RecordingPlayback {
        if (failing) throw IOException("cannot open the recording")
        return FakeRecordingPlayback(file, durationMs, onPrepared, onCompleted, onError).also {
            opened += it
            when {
                failOnPrepare -> onError()
                !stalling && !deferPrepare -> onPrepared()
            }
        }
    }
}
