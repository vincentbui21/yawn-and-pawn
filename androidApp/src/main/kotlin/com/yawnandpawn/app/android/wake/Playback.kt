package com.yawnandpawn.app.android.wake

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.yawnandpawn.app.R
import com.yawnandpawn.app.core.alarm.Alarm

/** The audio attributes of every alarm sound and vibration below API 33: the alarm stream, independent of media and ringer. */
val ALARM_AUDIO_ATTRIBUTES: AudioAttributes =
    AudioAttributes
        .Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

/** A sound the alarm player can open. */
sealed interface AlarmSound {
    /** The bundled default (`res/raw/alarm_default.ogg`): `Alarm.DEFAULT_SOUND_REF` and the never-silent fallback. */
    data object Default : AlarmSound

    /** The phone's own default alarm ringtone: the last resort when even the bundled default fails. */
    data object SystemAlarm : AlarmSound

    /** A sound file chosen by the user or from the sound library (Story 1.17). */
    data class File(
        val uri: String,
    ) : AlarmSound
}

/**
 * Maps an alarm's `soundRef` to the sound to open; `null` when the reference is unknown, and the player then plays
 * [AlarmSound.Default]. The sound library replaces the binding in Story 1.17.
 */
fun interface SoundResolver {
    fun resolve(soundRef: String): AlarmSound?
}

/** Story 1.14: only [Alarm.DEFAULT_SOUND_REF] is known; any other reference resolves to nothing (and the default plays). */
object DefaultOnlySoundResolver : SoundResolver {
    override fun resolve(soundRef: String): AlarmSound? = AlarmSound.Default.takeIf { soundRef == Alarm.DEFAULT_SOUND_REF }
}

/**
 * One opened, looping, prepared sound: the small seam over `MediaPlayer` (Design Notes), so tests can force errors
 * without relying on the media shadows. Calls come from [AndroidAlarmPlayer] only, under its lock.
 */
interface Playback {
    fun start()

    fun pause()

    /** The player gain, 0.0 to 1.0, on top of the alarm stream volume. */
    fun setGain(gain: Float)

    fun release()
}

/** Opens sounds for [AndroidAlarmPlayer]. */
fun interface PlaybackFactory {
    /**
     * Opens and prepares [sound] to loop on the alarm stream. Throws when it cannot be opened or prepared; [onError] runs
     * (on the main thread) if it fails later, while playing.
     */
    fun open(
        sound: AlarmSound,
        onError: () -> Unit,
    ): Playback
}

/** [PlaybackFactory] on `MediaPlayer`: `USAGE_ALARM` / sonification, looping, holding a partial wake lock while it plays. */
class MediaPlayerPlaybackFactory(
    private val context: Context,
) : PlaybackFactory {
    // MediaPlayer throws IOException, IllegalStateException, IllegalArgumentException or SecurityException for a
    // source it cannot open; the player falls back on any of them, so release and rethrow whatever it was.
    @Suppress("TooGenericExceptionCaught")
    override fun open(
        sound: AlarmSound,
        onError: () -> Unit,
    ): Playback {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(ALARM_AUDIO_ATTRIBUTES)
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(context, uriOf(sound))
            player.isLooping = true
            player.setOnErrorListener { _, _, _ ->
                onError()
                true
            }
            player.prepare()
        } catch (e: Exception) {
            player.release()
            throw e
        }
        return MediaPlayerPlayback(player)
    }

    private fun uriOf(sound: AlarmSound): Uri =
        when (sound) {
            AlarmSound.Default -> Uri.parse("android.resource://${context.packageName}/${R.raw.alarm_default}")
            AlarmSound.SystemAlarm -> Settings.System.DEFAULT_ALARM_ALERT_URI
            is AlarmSound.File -> Uri.parse(sound.uri)
        }
}

private class MediaPlayerPlayback(
    private val player: MediaPlayer,
) : Playback {
    /** `pause()` on a prepared player that never started puts it into the Error state, so only a started one pauses. */
    private var started = false

    override fun start() {
        player.start()
        started = true
    }

    override fun pause() {
        if (started) player.pause()
    }

    override fun setGain(gain: Float) = player.setVolume(gain, gain)

    override fun release() = player.release()
}
