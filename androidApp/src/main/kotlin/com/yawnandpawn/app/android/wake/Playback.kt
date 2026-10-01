package com.yawnandpawn.app.android.wake

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RawRes
import com.yawnandpawn.app.R

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

    /** Another bundled sound of the `SoundCatalog` (Story 1.17): the `res/raw` resource [rawRes]. */
    data class BuiltIn(
        @param:RawRes val rawRes: Int,
    ) : AlarmSound

    /** A sound behind a content URI: one of the phone's alarm ringtones (Story 1.17), later the user's files. */
    data class File(
        val uri: String,
    ) : AlarmSound
}

/**
 * Maps an alarm's `soundRef` to the sound to open; `null` when the reference is unknown, and the player then plays
 * [AlarmSound.Default]. Production binds `LibrarySoundResolver` (the sound library, Story 1.17).
 */
fun interface SoundResolver {
    fun resolve(soundRef: String): AlarmSound?
}

/** Where [sound] is read from. */
fun soundUri(
    context: Context,
    sound: AlarmSound,
): Uri =
    when (sound) {
        AlarmSound.Default -> rawUri(context, R.raw.alarm_default)
        AlarmSound.SystemAlarm -> Settings.System.DEFAULT_ALARM_ALERT_URI
        is AlarmSound.BuiltIn -> rawUri(context, sound.rawRes)
        is AlarmSound.File -> Uri.parse(sound.uri)
    }

private fun rawUri(
    context: Context,
    @RawRes rawRes: Int,
): Uri = Uri.parse("android.resource://${context.packageName}/$rawRes")

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
     * Opens [sound] to loop on the alarm stream and starts preparing it. Throws when it cannot be opened (for example a
     * ringtone URI that no longer resolves); [onPrepared] runs once it is prepared, [onError] (on the main thread) if it
     * fails later, while preparing or playing. Either may run before `open` returns. `start` and gains given before it
     * is prepared apply once it is.
     */
    fun open(
        sound: AlarmSound,
        onPrepared: () -> Unit,
        onError: () -> Unit,
    ): Playback
}

/**
 * [PlaybackFactory] on `MediaPlayer`: `USAGE_ALARM` / sonification, looping, holding a partial wake lock while it plays.
 * It prepares with `prepareAsync` (Story 1.17): a content URI can be slow to prepare, and the player's caller holds its
 * lock and may be the main thread. A prepare error arrives through the error listener like a playback error.
 */
class MediaPlayerPlaybackFactory(
    private val context: Context,
) : PlaybackFactory {
    // MediaPlayer throws IOException, IllegalStateException, IllegalArgumentException or SecurityException for a
    // source it cannot open; the player falls back on any of them, so release and rethrow whatever it was.
    @Suppress("TooGenericExceptionCaught")
    override fun open(
        sound: AlarmSound,
        onPrepared: () -> Unit,
        onError: () -> Unit,
    ): Playback {
        val player = MediaPlayer()
        val playback = MediaPlayerPlayback(player)
        try {
            player.setAudioAttributes(ALARM_AUDIO_ATTRIBUTES)
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(context, soundUri(context, sound))
            player.isLooping = true
            // The caller hears of it after the playback's own lock is released (the caller then takes its lock).
            player.setOnPreparedListener { if (playback.onPrepared()) onPrepared() }
            player.setOnErrorListener { _, _, _ ->
                onError()
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            player.release()
            throw e
        }
        return playback
    }
}

/**
 * One `MediaPlayer` that may still be preparing: what the alarm player asked for before it was prepared (start, the
 * gain) is applied when it is. Its own lock: `onPrepared` arrives on the main thread while the alarm player calls from
 * its own lock (never the other way round, so the two cannot deadlock).
 */
private class MediaPlayerPlayback(
    private val player: MediaPlayer,
) : Playback {
    private var prepared = false
    private var wantsToPlay = false
    private var released = false

    /** `pause()` on a prepared player that never started puts it into the Error state, so only a started one pauses. */
    private var started = false
    private var gain: Float? = null

    /** Applies what was asked before it was prepared; false when it was released meanwhile. */
    @Synchronized
    fun onPrepared(): Boolean {
        if (released) return false
        prepared = true
        gain?.let { player.setVolume(it, it) }
        if (wantsToPlay) startNow()
        return true
    }

    @Synchronized
    override fun start() {
        wantsToPlay = true
        if (prepared && !released) startNow()
    }

    @Synchronized
    override fun pause() {
        wantsToPlay = false
        if (started && !released) player.pause()
    }

    @Synchronized
    override fun setGain(gain: Float) {
        this.gain = gain
        if (prepared && !released) player.setVolume(gain, gain)
    }

    @Synchronized
    override fun release() {
        if (released) return
        released = true
        player.release()
    }

    private fun startNow() {
        player.start()
        started = true
    }
}
