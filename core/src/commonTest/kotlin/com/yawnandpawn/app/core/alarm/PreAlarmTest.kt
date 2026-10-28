package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.work.TaskResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 7.11: the pre-alarm job ([PreAlarmScheduler], through [AlarmScheduling]) and its run ([PreAlarmTask]). A daily
 * 07:00 alarm (UTC) with "Offer to skip" on; Tuesday 2027-03-09.
 */
class PreAlarmTest {
    private val tuesday7 = Instant.parse("2027-03-09T07:00:00Z")

    private fun daily(
        enabled: Boolean = true,
        offerSkip: Boolean = true,
        skippedDate: LocalDate? = null,
        repeatDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    ) = Alarm(
        id = "a",
        time = LocalTime(7, 0),
        repeatDays = repeatDays,
        enabled = enabled,
        offerSkip = offerSkip,
        skippedDate = skippedDate,
        requestCode = 1000,
        createdAt = Instant.parse("2027-03-01T00:00:00Z"),
        updatedAt = Instant.parse("2027-03-01T00:00:00Z"),
    )

    private fun at(text: String) = Instant.parse(text)

    @Test
    fun `at 01-00 the job runs at 05-00, 4 h later`() {
        val f = SkipFixture(at("2027-03-09T01:00:00Z"))
        f.scheduling.sync(daily())

        assertEquals(4.hours, f.work.pending.getValue("a"))
    }

    @Test
    fun `inside the 2 h window it runs at once`() {
        val f = SkipFixture(at("2027-03-09T06:00:00Z"))
        f.scheduling.sync(daily())

        assertEquals(Duration.ZERO, f.work.pending.getValue("a"))
    }

    @Test
    fun `the switch off, the alarm off or deleted cancels the job and the notification`() {
        val f = SkipFixture(at("2027-03-09T06:00:00Z"))
        f.notifier.showing[1000] = 1.hours

        f.scheduling.sync(daily(offerSkip = false))
        assertEquals(PreAlarmWorkCall.Cancel("a"), f.work.calls.last())
        assertTrue(f.notifier.showing.isEmpty())

        f.notifier.showing[1000] = 1.hours
        f.scheduling.sync(daily(enabled = false))
        assertEquals(PreAlarmWorkCall.Cancel("a"), f.work.calls.last())
        assertTrue(f.notifier.showing.isEmpty())

        f.notifier.showing[1000] = 1.hours
        f.scheduling.cancel(1000, "a")
        assertEquals(PreAlarmWorkCall.Cancel("a"), f.work.calls.last())
        assertTrue(f.notifier.showing.isEmpty())
    }

    @Test
    fun `a skipped ring moves the job to the next day and removes the notification showing for it`() {
        val f = SkipFixture(at("2027-03-09T05:30:00Z"))
        f.notifier.showing[1000] = 90.minutes

        f.scheduling.sync(daily(skippedDate = LocalDate(2027, 3, 9)))

        assertEquals(at("2027-03-10T05:00:00Z") - f.clock.now, f.work.pending.getValue("a"))
        assertTrue(f.notifier.showing.isEmpty())
    }

    @Test
    fun `the re-arm after a ring schedules the next day's job`() =
        runTest {
            val f = SkipFixture(tuesday7)
            val alarm = daily()
            f.store(alarm)

            RearmOnFire(f.alarms, f.scheduling, f.clock, f.lock, f.logger).onAlarmFired(AlarmFired("a", tuesday7))

            assertEquals(22.hours, f.work.pending.getValue("a"))
        }

    @Test
    fun `before the first unlock nothing is handed to WorkManager, and the notification still goes`() {
        val f = SkipFixture(at("2027-03-09T01:00:00Z"))
        f.userLock.unlocked = false
        f.notifier.showing[1000] = 1.hours

        f.scheduling.sync(daily(enabled = false))

        assertTrue(f.work.calls.isEmpty())
        assertTrue(f.notifier.showing.isEmpty())
    }

    @Test
    fun `a refused hand-over is logged and the alarm is still armed`() {
        val f = SkipFixture(at("2027-03-09T01:00:00Z"))
        f.work.failure = DomainError.BackgroundWorkFailure("refused")

        f.scheduling.sync(daily())

        assertEquals(Call.Schedule("a", 1000, tuesday7), f.scheduler.calls.single())
        assertTrue(f.logger.events.isNotEmpty())
    }

