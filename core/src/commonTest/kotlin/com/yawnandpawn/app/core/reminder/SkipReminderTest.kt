package com.yawnandpawn.app.core.reminder

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.Shown
import com.yawnandpawn.app.core.alarm.SkipFixture
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.ringSession
import com.yawnandpawn.app.core.work.SchedulePolicy
import com.yawnandpawn.app.core.work.TaskResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 0.1.8: the global Skip reminder (FR-ALM-10). Monday 2027-03-08 in UTC; [tuesday] and [wednesday] are the dates the
 * reminders are for. Each test row of the spec's I/O matrix has its test here.
 */
class SkipReminderTest {
    private val monday22 = Instant.parse("2027-03-08T22:00:00Z")
    private val monday = LocalDate(2027, 3, 8)
    private val tuesday = LocalDate(2027, 3, 9)
    private val wednesday = LocalDate(2027, 3, 10)

    private fun at(text: String) = Instant.parse("2027-03-${text}Z")

    private fun alarm(
        id: String,
        time: LocalTime,
        repeatDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
        enabled: Boolean = true,
        updatedAt: Instant = Instant.parse("2027-03-01T00:00:00Z"),
        skippedDate: LocalDate? = null,
        requestCode: Int = 1000,
    ) = Alarm(
        id = id,
        time = time,
        repeatDays = repeatDays,
        enabled = enabled,
        requestCode = requestCode,
        createdAt = Instant.parse("2027-03-01T00:00:00Z"),
        updatedAt = updatedAt,
        skippedDate = skippedDate,
    )

    private suspend fun SkipFixture.twoDaily() {
        store(alarm("a", LocalTime(7, 0)))
        store(alarm("b", LocalTime(7, 30), requestCode = 1001))
    }

    @Test
    fun `normal day - the job waits until 2 h before the first ring, then posts with the real time left and schedules the next date`() =
        runTest {
            val f = SkipFixture(monday22)
            f.twoDaily()

            f.reminder.sync()

            assertEquals(7.hours, f.work.reminder, "Tuesday 05:00")
            assertNull(f.notifier.showing)

            f.clock.now = at("09T05:00:00")
            assertEquals(TaskResult.Done, f.task.run())

            val posted = f.notifier.posts.single()
            assertEquals(SkipReminderTarget(tuesday, at("09T07:00:00"), LocalTime(7, 0)), posted.target)
            assertEquals(2.hours, posted.ringsIn)
            assertEquals(tuesday, f.reminderStore.prefs.lastRemindedDate)
            assertEquals(24.hours, f.work.reminder, "Wednesday 05:00")
        }

    @Test
    fun `a late job posts with the time actually left`() =
        runTest {
            val f = SkipFixture(at("09T06:20:00"))
            f.twoDaily()

            f.task.run()

            assertEquals(
                40.minutes,
                f.notifier.posts
                    .single()
                    .ringsIn,
            )
        }

    @Test
    fun `a job that runs early posts nothing and only schedules again`() =
        runTest {
            val f = SkipFixture(at("09T04:59:00"))
            f.twoDaily()

            f.task.run()

            assertTrue(f.notifier.posts.isEmpty())
            assertEquals(1.minutes, f.work.reminder)
            assertNull(f.reminderStore.prefs.lastRemindedDate)
        }

    @Test
    fun `a new alarm 1 h away posts at once`() =
        runTest {
            val f = SkipFixture(at("09T07:00:00"))
            val oneTime = alarm("a", LocalTime(8, 0), repeatDays = emptySet(), updatedAt = at("09T07:00:00"))
            f.store(oneTime)

            f.scheduling.sync(oneTime)

            val posted = f.notifier.posts.single()
            assertEquals(1.hours, posted.ringsIn)
            assertEquals(tuesday, posted.target.date)
            assertNull(f.work.reminder, "a one-time alarm has no next date")
        }

    @Test
    fun `a new alarm 10 min away gets no reminder that day, and the next date is scheduled`() =
        runTest {
            val now = at("09T07:50:00")
            val f = SkipFixture(now)
            val daily = alarm("a", LocalTime(8, 0), updatedAt = now)
            f.store(daily)

            f.scheduling.sync(daily)

            assertTrue(f.notifier.posts.isEmpty())
            assertEquals(at("10T06:00:00") - now, f.work.reminder)
            assertEquals(tuesday, f.reminderStore.prefs.lastRemindedDate, "handled, so it never comes back")
        }

