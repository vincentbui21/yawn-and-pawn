package com.yawnandpawn.app.android.recording

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** The recorder's audio focus (Story 7.1): held while a take records; losing it (a call) ends the take. */
interface RecordingFocus {
    /** Asks for focus; false when refused (a call holds it). [onLoss] runs when another app or a call takes it. */
    fun request(onLoss: () -> Unit): Boolean

    /** Gives focus back; nothing when not held. */
    fun abandon()
}

/**
 * [RecordingFocus] as `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` for speech, so music pauses while the user records and
 * resumes after. A permanent or transient loss ends the take (something else wants the audio, usually a call); a
 * "can duck" loss (a notification chime) does not.
 */
class AndroidRecordingFocus(
    private val audio: AudioManager,
    /** `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` for a take; the Recordings preview (Story 7.2) asks `AUDIOFOCUS_GAIN_TRANSIENT`. */
    private val gain: Int = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
    /** Media speech for a take and a preview; the alarm stream's for the Success message (Story 7.3). */
    private val attributes: AudioAttributes = SPEECH,
) : RecordingFocus {
    private var held: AudioFocusRequest? = null

    @Synchronized
    override fun request(onLoss: () -> Unit): Boolean {
        abandon()
        val request =
            AudioFocusRequest
                .Builder(gain)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change -> if (change in ENDING_LOSSES) onLoss() }
                .build()
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) held = request
        return granted
    }

    @Synchronized
    override fun abandon() {
        held?.let(audio::abandonAudioFocusRequest)
        held = null
    }

    private companion object {
        val ENDING_LOSSES = setOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        val SPEECH: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
    }
}
