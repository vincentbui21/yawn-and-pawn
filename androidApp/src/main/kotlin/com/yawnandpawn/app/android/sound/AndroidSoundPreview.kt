package com.yawnandpawn.app.android.sound

import android.content.Context
import android.media.MediaPlayer
import com.yawnandpawn.app.android.wake.ALARM_AUDIO_ATTRIBUTES
import com.yawnandpawn.app.android.wake.AlarmVolume
import com.yawnandpawn.app.android.wake.SoundResolver
import com.yawnandpawn.app.android.wake.soundUri
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.sound.SoundRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Sound picker's preview player (FR-SND-1, Story 1.17): one sound at a time, played once (not looping) on the alarm
 * stream (`USAGE_ALARM`) with the stream at the alarm's volume, so the user hears what the alarm will sound like. It is
 * thread-safe (its own lock): the UI calls it on the main thread, the alarm player at a ring start.
 *
 * - **Volume:** the same [AlarmVolume] as the ring saves the user's alarm volume and puts it back when the preview ends.
 *   While an alarm rings ([ringing]) a preview never starts and never restores the volume: the ring owns the stream,
 *   keeps the user volume the preview saved, and restores it when the session ends. A ring that starts stops a playing
 *   preview (`AndroidAlarmPlayer`'s ring-start hook, bound in `wakeModule`). A crash mid-preview is healed at app start
 *   (`WakeRuntime.restoreVolumeIfIdle`). [ringing] must not take the alarm player's lock (that would deadlock with the
 *   ring-start hook).
 * - **Never blocking:** it prepares with `prepareAsync`; a sound that cannot be opened or fails clears [previewing].
 *   Nothing logged names the sound or its URI.
 */
class AndroidSoundPreview(
    private val context: Context,
    private val volume: AlarmVolume,
    private val resolver: SoundResolver,
    private val ringing: () -> Boolean,
    private val logger: Logger,
    private val newPlayer: () -> MediaPlayer = ::MediaPlayer,
) : SoundPreview {
    private val playing = MutableStateFlow<String?>(null)
    override val previewing: StateFlow<String?> = playing.asStateFlow()

    private var player: MediaPlayer? = null

    // MediaPlayer reports a source it cannot open with several exception types; each means "this preview fails".
    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    override fun play(
        ref: SoundRef,
        volumePercent: Int,
    ) {
        release()
        if (ringing()) {
            logger.log(LogEvent.OperationFailed("preview sound", "an alarm is ringing"))
            finish()
            return
        }
        val sound = resolver.resolve(ref.encode())
        if (sound == null) {
            logger.log(LogEvent.OperationFailed("preview sound", "unknown sound reference"))
            finish()
            return
        }
        volume.setForRing(volumePercent)
        val opened = newPlayer()
        player = opened
        playing.value = ref.encode()
        try {
            opened.setAudioAttributes(ALARM_AUDIO_ATTRIBUTES)
            opened.setDataSource(context, soundUri(context, sound))
            opened.setOnPreparedListener { if (player === it) it.start() }
            opened.setOnCompletionListener { if (player === it) finish() }
            opened.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    logger.log(LogEvent.OperationFailed("preview sound", "the sound failed while playing"))
                    finish()
                }
                true
            }
            opened.prepareAsync()
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed("preview sound", "could not open the sound: ${e::class.simpleName}"))
            finish()
        }
    }

    @Synchronized
    override fun setVolume(volumePercent: Int) {
        if (player != null && !ringing()) volume.setForRing(volumePercent)
    }

    @Synchronized
    override fun stop() {
        if (player != null || playing.value != null) finish()
    }

    @Synchronized
    private fun finish() {
        release()
        playing.value = null
        if (!ringing()) volume.restore()
    }

    private fun release() {
        player?.release()
        player = null
    }
}