    @Test
    fun `once the first ring of the day has passed there is no reminder for that day`() =
        runTest {
            val now = at("09T07:05:00")
            val f = SkipFixture(now)
            f.store(alarm("a", LocalTime(7, 0)))
            f.store(alarm("b", LocalTime(9, 0), requestCode = 1001))

            f.reminder.sync()

            assertTrue(f.notifier.posts.isEmpty())
            assertEquals(at("10T05:00:00") - now, f.work.reminder)
        }

    @Test
    fun `an alarm saved after its time today did not ring, so a later alarm that day still gets its reminder`() =
        runTest {
            val now = at("09T07:05:00")
            val f = SkipFixture(now)
            f.store(alarm("a", LocalTime(7, 0), updatedAt = at("09T07:02:00")))
            f.store(alarm("b", LocalTime(9, 0), requestCode = 1001))

            f.reminder.sync()

            assertEquals(
                LocalTime(9, 0),
                f.notifier.posts
                    .single()
                    .target.time,
            )
        }

    @Test
    fun `Skip today's alarms skips every alarm of that date with its Skipped row, for free`() =
        runTest {
            val f = SkipFixture(at("09T05:10:00"))
            f.twoDaily()
            f.store(alarm("c", LocalTime(7, 0), repeatDays = setOf(DayOfWeek.WEDNESDAY), requestCode = 1002))
            f.reminder.sync()

            assertEquals(Outcome.Success(2), f.skipToday(tuesday))

            assertEquals(tuesday, f.stored("a").skippedDate)
            assertEquals(tuesday, f.stored("b").skippedDate)
            assertNull(f.stored("c").skippedDate, "an alarm of another date is left alone")
            val rows =
                f.history.rows.values
                    .toList()
            assertEquals(setOf("a", "b"), rows.map { it.alarmId }.toSet())
            assertTrue(rows.all { it.outcome == SessionOutcome.Skipped })
            assertEquals(Outcome.Success(0), f.skipToday(tuesday), "a second press skips nothing new")
            assertEquals(2, f.history.rows.size)
        }

    @Test
    fun `Skip today's alarms during a session skips nothing`() =
        runTest {
            val f = SkipFixture(at("09T05:10:00"))
            f.twoDaily()
            f.session.value = SessionState.Ringing(ringSession())

            assertEquals(Outcome.Failure(DomainError.SessionActive), f.skipToday(tuesday))

            assertNull(f.stored("a").skippedDate)
            assertNull(f.stored("b").skippedDate)
            assertTrue(f.history.rows.isEmpty())
        }

    @Test
    fun `the setting off cancels the job and removes a posted reminder, and on schedules it again`() =
        runTest {
            val f = SkipFixture(at("09T05:00:00"))
            f.twoDaily()
            f.reminder.sync()
            assertIs<Shown.Reminder>(f.notifier.showing)

            assertEquals(Outcome.Success(Unit), f.setReminder(false))

            assertEquals(false, f.reminderStore.prefs.enabled)
            assertNull(f.work.reminder)
            assertNull(f.notifier.showing)

            f.scheduling.sync(f.stored("a"))
            assertNull(f.work.reminder, "an alarm change schedules nothing while it is off")

            assertEquals(Outcome.Success(Unit), f.setReminder(true))
            assertEquals(24.hours, f.work.reminder, "Tuesday was reminded already: Wednesday 05:00")
        }

    @Test
    fun `the switch is not touched during a session`() =
        runTest {
            val f = SkipFixture(monday22)
            f.session.value = SessionState.Ringing(ringSession())

            assertEquals(Outcome.Failure(DomainError.SessionActive), f.setReminder(false))

            assertEquals(true, f.reminderStore.prefs.enabled)
        }

    @Test
    fun `a session in progress at the reminder time posts nothing and the next date is scheduled`() =
        runTest {
            val f = SkipFixture(at("09T05:00:00"))
            f.twoDaily()
            f.session.value = SessionState.Ringing(ringSession())

            f.task.run()

            assertTrue(f.notifier.posts.isEmpty())
            assertEquals(tuesday, f.reminderStore.prefs.lastRemindedDate)
            assertEquals(24.hours, f.work.reminder)
        }

    @Test
    fun `notifications not allowed post nothing and the next date is scheduled`() =
        runTest {
            val f = SkipFixture(at("09T05:00:00"))
            f.twoDaily()
            f.notifier.allowed = false

            f.task.run()

            assertTrue(f.notifier.posts.isEmpty())
            assertEquals(24.hours, f.work.reminder)
        }

