package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.time.BootCounter
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** 2027-03-03T06:00:00Z, a Wednesday with no DST change near it in the zones the tests use. */
val DEFAULT_FAKE_INSTANT: Instant = Instant.parse("2027-03-03T06:00:00Z")

/** Wall clock under test control. It only moves when told to. */
class FakeClock(
    private var now: Instant = DEFAULT_FAKE_INSTANT,
) : Clock {
    override fun now(): Instant = now

    /** Jumps the wall clock, as a user or network time change would (backwards too). */
    fun set(instant: Instant) {
        now = instant
    }

    fun advanceBy(duration: Duration) {
        now += duration
    }
}

/** Time since boot under test control; [reboot] resets it to 0. */
class FakeMonotonicClock(
    private var elapsedMillis: Long = 0,
) : MonotonicClock {
    init {
        require(elapsedMillis >= 0) { "elapsed time since boot cannot be negative, was $elapsedMillis" }
    }

    override fun elapsedMillis(): Long = elapsedMillis

    fun set(elapsedMillis: Long) {
        require(elapsedMillis >= 0) { "elapsed time since boot cannot be negative, was $elapsedMillis" }
        this.elapsedMillis = elapsedMillis
    }

    fun advanceBy(duration: Duration) {
        requireElapsedStep(duration)
        elapsedMillis += duration.inWholeMilliseconds
    }

    fun reboot() {
        elapsedMillis = 0
    }
}

/** Boot count under test control; [reboot] moves to the next boot. */
class FakeBootCounter(
    private var bootCount: Int = 1,
) : BootCounter {
    override fun bootCount(): Int = bootCount

    fun set(bootCount: Int) {
        this.bootCount = bootCount
    }

    fun reboot() {
        bootCount += 1
    }
}

/** Current time zone under test control. */
class FakeTimeZoneProvider(
    private var zone: TimeZone = TimeZone.UTC,
) : TimeZoneProvider {
    override fun current(): TimeZone = zone

    fun set(zone: TimeZone) {
        this.zone = zone
    }
}

/**
 * The four time fakes moved together: [advanceBy] lets real time pass (wall and elapsed), [setWall] jumps only
 * the wall clock, [reboot] starts a new boot (elapsed back to 0, wall keeps running), [snapshot] builds a
 * [TimeSnapshot] for deadline logic.
 */
class FakeTime(
    val clock: FakeClock = FakeClock(),
    val monotonicClock: FakeMonotonicClock = FakeMonotonicClock(),
    val bootCounter: FakeBootCounter = FakeBootCounter(),
    val timeZoneProvider: FakeTimeZoneProvider = FakeTimeZoneProvider(),
) {
    fun advanceBy(duration: Duration) {
        // Check before moving anything, so a rejected step never leaves wall and elapsed out of sync.
        requireElapsedStep(duration)
        clock.advanceBy(duration)
        monotonicClock.advanceBy(duration)
    }

    fun setWall(instant: Instant) = clock.set(instant)

    fun setZone(zone: TimeZone) = timeZoneProvider.set(zone)

    fun reboot() {
        bootCounter.reboot()
        monotonicClock.reboot()
    }

    fun snapshot(): TimeSnapshot = TimeSnapshot.of(clock, monotonicClock, bootCounter)
}

/** Real time only moves forward, by a finite whole number of milliseconds (the unit of [MonotonicClock]). */
private fun requireElapsedStep(duration: Duration) {
    require(!duration.isNegative() && duration.isFinite()) { "time only moves forward by a finite step, was $duration" }
    require(duration.inWholeMilliseconds.milliseconds == duration) { "time moves in whole milliseconds, was $duration" }
}
