package com.yawnandpawn.app.core.alarm

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long "Gradually increase volume" takes to reach the set volume (FR-SND, Story 1.14). */
val RAMP_DURATION: Duration = 30.seconds

/**
 * The player gain [elapsed] after a ring started with "Gradually increase volume" on (AD-5): linear from
 * [startFraction] at 0 to 1.0 at [duration], then held at 1.0. The alarm stream itself is set to the alarm's volume, so
 * the gain is a fraction of the set volume: with the fixed 20% start it plays at a fifth of it first.
 *
 * Pure. [startFraction] is clamped to 0..1 and a negative [elapsed] counts as 0; a zero (or negative) [duration] means
 * no ramp, so the gain is 1.0 at once.
 */
fun rampGain(
    elapsed: Duration,
    startFraction: Double,
    duration: Duration = RAMP_DURATION,
): Double {
    val start = startFraction.coerceIn(0.0, 1.0)
    if (!duration.isPositive()) return 1.0
    val progress = (elapsed / duration).coerceIn(0.0, 1.0)
    return (start + (1.0 - start) * progress).coerceAtMost(1.0)
}
