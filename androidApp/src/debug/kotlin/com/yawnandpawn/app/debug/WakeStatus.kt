package com.yawnandpawn.app.debug

import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import org.koin.core.context.GlobalContext

/**
 * Debug builds only (Story 2.11): what the wake runtime is doing, for the managed-device test that checks the phone
 * stays usable while the alarm rings. Lives in the debug source set under `com.yawnandpawn.app.debug`, so
 * `checkReleaseContent` keeps it out of release builds, like the fire-now hook.
 */
object WakeStatus {
    /** The alarm sound is open and audible: not muted (grace) and not paused (a call). */
    fun isPlaying(): Boolean {
        val player = GlobalContext.getOrNull()?.getOrNull<AndroidAlarmPlayer>() ?: return false
        return player.sound != null && !player.isMuted && !player.isPaused
    }
}
