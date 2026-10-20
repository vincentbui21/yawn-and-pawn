package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.testing.FakeClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Epic 4 device follow-up 1: the Ringing clock's time comes from the app's clock, again at every minute boundary. */
class WakeClockTicksTest {
    private val start = Instant.parse("2027-03-03T06:09:40Z")

    @Test
    fun `the wait runs to the next minute boundary, a full minute on one`() {
        assertEquals(20.seconds, untilNextMinute(start))
        assertEquals(1.minutes, untilNextMinute(Instant.parse("2027-03-03T06:10:00Z")))
        assertEquals(1.milliseconds, untilNextMinute(Instant.parse("2027-03-03T06:10:59.999Z")))
    }

    @Test
    fun `it emits now, then at each minute boundary, reading the injected clock`() =
        runTest {
            val clock = FakeClock(start)
            val seen = mutableListOf<Instant>()
            val job = backgroundScope.launch { minuteTicks(clock).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(start), seen)

            // Nothing before the boundary.
            advanceTimeBy(19.seconds)
            runCurrent()
            assertEquals(1, seen.size)

            clock.set(Instant.parse("2027-03-03T06:10:00Z"))
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(Instant.parse("2027-03-03T06:10:00Z"), seen.last())

            clock.set(Instant.parse("2027-03-03T06:11:00Z"))
            advanceTimeBy(1.minutes)
            runCurrent()
            assertEquals(listOf(start, Instant.parse("2027-03-03T06:10:00Z"), Instant.parse("2027-03-03T06:11:00Z")), seen)
            job.cancel()
        }
}
