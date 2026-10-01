package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TimeFakesTest {
    @Test
    fun `FakeClock starts at the default instant and moves only when told to`() {
        val clock = FakeClock()
        assertEquals(DEFAULT_FAKE_INSTANT, clock.now())

        clock.advanceBy(90.minutes)
        assertEquals(DEFAULT_FAKE_INSTANT + 90.minutes, clock.now())

        clock.set(Instant.parse("2027-01-01T00:00:00Z"))
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), clock.now())
    }

    @Test
    fun `FakeMonotonicClock advances, can be set and resets to zero on reboot`() {
        val monotonic = FakeMonotonicClock()
        assertEquals(0, monotonic.elapsedMillis())

        monotonic.advanceBy(5.minutes)
        assertEquals(300_000, monotonic.elapsedMillis())
        monotonic.set(42)
        assertEquals(42, monotonic.elapsedMillis())
        monotonic.reboot()
        assertEquals(0, monotonic.elapsedMillis())
    }

    @Test
    fun `FakeMonotonicClock never goes back`() {
        val monotonic = FakeMonotonicClock()
        listOf<() -> Unit>({ monotonic.advanceBy((-1).minutes) }, { monotonic.set(-1) }).forEach { move ->
            assertFailsWith<IllegalArgumentException> { move() }
        }
    }

    @Test
    fun `FakeMonotonicClock rejects a negative start and non-whole or infinite steps`() {
        assertFailsWith<IllegalArgumentException> { FakeMonotonicClock(-1) }
        val monotonic = FakeMonotonicClock()
        listOf(Duration.INFINITE, 500.microseconds, 1.milliseconds + 1.nanoseconds).forEach { step ->
            assertFailsWith<IllegalArgumentException>("$step") { monotonic.advanceBy(step) }
        }
        assertEquals(0, monotonic.elapsedMillis())
    }

    @Test
    fun `FakeTime rejects a bad step before moving any clock, so wall and elapsed stay in sync`() {
        val time = FakeTime()
        val before = time.snapshot()
        listOf((-1).minutes, Duration.INFINITE, 500.microseconds, 1.milliseconds + 1.nanoseconds).forEach { step ->
            assertFailsWith<IllegalArgumentException>("$step") { time.advanceBy(step) }
            assertEquals(before, time.snapshot(), "after rejecting $step")
        }
    }

    @Test
    fun `FakeBootCounter can be set and counts up on reboot`() {
        val boots = FakeBootCounter()
        assertEquals(1, boots.bootCount())

        boots.reboot()
        assertEquals(2, boots.bootCount())
        boots.set(10)
        assertEquals(10, boots.bootCount())
    }

    @Test
    fun `FakeTimeZoneProvider starts in UTC and can be set`() {
        val zones = FakeTimeZoneProvider()
        assertEquals(TimeZone.UTC, zones.current())

        zones.set(TimeZone.of("Europe/Berlin"))
        assertEquals(TimeZone.of("Europe/Berlin"), zones.current())
    }

    @Test
    fun `FakeTime moves wall and elapsed together, jumps the wall alone and reboots with the wall running`() {
        val time = FakeTime()
        val start = time.snapshot()
        assertEquals(TimeSnapshot(DEFAULT_FAKE_INSTANT.toEpochMilliseconds(), 0, 1), start)

        time.advanceBy(10.minutes)
        assertEquals(start.copy(wallMillis = start.wallMillis + 600_000, elapsedMillis = 600_000), time.snapshot())

        time.setWall(DEFAULT_FAKE_INSTANT - 2.hours)
        assertEquals(600_000, time.snapshot().elapsedMillis)
        assertEquals((DEFAULT_FAKE_INSTANT - 2.hours).toEpochMilliseconds(), time.snapshot().wallMillis)

        val wallBeforeReboot = time.clock.now()
        time.reboot()
        assertEquals(TimeSnapshot(wallBeforeReboot.toEpochMilliseconds(), 0, 2), time.snapshot())

        time.setZone(TimeZone.of("America/New_York"))
        assertEquals(TimeZone.of("America/New_York"), time.timeZoneProvider.current())
    }

    @Test
    fun `a deadline built from FakeTime ignores wall jumps on the same boot and uses wall time after reboot`() {
        val time = FakeTime()
        val deadline = Deadline.after(time.snapshot(), 10.minutes)

        time.setWall(time.clock.now() + 2.hours)
        time.monotonicClock.advanceBy(5.minutes)
        assertFalse(deadline.isDue(time.snapshot()))
        assertEquals(5.minutes, deadline.remaining(time.snapshot()))

        time.reboot()
        assertTrue(deadline.isDue(time.snapshot()))
        assertEquals(Duration.ZERO, deadline.remaining(time.snapshot()))
    }

    @Test
    fun `FakeTimeChangeSignal reaches its collectors and counts them`() =
        runTest {
            val signal = FakeTimeChangeSignal()
            var received = 0
            assertEquals(0, signal.subscribers)

            val job = launch(UnconfinedTestDispatcher(testScheduler)) { signal.changes().collect { received++ } }
            signal.emit()
            signal.emit()

            assertEquals(1, signal.subscribers)
            assertEquals(2, received)
            job.cancel()
        }
}
