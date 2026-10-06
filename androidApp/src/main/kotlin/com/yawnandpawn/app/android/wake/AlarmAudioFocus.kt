package com.yawnandpawn.app.android.wake

import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * The ring's audio focus (Story 2.7, AD-5): `AUDIOFOCUS_GAIN` with the alarm attributes, never paused for ducking. The
 * alarm plays on the alarm stream whatever the result, and a focus change never lowers, ducks or pauses it: another
 * app's music or video does not affect the alarm. Focus changes are only passed to [onChange], so the call adapter can
 * look at the audio mode (a call takes focus).
 */
class AlarmAudioFocus(
    private val audio: AudioManager,
    private val onChange: (Int) -> Unit = {},
) {
    private val request: AudioFocusRequest =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(ALARM_AUDIO_ATTRIBUTES)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change -> onChange(change) }
            .build()

    @Volatile
    private var held = false

    /** Requests focus for a ring; nothing while held. */
    fun request() {
        if (held) return
        audio.requestAudioFocus(request)
        held = true
    }

    /** Gives focus back when the ring is released; nothing when not held. */
    fun abandon() {
        if (!held) return
        audio.abandonAudioFocusRequest(request)
        held = false
    }
}