    @Test
    fun `rescheduleAll re-syncs every alarm's job`() =
        runTest {
            val f = SkipFixture(at("2027-03-09T01:00:00Z"))
            f.store(daily())

            f.scheduling.rescheduleAll()

            assertEquals(4.hours, f.work.pending.getValue("a"))
        }

    @Test
    fun `the run posts with the time left when the ring is at most 2 h away`() =
        runTest {
            val f = SkipFixture(at("2027-03-09T05:00:00Z"))
            f.store(daily())

            assertEquals(TaskResult.Done, f.task.run("a"))

            assertEquals(listOf("a" to 2.hours), f.notifier.posts)
        }

    @Test
    fun `a run at or after the occurrence posts nothing`() =
        runTest {
            val f = SkipFixture(at("2027-03-09T07:01:00Z"))
            f.store(daily())

            assertEquals(TaskResult.Done, f.task.run("a"))
            f.clock.now = tuesday7
            assertEquals(TaskResult.Done, f.task.run("a"))

            assertTrue(f.notifier.posts.isEmpty())
        }

    @Test
    fun `the run posts nothing without the permission, with the switch off, for an alarm off, skipped or gone`() =
        runTest {
            val f = SkipFixture(at("2027-03-09T05:30:00Z"))
            f.notifier.allowed = false
            f.store(daily())
            f.task.run("a")

            f.notifier.allowed = true
            f.store(daily(offerSkip = false))
            f.task.run("a")
            f.store(daily(enabled = false))
            f.task.run("a")
            // Skipped: the next ring is tomorrow, more than 2 h away.
            f.store(daily(skippedDate = LocalDate(2027, 3, 9)))
            f.task.run("a")
            assertEquals(TaskResult.Done, f.task.run("gone"))

            assertTrue(f.notifier.posts.isEmpty())
        }

    @Test
    fun `a refused post is logged`() =
        runTest {
            val f = SkipFixture(at("2027-03-09T05:30:00Z"))
            f.store(daily())
            f.notifier.failure = DomainError.BackgroundWorkFailure("refused")

            assertEquals(TaskResult.Done, f.task.run("a"))
            assertTrue(f.logger.events.isNotEmpty())
        }

    @Test
    fun `a DST spring Sunday is skipped by its date and the next ring is Monday's`() {
        val helsinki = TimeZone.of("Europe/Helsinki")
        // Sunday 2027-03-28: 03:00 jumps to 04:00, so a 03:30 alarm rings at 04:30 EEST (01:30Z).
        val alarm = daily().copy(time = LocalTime(3, 30), skippedDate = LocalDate(2027, 3, 28))
        val saturdayNight = at("2027-03-27T20:00:00Z")

        assertEquals(at("2027-03-28T01:30:00Z"), nextOccurrence(alarm.toRule(), saturdayNight, helsinki))
        assertEquals(at("2027-03-29T00:30:00Z"), nextRing(alarm, saturdayNight, helsinki))
        assertTrue(alarm.isNextOccurrenceSkipped(saturdayNight, helsinki))
    }

    @Test
    fun `a DST autumn Sunday is skipped by its date and the next ring is Monday's`() {
        val helsinki = TimeZone.of("Europe/Helsinki")
        // Sunday 2027-10-31: 04:00 goes back to 03:00, so 03:30 happens twice; only the earlier (00:30Z) rings.
        val alarm = daily().copy(time = LocalTime(3, 30), skippedDate = LocalDate(2027, 10, 31))
        val saturdayNight = at("2027-10-30T20:00:00Z")

        assertEquals(at("2027-10-31T00:30:00Z"), nextOccurrence(alarm.toRule(), saturdayNight, helsinki))
        assertEquals(at("2027-11-01T01:30:00Z"), nextRing(alarm, saturdayNight, helsinki))
    }

    @Test
    fun `a past skipped date skips nothing`() {
        val alarm = daily(skippedDate = LocalDate(2027, 3, 1))

        assertEquals(tuesday7, nextRing(alarm, at("2027-03-09T01:00:00Z"), TimeZone.UTC))
        assertTrue(!alarm.isNextOccurrenceSkipped(at("2027-03-09T01:00:00Z"), TimeZone.UTC))
    }
}
