package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Epic 4 device follow-up 1: the Ringing clock's time and zone come from the app's ports, again at every time-change
 * signal and, as a backstop, at every minute boundary.
 */
class WakeClockTicksTest {
    private val start = Instant.parse("2027-03-03T06:09:40Z")
    private val utc = TimeZone.UTC

    @Test
    fun `the wait runs to the next minute boundary, a full minute on one`() {
        assertEquals(20.seconds, untilNextMinute(start))
        assertEquals(1.minutes, untilNextMinute(Instant.parse("2027-03-03T06:10:00Z")))
        assertEquals(1.milliseconds, untilNextMinute(Instant.parse("2027-03-03T06:10:59.999Z")))
    }

    @Test
    fun `it emits now, then at each time-change signal with the zone of the moment`() =
        runTest {
            val clock = FakeClock(start)
            val zones = FakeTimeZoneProvider(utc)
            val changes = FakeTimeChangeSignal()
            val seen = mutableListOf<WallTime>()
            backgroundScope.launch { wallTimes(clock, zones, changes).collect { seen += it } }
            runCurrent()
            assertEquals(listOf(WallTime(start, utc)), seen)

            // The clock was set and the zone changed: the system's signal brings both at once, before any boundary.
            val berlin = TimeZone.of("Europe/Berlin")
            clock.set(Instant.parse("2027-03-03T07:30:05Z"))
            zones.set(berlin)
            changes.emit()
            runCurrent()

            assertEquals(WallTime(Instant.parse("2027-03-03T07:30:05Z"), berlin), seen.last())
        }

    @Test
    fun `without a signal the minute boundary still moves it on`() =
        runTest {
            val clock = FakeClock(start)
            val seen = mutableListOf<Instant>()
            backgroundScope.launch { wallTimes(clock, FakeTimeZoneProvider(utc), FakeTimeChangeSignal()).collect { seen += it.now } }
            runCurrent()

            // Nothing before the boundary.
            advanceTimeBy(19.seconds)
            runCurrent()
            assertEquals(listOf(start), seen)

            clock.set(Instant.parse("2027-03-03T06:10:00Z"))
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(Instant.parse("2027-03-03T06:10:00Z"), seen.last())

            clock.set(Instant.parse("2027-03-03T06:11:00Z"))
            advanceTimeBy(1.minutes)
            runCurrent()
            assertEquals(listOf(start, Instant.parse("2027-03-03T06:10:00Z"), Instant.parse("2027-03-03T06:11:00Z")), seen)
        }
}