    @Test
    fun `a date never gets two reminders`() =
        runTest {
            val f = SkipFixture(at("09T05:00:00"))
            f.twoDaily()

            f.task.run()
            f.clock.now = at("09T05:30:00")
            f.reminder.sync()
            f.task.run()

            assertEquals(1, f.notifier.posts.size)
        }

    @Test
    fun `a date whose alarms are all off or skipped has no reminder, the next date with an alarm has`() =
        runTest {
            val f = SkipFixture(monday22)
            f.store(alarm("a", LocalTime(7, 0), skippedDate = tuesday))
            f.store(alarm("b", LocalTime(6, 0), enabled = false, requestCode = 1001))

            f.reminder.sync()

            assertEquals(at("10T05:00:00") - monday22, f.work.reminder)
            assertEquals(
                SkipReminderPlan(monday, SkipReminderTarget(wednesday, at("10T07:00:00"), LocalTime(7, 0))),
                skipReminderPlan(
                    f.alarms.alarms.value.values
                        .toList(),
                    monday22,
                    TimeZone.UTC,
                    null,
                ),
            )
        }

    @Test
    fun `no alarm at all cancels the job`() =
        runTest {
            val f = SkipFixture(monday22)

            f.reminder.sync()

            assertEquals(listOf<Duration?>(null), f.work.calls)
        }

    @Test
    fun `before the first unlock nothing is handed to the work, after it rescheduleAll syncs`() =
        runTest {
            val f = SkipFixture(monday22)
            f.twoDaily()
            f.userLock.unlocked = false

            f.reminder.sync()
            assertTrue(f.work.calls.isEmpty())

            f.userLock.unlocked = true
            f.scheduling.rescheduleAll()
            assertEquals(7.hours, f.work.reminder)
        }

    @Test
    fun `rescheduleAll cancels the old per-alarm pre-alarm jobs, which do nothing any more`() =
        runTest {
            val f = SkipFixture(monday22)
            f.twoDaily()

            f.scheduling.rescheduleAll()

            assertEquals(listOf("a", "b"), f.work.preAlarmCancels.sorted())
            assertTrue(f.notifier.posts.isEmpty())
        }

    @Test
    fun `a store that cannot be read posts and schedules nothing`() =
        runTest {
            val f = SkipFixture(at("09T05:00:00"))
            f.twoDaily()
            f.reminderStore.readFailure = DomainError.StorageFailure("disk")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk")), f.reminder.sync())

            assertTrue(f.notifier.posts.isEmpty())
            assertTrue(f.work.calls.isEmpty())
        }

    @Test
    fun `a date that cannot be stored as reminded is not posted, so it can never post twice`() =
        runTest {
            val f = SkipFixture(at("09T05:00:00"))
            f.twoDaily()
            f.reminderStore.writeFailure = DomainError.StorageFailure("disk")

            f.task.run()

            assertTrue(f.notifier.posts.isEmpty())
        }

    @Test
    fun `undoing a skip brings the date back once it was not reminded`() =
        runTest {
            val f = SkipFixture(monday22)
            f.twoDaily()
            f.skipToday(tuesday)
            assertEquals(at("10T05:00:00") - monday22, f.work.reminder)

            f.undo("a")

            assertEquals(7.hours, f.work.reminder)
        }

    @Test
    fun `the first ring follows a time-zone change`() =
        runTest {
            val f = SkipFixture(monday22)
            f.twoDaily()
            f.zones.zone = TimeZone.of("Europe/Helsinki")

            f.reminder.sync()

            // 07:00 in Helsinki (UTC+2) is 05:00 UTC; the reminder is at 03:00 UTC.
            assertEquals(5.hours, f.work.reminder)
        }

    // Review fixes (0.1.8) ------------------------------------------------------------------------------------------

    @Test
    fun `two reminder windows open at once near midnight post one each, then the job waits for the next date`() =
        runTest {
            val now = at("08T22:40:00")
            val f = SkipFixture(now)
            f.store(alarm("mon", LocalTime(23, 0), repeatDays = emptySet(), updatedAt = now))
            f.store(alarm("tue", LocalTime(0, 30), repeatDays = emptySet(), updatedAt = now, requestCode = 1001))
            f.store(alarm("wed", LocalTime(7, 0), repeatDays = setOf(DayOfWeek.WEDNESDAY), requestCode = 1002))

            f.reminder.sync()
            f.reminder.sync()

            assertEquals(listOf(monday to false, tuesday to true), f.notifier.posts.map { it.target.date to it.tomorrow })
            assertEquals(tuesday, f.reminderStore.prefs.lastRemindedDate)
            assertEquals(at("10T05:00:00") - now, f.work.reminder, "Wednesday 05:00")
        }

