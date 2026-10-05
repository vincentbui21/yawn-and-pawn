package com.yawnandpawn.app.core.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DeadlineTest {
    private val created = TimeSnapshot(wallMillis = 1_800_000_000_000, elapsedMillis = 3_600_000, bootCount = 7)
    private val deadline = Deadline.after(created, 10.minutes)

    private data class Case(
        val name: String,
        val now: TimeSnapshot,
        val due: Boolean,
        val remaining: Duration,
    )

    private fun later(
        wall: Duration,
        elapsed: Duration,
    ) = TimeSnapshot(
        wallMillis = created.wallMillis + wall.inWholeMilliseconds,
        elapsedMillis = created.elapsedMillis + elapsed.inWholeMilliseconds,
        bootCount = created.bootCount,
    )

    private fun rebooted(
        wall: Duration,
        elapsedMillis: Long,
    ) = TimeSnapshot(wallMillis = created.wallMillis + wall.inWholeMilliseconds, elapsedMillis = elapsedMillis, bootCount = 8)

    private val cases =
        listOf(
            Case("just created", created, due = false, remaining = 10.minutes),
            Case("same boot, wall moved forward 2 h", later(wall = 2.hours + 5.minutes, elapsed = 5.minutes), false, 5.minutes),
            Case("same boot, wall moved back 2 h", later(wall = (-2).hours + 5.minutes, elapsed = 5.minutes), false, 5.minutes),
            Case("same boot, wall far past but elapsed not", later(wall = 3.hours, elapsed = 9.minutes), false, 1.minutes),
            Case("same boot, exactly at the deadline", later(wall = 10.minutes, elapsed = 10.minutes), true, Duration.ZERO),
            Case("same boot, past the deadline", later(wall = 1.minutes, elapsed = 30.minutes), true, Duration.ZERO),
            Case("after reboot, wall past the deadline", rebooted(wall = 11.minutes, elapsedMillis = 20_000), true, Duration.ZERO),
            Case("after reboot, wall before the deadline", rebooted(wall = 4.minutes, elapsedMillis = 999_999_999), false, 6.minutes),
        )

    @Test
    fun `isDue and remaining compare elapsed time on the same boot and wall time after a reboot`() {
        cases.forEach { case ->
            assertEquals(case.due, deadline.isDue(case.now), "isDue: ${case.name}")
            assertEquals(case.remaining, deadline.remaining(case.now), "remaining: ${case.name}")
        }
    }

    @Test
    fun `after adds the duration to wall and elapsed time and keeps the boot count and the elapsed time it was made at`() {
        assertEquals(
            Deadline(wallMillis = 1_800_000_600_000, elapsedMillis = 4_200_000, bootCount = 7, createdElapsedMillis = 3_600_000),
            deadline,
        )
    }

    @Test
    fun `with the same boot count a lower elapsed time than at creation means a reboot - wall time decides`() {
        // A device without BOOT_COUNT reports the same count every boot.
        val sameCountRebooted =
            TimeSnapshot(wallMillis = created.wallMillis + 4.minutes.inWholeMilliseconds, elapsedMillis = 60_000, bootCount = 7)
        val sameCountRebootedLate = sameCountRebooted.copy(wallMillis = created.wallMillis + 11.minutes.inWholeMilliseconds)

        assertFalse(deadline.sameBoot(sameCountRebooted))
        assertEquals(6.minutes, deadline.remaining(sameCountRebooted))
        assertEquals(true, deadline.isDue(sameCountRebootedLate))
        assertEquals(true, deadline.sameBoot(later(wall = (-2).hours, elapsed = Duration.ZERO)), "at creation, whatever the wall clock")
    }

    @Test
    fun `a deadline stored before Story 2_2 has no creation time, so only the boot count decides, as before`() {
        val legacy = Deadline(wallMillis = deadline.wallMillis, elapsedMillis = deadline.elapsedMillis, bootCount = 7)

        cases.forEach { case -> assertEquals(case.remaining, legacy.remaining(case.now), case.name) }
    }

    @Test
    fun `any negative boot count is the missing marker, so a wall-derived identity from an older version still matches`() {
        // An older version stored a negative identity derived from the wall clock; after the update the counter says -1.
        val oldRow = Deadline(wallMillis = deadline.wallMillis, elapsedMillis = deadline.elapsedMillis, bootCount = -1_234_567)
        val afterUpdate =
            created.copy(
                wallMillis = created.wallMillis + 3.hours.inWholeMilliseconds,
                elapsedMillis = created.elapsedMillis + 60_000,
                bootCount = -1,
            )

        assertEquals(true, oldRow.sameBoot(afterUpdate))
        assertEquals(9.minutes, oldRow.remaining(afterUpdate), "monotonic, the 3 h wall change is ignored")
        assertFalse(oldRow.sameBoot(afterUpdate.copy(bootCount = 7)), "a negative count never matches a real one")
        assertEquals(true, Deadline.sameBoot(created.copy(bootCount = -9), afterUpdate))
        assertFalse(Deadline.sameBoot(created.copy(bootCount = -9), afterUpdate.copy(elapsedMillis = 1_000)), "elapsed went back")
        assertFalse(Deadline.sameBoot(created.copy(bootCount = -9), created))
    }

    @Test
    fun `two snapshots are of the same boot only with the same count and elapsed time not going back`() {
        assertEquals(true, Deadline.sameBoot(created, later(wall = (-1).hours, elapsed = 1.minutes)))
        assertFalse(Deadline.sameBoot(created, created.copy(elapsedMillis = 1_000)), "elapsed went back: a reboot")
        assertFalse(Deadline.sameBoot(created, rebooted(wall = 1.minutes, elapsedMillis = 99_999_999)), "another boot count")
    }

    @Test
    fun `after rejects negative and infinite durations`() {
        listOf((-1).milliseconds, Duration.INFINITE, -Duration.INFINITE).forEach { duration ->
            assertFailsWith<IllegalArgumentException>("$duration") { Deadline.after(created, duration) }
        }
    }

    @Test
    fun `after saturates instead of overflowing and a far deadline is never due`() {
        val nearMax = TimeSnapshot(wallMillis = Long.MAX_VALUE - 10, elapsedMillis = Long.MAX_VALUE - 10, bootCount = 1)
        val far = Deadline.after(nearMax, 1.hours)

        assertEquals(Deadline(Long.MAX_VALUE, Long.MAX_VALUE, 1, createdElapsedMillis = Long.MAX_VALUE - 10), far)
        assertFalse(far.isDue(nearMax))
        assertEquals(10.milliseconds, far.remaining(nearMax))
    }

    @Test
    fun `remaining compares before subtracting so extreme values cannot overflow`() {
        val far = Deadline(wallMillis = Long.MAX_VALUE, elapsedMillis = Long.MAX_VALUE, bootCount = 1)
        val farPast = Deadline(wallMillis = Long.MIN_VALUE, elapsedMillis = Long.MIN_VALUE, bootCount = 1)

        data class Row(
            val name: String,
            val deadline: Deadline,
            val now: TimeSnapshot,
            val expected: Duration,
        )
        listOf(
            Row("same boot, now far negative", far, TimeSnapshot(0, Long.MIN_VALUE, 1), Long.MAX_VALUE.milliseconds),
            Row("other boot, now far negative", far, TimeSnapshot(Long.MIN_VALUE, 0, 2), Long.MAX_VALUE.milliseconds),
            Row("same boot, deadline far in the past", farPast, TimeSnapshot(0, Long.MAX_VALUE, 1), Duration.ZERO),
            Row("other boot, deadline far in the past", farPast, TimeSnapshot(Long.MAX_VALUE, 0, 2), Duration.ZERO),
        ).forEach { row ->
            assertEquals(row.expected, row.deadline.remaining(row.now), row.name)
            assertEquals(row.expected == Duration.ZERO, row.deadline.isDue(row.now), row.name)
        }
    }

    @Test
    fun `a snapshot reads each port once`() {
        val clock =
            object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(42)
            }
        val snapshot = TimeSnapshot.of(clock = clock, monotonicClock = { 1_000 }, bootCounter = { 3 })

        assertEquals(TimeSnapshot(wallMillis = 42, elapsedMillis = 1_000, bootCount = 3), snapshot)
        val oneMillisLater = snapshot.copy(elapsedMillis = 1_001)
        assertEquals(10.minutes - 1.milliseconds, Deadline.after(snapshot, 10.minutes).remaining(oneMillisLater))
    }

    @Test
    fun `a shifted deadline moves later on both clocks and keeps its boot`() {
        val deadline = Deadline(wallMillis = 1_000, elapsedMillis = 500, bootCount = 2)
        assertEquals(Deadline(wallMillis = 601_000, elapsedMillis = 600_500, bootCount = 2), deadline.shiftedBy(10.minutes))
        assertEquals(deadline, deadline.shiftedBy(Duration.ZERO))
        val nearEnd = Deadline(wallMillis = Long.MAX_VALUE - 5, elapsedMillis = Long.MAX_VALUE - 5, bootCount = 2)
        assertEquals(Deadline(Long.MAX_VALUE, Long.MAX_VALUE, 2), nearEnd.shiftedBy(10.milliseconds))
    }

    @Test
    fun `a deadline only shifts later by a finite step`() {
        val deadline = Deadline(wallMillis = 1_000, elapsedMillis = 500, bootCount = 2)
        listOf((-1).minutes, Duration.INFINITE).forEach { step ->
            assertFailsWith<IllegalArgumentException>("$step") { deadline.shiftedBy(step) }
        }
    }
}
