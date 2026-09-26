package com.yawnandpawn.app.core.time

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A point in time that survives wall-clock jumps and reboots (AD-3). Within the boot it was created in
 * ([bootCount]) it compares monotonic time only, so a wall-clock change never moves it; after a reboot the
 * monotonic clock restarted, so it falls back to wall time.
 */
data class Deadline(
    val wallMillis: Long,
    val elapsedMillis: Long,
    val bootCount: Int,
) {
    /** True once [remaining] is zero. */
    fun isDue(now: TimeSnapshot): Boolean = remaining(now) == Duration.ZERO

    /** Time left until the deadline; never negative. */
    fun remaining(now: TimeSnapshot): Duration =
        if (now.bootCount == bootCount) {
            millisUntil(elapsedMillis, now.elapsedMillis)
        } else {
            millisUntil(wallMillis, now.wallMillis)
        }

    companion object {
        /** The deadline [duration] after [now]; [duration] must be finite and not negative. Saturates at `Long.MAX_VALUE`. */
        fun after(
            now: TimeSnapshot,
            duration: Duration,
        ): Deadline {
            require(!duration.isNegative() && duration.isFinite()) { "a deadline needs a finite, non-negative duration, was $duration" }
            val millis = duration.inWholeMilliseconds
            return Deadline(now.wallMillis.saturatingPlus(millis), now.elapsedMillis.saturatingPlus(millis), now.bootCount)
        }

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