    @Test
    fun `a one-time alarm saved 10 min before its ring handles that day, so a later alarm that day gets no reminder`() =
        runTest {
            val now = at("09T07:50:00")
            val f = SkipFixture(now)
            val oneTime = alarm("once", LocalTime(8, 0), repeatDays = emptySet(), updatedAt = now)
            f.store(oneTime)
            f.store(alarm("later", LocalTime(10, 0), requestCode = 1001))

            f.reminder.sync()
            assertEquals(tuesday, f.reminderStore.prefs.lastRemindedDate)

            // 08:00 rang: the one-time alarm is switched off; 10:00 is now the day's first ring, 1 h 59 min away.
            f.clock.now = at("09T08:01:00")
            f.store(oneTime.copy(enabled = false, updatedAt = at("09T08:01:00")))
            f.reminder.sync()

            assertTrue(f.notifier.posts.isEmpty())
            assertEquals(at("10T08:00:00") - at("09T08:01:00"), f.work.reminder, "Wednesday's 10:00, at 08:00")
        }

    @Test
    fun `a ring then an edit never brings the day back`() =
        runTest {
            val f = SkipFixture(at("09T07:05:00"))
            f.store(alarm("a", LocalTime(7, 0)))
            f.store(alarm("b", LocalTime(9, 0), requestCode = 1001))
            f.reminder.sync()

            f.store(alarm("a", LocalTime(7, 0), updatedAt = at("09T07:06:00")))
            f.reminder.sync()

            assertTrue(f.notifier.posts.isEmpty())
        }

    @Test
    fun `before the first unlock nothing is posted or stored`() =
        runTest {
            val f = SkipFixture(at("09T05:00:00"))
            f.twoDaily()
            f.userLock.unlocked = false

            f.reminder.sync()

            assertTrue(f.notifier.posts.isEmpty())
            assertNull(f.reminderStore.prefs.lastRemindedDate)
        }

    @Test
    fun `the job's own run chains the next job after it, every other sync replaces`() =
        runTest {
            val f = SkipFixture(at("09T05:00:00"))
            f.twoDaily()

            f.task.run()
            f.reminder.sync()

            assertEquals(listOf(SchedulePolicy.AfterRun, SchedulePolicy.Replace), f.work.policies)
        }

    @Test
    fun `the job's own run with no next date cancels nothing`() =
        runTest {
            val f = SkipFixture(at("09T05:00:00"))
            f.store(alarm("once", LocalTime(7, 0), repeatDays = emptySet()))

            f.task.run()

            assertEquals(1, f.notifier.posts.size)
            assertTrue(f.work.calls.isEmpty())
        }

    @Test
    fun `a skip that fails half way returns the failure, the alarms skipped stay skipped`() =
        runTest {
            val f = SkipFixture(at("09T05:10:00"))
            f.twoDaily()
            f.history.upsertFailure = DomainError.StorageFailure("disk")
            f.history.upsertsBeforeFailure = 1

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk")), f.skipToday(tuesday))

            assertEquals(
                1,
                f.alarms.alarms.value.values
                    .count { it.skippedDate == tuesday },
            )
            assertEquals(1, f.history.rows.size)

            f.history.upsertFailure = null
            assertEquals(Outcome.Success(1), f.skipToday(tuesday), "a second press skips the rest")
            assertTrue(
                f.alarms.alarms.value.values
                    .all { it.skippedDate == tuesday },
            )
        }

    @Test
    fun `Don't skip removes the skipped confirmation only`() =
        runTest {
            val f = SkipFixture(at("09T05:10:00"))
            f.twoDaily()
            f.skipToday(tuesday)
            f.notifier.showSkipped(1.hours)

            assertEquals(Outcome.Success(true), f.undo("a"))

            assertNull(f.notifier.skipped)
        }

    @Test
    fun `rescheduleAll removes the old pre-alarm notifications too`() =
        runTest {
            val f = SkipFixture(monday22)
            f.twoDaily()

            f.scheduling.rescheduleAll()

            assertEquals(listOf(1000, 1001), f.notifier.legacyCancels.sorted())
        }
}
