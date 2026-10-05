package com.yawnandpawn.app.debug

import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import org.koin.core.context.GlobalContext

/**
 * Debug builds only (Story 2.11): what the wake runtime is doing, for tests that check the phone stays usable while
 * the alarm rings. Lives in the debug source set under `com.yawnandpawn.app.debug`, so `checkReleaseContent` keeps it
 * out of release builds, like the fire-now hook.
 */
object WakeStatus {
    /**
     * The alarm is audible: a ring is on, its sound is open and prepared (so started), not muted (grace) and not paused
     * (a call). False without a Koin graph.
     */
    fun isPlaying(): Boolean {
        val player = GlobalContext.getOrNull()?.getOrNull<AndroidAlarmPlayer>() ?: return false
        return player.isRinging && player.sound != null && player.isPrepared && !player.isMuted && !player.isPaused
    }
}
