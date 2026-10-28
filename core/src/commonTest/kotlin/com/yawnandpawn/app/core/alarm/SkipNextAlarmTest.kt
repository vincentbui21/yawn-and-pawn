package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.ringSession
import com.yawnandpawn.app.core.stats.isCounted
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 7.11: "Skip this one" confirmed ([SkipNextAlarm]) and "Don't skip" ([UndoSkip]), with the system alarm, the
 * Skipped history row and the pre-alarm job. Monday 2027-03-08 22:00 UTC; a daily 07:00 alarm's next ring is Tuesday.
 */
class SkipNextAlarmTest {
    private val monday = Instant.parse("2027-03-08T22:00:00Z")
    private val tuesday7 = Instant.parse("2027-03-09T07:00:00Z")
    private val wednesday7 = Instant.parse("2027-03-10T07:00:00Z")
    private val tuesday = LocalDate(2027, 3, 9)

    private fun daily(
        id: String = "a",
        enabled: Boolean = true,
        offerSkip: Boolean = true,
        time: LocalTime = LocalTime(7, 0),
        repeatDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    ) = Alarm(
        id = id,
        time = time,
        repeatDays = repeatDays,
        enabled = enabled,
        offerSkip = offerSkip,
        requestCode = 1000,
        createdAt = Instant.parse("2027-03-01T00:00:00Z"),
        updatedAt = Instant.parse("2027-03-01T00:00:00Z"),
    )

    private fun SkipFixture.rows(): List<SessionHistoryRow> = history.rows.values.toList()

