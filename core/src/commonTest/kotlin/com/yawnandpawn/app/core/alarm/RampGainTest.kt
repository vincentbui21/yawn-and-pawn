package com.yawnandpawn.app.core.alarm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RampGainTest {
    private fun assertGain(
        expected: Double,
        elapsed: Duration,
        startFraction: Double = 0.2,
        duration: Duration = RAMP_DURATION,
    ) = assertEquals(expected, rampGain(elapsed, startFraction, duration), absoluteTolerance = 1e-9, "at $elapsed from $startFraction")

    @Test
    fun `a 20 percent ramp starts at 0_2, is 0_6 halfway and 1_0 at 30 s and after`() {
        assertGain(0.2, 0.seconds)
        assertGain(0.6, 15.seconds)
        assertGain(1.0, 30.seconds)
        assertGain(1.0, 45.seconds)
    }

    @Test
    fun `the ramp is linear between its ends`() {
        assertGain(0.2 + 0.8 * (250.0 / 30_000), 250.milliseconds)
        assertGain(0.2 + 0.8 * 0.75, 22_500.milliseconds)
    }

    @Test
    fun `a ramp that starts at 1_0 stays at full gain`() {
        listOf(0.seconds, 15.seconds, 30.seconds, 45.seconds).forEach { assertGain(1.0, it, startFraction = 1.0) }
    }

    @Test
    fun `out-of-range input is clamped`() {
        assertGain(0.2, (-5).seconds)
        assertGain(0.0, 0.seconds, startFraction = -1.0)
        assertGain(1.0, 0.seconds, startFraction = 2.0)
        assertGain(1.0, 0.seconds, duration = Duration.ZERO)
        assertGain(1.0, Duration.INFINITE)
    }

    @Test
    fun `the default duration is 30 s`() {
        assertEquals(30.seconds, RAMP_DURATION)
        assertEquals(0.6, rampGain(15.seconds, 0.2), absoluteTolerance = 1e-9)
    }
}
