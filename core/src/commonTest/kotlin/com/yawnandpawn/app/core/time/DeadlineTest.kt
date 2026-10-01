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
    fun `after adds the duration to wall and elapsed time and keeps the boot count`() {
        assertEquals(Deadline(wallMillis = 1_800_000_600_000, elapsedMillis = 4_200_000, bootCount = 7), deadline)
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

        assertEquals(Deadline(Long.MAX_VALUE, Long.MAX_VALUE, 1), far)
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
