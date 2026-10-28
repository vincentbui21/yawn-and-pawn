package com.yawnandpawn.app.core.recording

import kotlinx.coroutines.flow.StateFlow

/**
 * The motivation message of the session [sessionId] on Success (Story 7.3): [playing] or not (paused, or played to its
 * end, then [positionMs] is [durationMs]), [positionMs] into its [durationMs].
 */
data class MotivationPlayback(
    val sessionId: String,
    val playing: Boolean,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * Port for the player of the motivation message "After I'm up" (Story 7.3): the session runtime starts it when a session
 * completes, the Success screen shows and controls it. One message at a time, played once (no loop) on the alarm stream
 * at the user's own alarm volume (never the ring's), with transient audio focus. It never plays while an alarm rings,
 * and a ring that starts stops it. A recording whose row or file is gone (or locked), or that fails to prepare or
 * play, plays nothing and clears [state]. Only error codes are logged, never a name or a path.
 */
interface MotivationPlayer {
    /**
     * The message and the session it belongs to, playing, paused or played to its end (it stays until [stop] or a newer
     * [play]); null before it is ready to play, after [stop], and when it could not play or failed while playing.
     */
    val state: StateFlow<MotivationPlayback?>

    /** Plays the recording [recordingId] once for the session [sessionId], stopping any message before. */
    fun play(
        sessionId: String,
        recordingId: String,
    )

    /** Pauses the message; nothing when none plays. */
    fun pause()

    /** Plays on from where it paused, or from the start when it played to its end. */
    fun resume()

    /** Plays the message again from its start. */
    fun replay()

    /** Stops the message, if any, and clears [state]. */
    fun stop()
}
