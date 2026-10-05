package com.yawnandpawn.app.core.time

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A point in time that survives wall-clock jumps and reboots (AD-3). Within the boot it was created in it compares
 * monotonic time only, so a wall-clock change never moves it; after a reboot the monotonic clock restarted, so it
 * falls back to wall time. Serializable so the session state can be persisted (AD-2).
 *
 * "The same boot" ([sameBoot], Story 2.2) means the boot count matches and the elapsed clock has not gone back below
 * [createdElapsedMillis], the elapsed time the deadline was made at: a lower elapsed time always means a reboot, also
 * on a device whose `BOOT_COUNT` is missing (its boot counter then reports the same value every boot). A deadline
 * stored before Story 2.2 has no [createdElapsedMillis] (0), so for it only the boot count decides, as before.
 */
@Serializable
data class Deadline(
    val wallMillis: Long,
    val elapsedMillis: Long,
    val bootCount: Int,
    val createdElapsedMillis: Long = 0,
) {
    /** True once [remaining] is zero. */
    fun isDue(now: TimeSnapshot): Boolean = remaining(now) == Duration.ZERO

    /** [now] is in the boot this deadline was made in, so its monotonic time counts (see the class comment). */
    fun sameBoot(now: TimeSnapshot): Boolean = now.bootCount == bootCount && now.elapsedMillis >= createdElapsedMillis

    /** Time left until the deadline; never negative. */
    fun remaining(now: TimeSnapshot): Duration =
        if (sameBoot(now)) {
            millisUntil(elapsedMillis, now.elapsedMillis)
        } else {
            millisUntil(wallMillis, now.wallMillis)
        }

    /**
     * This deadline moved [duration] later on both clocks, used to leave paused time out (AD-2: time spent in a call
     * does not count). [duration] must be finite and not negative. Saturates at `Long.MAX_VALUE`.
     */
    fun shiftedBy(duration: Duration): Deadline {
        require(!duration.isNegative() && duration.isFinite()) { "a deadline only moves later by a finite step, was $duration" }
        val millis = duration.inWholeMilliseconds
        return copy(wallMillis = wallMillis.saturatingPlus(millis), elapsedMillis = elapsedMillis.saturatingPlus(millis))
    }

    companion object {
        /** The deadline [duration] after [now]; [duration] must be finite and not negative. Saturates at `Long.MAX_VALUE`. */
        fun after(
            now: TimeSnapshot,
            duration: Duration,
        ): Deadline {
            require(!duration.isNegative() && duration.isFinite()) { "a deadline needs a finite, non-negative duration, was $duration" }
            val millis = duration.inWholeMilliseconds
            return Deadline(
                wallMillis = now.wallMillis.saturatingPlus(millis),
                elapsedMillis = now.elapsedMillis.saturatingPlus(millis),
                bootCount = now.bootCount,
                createdElapsedMillis = now.elapsedMillis,
            )
        }

        /**
         * [from] and [to] were read in the same boot: the boot count matches and elapsed time did not go back (a lower
         * elapsed time always means a reboot, Story 2.2).
         */
        fun sameBoot(
            from: TimeSnapshot,
            to: TimeSnapshot,
        ): Boolean = from.bootCount == to.bootCount && to.elapsedMillis >= from.elapsedMillis

        /** Compares first, so the subtraction only runs when [target] is ahead and cannot go negative. */
        private fun millisUntil(
            target: Long,
            now: Long,
        ): Duration {
            if (target <= now) return Duration.ZERO
            val left = target - now
            // Only overflows when target and now are more than Long.MAX_VALUE apart (now far negative).
            return if (left < 0) Long.MAX_VALUE.milliseconds else left.milliseconds
        }

        private fun Long.saturatingPlus(other: Long): Long = if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
    }
}
