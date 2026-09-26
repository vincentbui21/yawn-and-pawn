package com.yawnandpawn.app.core.time

import kotlinx.datetime.TimeZone

/**
 * Wall-clock port (AD-3): the standard `kotlin.time.Clock`. It can jump when the user or the network
 * changes the time, so deadlines within one boot use [MonotonicClock] instead (see [Deadline]).
 * Bound to `kotlin.time.Clock.System` only in `com.yawnandpawn.app.android` (nested objects are not reachable through a
 * typealias, so write it fully qualified); detekt `NoDirectTimeAccess` bans direct use elsewhere.
 */
typealias Clock = kotlin.time.Clock

/** Milliseconds since boot, including deep sleep; never jumps, resets to 0 on reboot. */
fun interface MonotonicClock {
    fun elapsedMillis(): Long
}

/** How many times the phone has booted; a different value means [MonotonicClock] restarted from 0. */
fun interface BootCounter {
    fun bootCount(): Int
}

/** The phone's current time zone; read it each time, it changes when the user travels or changes settings. */
fun interface TimeZoneProvider {
    fun current(): TimeZone
}

/** "Now" as deadline logic needs it: wall time, time since boot and the boot it was read in. */
data class TimeSnapshot(
    val wallMillis: Long,
    val elapsedMillis: Long,
    val bootCount: Int,
) {
    companion object {
        /** Reads all three ports once. */
        fun of(
            clock: Clock,
            monotonicClock: MonotonicClock,
            bootCounter: BootCounter,
        ): TimeSnapshot =
            TimeSnapshot(
                wallMillis = clock.now().toEpochMilliseconds(),
                elapsedMillis = monotonicClock.elapsedMillis(),
                bootCount = bootCounter.bootCount(),
            )
    }
}