    @Test
    fun `skipping a repeating alarm stores the date, arms the next day and writes one Skipped row with no ring`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily())

            assertEquals(Outcome.Success(SkipResult.Skipped), f.skip("a"))

            assertEquals(tuesday, f.stored("a").skippedDate)
            assertTrue(f.stored("a").enabled)
            assertEquals(Call.Schedule("a", 1000, wednesday7), f.scheduler.calls.last())
            val row = f.rows().single()
            assertEquals("a", row.alarmId)
            assertEquals(tuesday7, row.scheduledAt)
            assertNull(row.firstRingAt)
            assertEquals(SessionOutcome.Skipped, row.outcome)
            assertEquals(0, row.snoozeCount)
            assertEquals(emptyList(), row.checkTypes)
            assertNull(row.timeToCompleteMs)
            assertFalse(row.fallbackUsed)
            assertEquals(monday, row.endedAt)
            assertFalse(isCounted(row), "a skip never counts toward rates or streaks")
            // The pre-alarm job follows: 2 h before Wednesday's ring.
            assertEquals(wednesday7 - 2.hours - monday, f.work.pending.getValue("a"))
        }

    @Test
    fun `skipping a one-time alarm switches it off and cancels it, with its Skipped row`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily(repeatDays = emptySet()))

            assertEquals(Outcome.Success(SkipResult.Skipped), f.skip("a"))

            assertFalse(f.stored("a").enabled)
            assertNull(f.stored("a").skippedDate)
            assertEquals(Call.Cancel(1000), f.scheduler.calls.last())
            assertEquals(tuesday7, f.rows().single().scheduledAt)
            assertEquals(PreAlarmWorkCall.Cancel("a"), f.work.calls.last())
        }

    @Test
    fun `skipping the same occurrence twice changes nothing and writes no second row`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily())
            f.skip("a")
            val calls = f.scheduler.calls.size

            assertEquals(Outcome.Success(SkipResult.AlreadySkipped), f.skip("a"))

            assertEquals(1, f.rows().size)
            assertEquals(calls, f.scheduler.calls.size)
        }

    @Test
    fun `a second skip of a one-time alarm finds it off and writes nothing`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily(repeatDays = emptySet()))
            f.skip("a")

            assertEquals(Outcome.Success(SkipResult.NothingToSkip), f.skip("a"))
            assertEquals(1, f.rows().size)
        }

    @Test
    fun `a skip during a session is refused and nothing changes`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily())
            f.session.value = SessionState.Ringing(ringSession())

            assertEquals(Outcome.Failure(DomainError.SessionActive), f.skip("a"))

            assertNull(f.stored("a").skippedDate)
            assertTrue(f.rows().isEmpty())
            assertTrue(f.scheduler.calls.isEmpty())
        }

    @Test
    fun `a failed row write changes nothing`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily())
            f.history.upsertFailure = DomainError.StorageFailure("disk")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk")), f.skip("a"))

            assertNull(f.stored("a").skippedDate)
            assertTrue(f.scheduler.calls.isEmpty())
        }

    @Test
    fun `a failed alarm write removes the row again, so nothing is half-applied`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily())
            f.alarms.failure = DomainError.StorageFailure("disk")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk")), f.skip("a"))

            assertNull(f.stored("a").skippedDate)
            assertTrue(f.rows().isEmpty())
            assertTrue(f.scheduler.calls.isEmpty())
        }

    @Test
    fun `a skipped Tuesday in Helsinki is still skipped after moving to London`() =
        runTest {
            val f = SkipFixture(monday, TimeZone.of("Europe/Helsinki"))
            f.store(daily())
            f.skip("a")
            assertEquals(tuesday, f.stored("a").skippedDate)

            f.zones.zone = TimeZone.of("Europe/London")

            // London is UTC in March: Tuesday 07:00 is passed over for Wednesday 07:00.
            assertEquals(wednesday7, nextRing(f.stored("a"), monday, f.zones.zone))
            assertEquals(Outcome.Success(SkipResult.AlreadySkipped), f.skip("a"))
            f.scheduling.rescheduleAll()
            assertEquals(Call.Schedule("a", 1000, wednesday7), f.scheduler.calls.last())
        }

    @Test
    fun `Don't skip clears the date, arms the skipped ring again, deletes its row and re-syncs the job`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily())
            f.skip("a")

            assertEquals(Outcome.Success(true), f.undo("a"))

            assertNull(f.stored("a").skippedDate)
            assertEquals(Call.Schedule("a", 1000, tuesday7), f.scheduler.calls.last())
            assertTrue(f.rows().isEmpty())
            assertEquals(tuesday7 - 2.hours - monday, f.work.pending.getValue("a"))
        }

    @Test
    fun `Don't skip still finds the row after the alarm moved to a later time that day`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily())
            f.skip("a")
            f.store(f.stored("a").copy(time = LocalTime(8, 0)))

            assertEquals(Outcome.Success(true), f.undo("a"))
            assertTrue(f.rows().isEmpty())
        }

    @Test
    fun `Don't skip with nothing skipped, or once the skipped day passed, changes nothing`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily())
            assertEquals(Outcome.Success(false), f.undo("a"))

            f.skip("a")
            f.clock.now = tuesday7 + 1.minutes
            assertEquals(Outcome.Success(false), f.undo("a"))
            assertEquals(tuesday, f.stored("a").skippedDate)
            assertEquals(1, f.rows().size, "the past skip stays logged")
        }

    @Test
    fun `Don't skip during a session is refused`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily())
            f.skip("a")
            f.session.value = SessionState.Ringing(ringSession())

            assertEquals(Outcome.Failure(DomainError.SessionActive), f.undo("a"))
            assertEquals(tuesday, f.stored("a").skippedDate)
        }

    @Test
    fun `a failed row delete changes nothing, and a failed alarm write puts the row back`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily())
            f.skip("a")

            f.history.deleteFailure = DomainError.StorageFailure("disk")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk")), f.undo("a"))
            assertEquals(tuesday, f.stored("a").skippedDate)
            assertEquals(1, f.rows().size)

            f.history.deleteFailure = null
            f.alarms.failure = DomainError.StorageFailure("disk")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk")), f.undo("a"))
            assertEquals(tuesday, f.stored("a").skippedDate)
            assertEquals(tuesday7, f.rows().single().scheduledAt)
        }

    @Test
    fun `an alarm whose stored Offer to skip is off (also while turning it on waits under the lock) is not skipped`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily(offerSkip = false))

            assertEquals(Outcome.Success(SkipResult.NothingToSkip), f.skip("a"))

            assertNull(f.stored("a").skippedDate)
            assertTrue(f.rows().isEmpty())
        }

    @Test
    fun `a skip of an occurrence that is no longer the next one skips nothing`() =
        runTest {
            val f = SkipFixture(monday)
            f.store(daily())

            assertEquals(Outcome.Success(SkipResult.NothingToSkip), f.skip("a", wednesday7))
            assertNull(f.stored("a").skippedDate)
            assertTrue(f.rows().isEmpty())

            assertEquals(Outcome.Success(SkipResult.Skipped), f.skip("a", tuesday7))
            assertEquals(tuesday, f.stored("a").skippedDate)
        }
}
