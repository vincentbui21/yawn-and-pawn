package com.yawnandpawn.app.core.sound

import kotlinx.coroutines.flow.StateFlow

/**
 * The sounds an alarm can play beyond the [SoundCatalog] (Story 1.17): the phone's alarm ringtones, and whether a
 * chosen sound can still be played. The Android adapter reads `RingtoneManager` (`TYPE_ALARM`) off the main thread.
 */
interface SoundLibrary {
    /** The phone's alarm ringtones, in the system's order; empty when they cannot be read (logged by the adapter). */
    suspend fun systemSounds(): List<SoundRef.System>

    /**
     * Whether [ref] can be played now: a catalog sound always, a system ringtone while its file opens, anything else
     * never. A sound that is not available shows "File missing. Default sound will play." and rings as the default.
     */
    suspend fun isAvailable(ref: SoundRef): Boolean
}

/**
 * The Sound picker's preview player (FR-SND-1, Story 1.17): one sound at a time, played once on the alarm stream at
 * the alarm's volume. The Android adapter never plays while an alarm rings.
 */
interface SoundPreview {
    /** The encoded [SoundRef] playing now, or null; it clears by itself when the sound ends or fails. */
    val previewing: StateFlow<String?>

    /** Plays [ref] once at [volumePercent] of the alarm stream, stopping any other preview first. */
    fun play(
        ref: SoundRef,
        volumePercent: Int,
    )

    /** The volume slider moved while a preview plays. */
    fun setVolume(volumePercent: Int)

    /** Stops the preview, if any, and puts the user's alarm volume back. */
    fun stop()
}
