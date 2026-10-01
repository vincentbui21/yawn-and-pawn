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
