package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** AD-18: the one writer of session history, one row per session id, safe to replay. */
class SessionRecorderTest {
    private val history = InMemoryHistory()
    private val recorder = SessionRecorder(history)

    // The alarm scheduled at 06:00 first rings at 06:00:05, 5 s after boot 1 started.
    private val firstRingAt: Instant = SCHEDULED_AT + 5.seconds
    private val firstRing = TimeSnapshot(firstRingAt.toEpochMilliseconds(), elapsedMillis = 5_000, bootCount = 1)
    private val started = ringSession().copy(firstRing = firstRing)

    /** [started], ended [after] its first ring on the same boot. */
    private fun endedAfter(after: Duration): SessionData = started.copy(ended = firstRing.plus(after))

    private fun startRow(session: SessionData = started) =
        SessionHistoryRow(
            sessionId = SESSION_ID,
            alarmId = "alarm-1",
            scheduledAt = SCHEDULED_AT,
            firstRingAt = session.firstRingAt ?: SCHEDULED_AT,
            endedAt = null,
            snoozeCount = 0,
            checkTypes = listOf("Placeholder"),
            timeToCompleteMs = null,
            fallbackUsed = false,
            directBoot = session.startedBeforeUnlock,
            outcome = null,
        )

    @Test
    fun `the start row holds the alarm, the scheduled time, the first ring time, the boot state and no outcome`() =
        runTest {
            val locked = started.copy(startedBeforeUnlock = true, beforeFirstUnlock = true)

            assertEquals(Outcome.Success(Unit), recorder.recordStart(locked))

            assertEquals(startRow(locked), history.rows.getValue(SESSION_ID))
            assertEquals(true, history.rows.getValue(SESSION_ID).directBoot)
        }

    @Test
    fun `a start without a first ring time falls back to the scheduled time`() =
        runTest {
            recorder.recordStart(ringSession())

            assertEquals(SCHEDULED_AT, history.rows.getValue(SESSION_ID).firstRingAt)
        }

    @Test
    fun `a replayed start keeps an end already written`() =
        runTest {
            recorder.recordEnd(endedAfter(2.minutes + 55.seconds), SessionEnd.Completed, SCHEDULED_AT + 3.minutes)
            val ended = history.rows.getValue(SESSION_ID)

            assertEquals(Outcome.Success(Unit), recorder.recordStart(started))

            assertEquals(ended, history.rows.getValue(SESSION_ID))
            assertEquals(SessionOutcome.OnTime, ended.outcome)
        }

    @Test
    fun `completed with no snooze at 06 03 is OnTime with 175 s to complete`() =
        runTest {
            recorder.recordStart(started)

            val done = endedAfter(2.minutes + 55.seconds)
            assertEquals(Outcome.Success(Unit), recorder.recordEnd(done, SessionEnd.Completed, SCHEDULED_AT + 3.minutes))

            assertEquals(
                startRow().copy(endedAt = SCHEDULED_AT + 3.minutes, timeToCompleteMs = 175_000, outcome = SessionOutcome.OnTime),
                history.rows.getValue(SESSION_ID),
            )
        }

    @Test
    fun `a write retried or restored hours later keeps the real end time and time to complete`() =
        runTest {
            recorder.recordEnd(endedAfter(2.minutes + 55.seconds), SessionEnd.Completed, SCHEDULED_AT + 5.hours)

            val row = history.rows.getValue(SESSION_ID)
            assertEquals(SCHEDULED_AT + 3.minutes, row.endedAt)
            assertEquals(175_000L, row.timeToCompleteMs)
        }

    @Test
    fun `a wall clock jump during the session does not change the time to complete`() =
        runTest {
            // The wall clock jumped an hour ahead mid-session; monotonic time says 175 s passed.
            val jumped =
                TimeSnapshot(firstRing.wallMillis + 1.hours.inWholeMilliseconds + 175_000, firstRing.elapsedMillis + 175_000, bootCount = 1)

            recorder.recordEnd(started.copy(ended = jumped), SessionEnd.Completed, SCHEDULED_AT)

            assertEquals(175_000L, history.rows.getValue(SESSION_ID).timeToCompleteMs)
        }

    @Test
    fun `across a reboot the time to complete is measured in wall time`() =
        runTest {
            val afterReboot = TimeSnapshot(firstRing.wallMillis + 10.minutes.inWholeMilliseconds, elapsedMillis = 30_000, bootCount = 2)

            recorder.recordEnd(started.copy(ended = afterReboot), SessionEnd.Completed, SCHEDULED_AT)

            assertEquals(10.minutes.inWholeMilliseconds, history.rows.getValue(SESSION_ID).timeToCompleteMs)
        }

    @Test
    fun `across a reboot with the same boot count (no BOOT_COUNT) the time to complete is measured in wall time`() =
        runTest {
            // The count stays the same, but the elapsed clock went back below the first ring's: a reboot.
            val ringingLate = firstRing.copy(elapsedMillis = 600_000)
            val afterReboot = TimeSnapshot(firstRing.wallMillis + 10.minutes.inWholeMilliseconds, elapsedMillis = 30_000, bootCount = 1)

            recorder.recordEnd(started.copy(firstRing = ringingLate, ended = afterReboot), SessionEnd.Completed, SCHEDULED_AT)

            assertEquals(10.minutes.inWholeMilliseconds, history.rows.getValue(SESSION_ID).timeToCompleteMs)
        }

    @Test
    fun `without BOOT_COUNT the time to complete is wall time, also across a reboot to a higher uptime`() =
        runTest {
            // Rebooted, and the end came 60 s after the new boot: higher than the first ring's 5 s, so it looks like
            // the same boot. Elapsed time would say 55 s; the wall clock says 10 minutes.
            val noCount = firstRing.copy(bootCount = -1)
            val afterReboot = TimeSnapshot(firstRing.wallMillis + 10.minutes.inWholeMilliseconds, elapsedMillis = 60_000, bootCount = -1)

            recorder.recordEnd(started.copy(firstRing = noCount, ended = afterReboot), SessionEnd.Completed, SCHEDULED_AT)
            assertEquals(10.minutes.inWholeMilliseconds, history.rows.getValue(SESSION_ID).timeToCompleteMs)

            // A count missing on only one side (a row from before the update) is wall time too, never negative.
            val backwards = afterReboot.copy(wallMillis = firstRing.wallMillis - 1_000)
            recorder.recordEnd(started.copy(ended = backwards.copy(bootCount = -7)), SessionEnd.Completed, SCHEDULED_AT)
            assertEquals(0L, history.rows.getValue(SESSION_ID).timeToCompleteMs)
        }

    @Test
    fun `completed after 2 granted snoozes is Snoozed with a snooze count of 2`() =
        runTest {
            recorder.recordEnd(endedAfter(30.minutes).copy(snoozesGranted = 2), SessionEnd.Completed, SCHEDULED_AT)

            val row = history.rows.getValue(SESSION_ID)
            assertEquals(SessionOutcome.Snoozed, row.outcome)
            assertEquals(2, row.snoozeCount)
        }

    @Test
    fun `missed after the timeout is Missed with no time to complete`() =
        runTest {
            recorder.recordEnd(endedAfter(30.minutes), SessionEnd.Missed, SCHEDULED_AT)

            val row = history.rows.getValue(SESSION_ID)
            assertEquals(SessionOutcome.Missed, row.outcome)
            assertNull(row.timeToCompleteMs)
            assertEquals(firstRingAt + 30.minutes, row.endedAt)
        }

    @Test
    fun `a test session is Test whatever its ending`() =
        runTest {
            val test = endedAfter(3.minutes).copy(config = testConfig(testMode = true), snoozesGranted = 1)
            SessionEnd.entries.forEach { end ->
                recorder.recordEnd(test.copy(sessionId = end.name), end, SCHEDULED_AT)
                assertEquals(SessionOutcome.Test, history.rows.getValue(end.name).outcome, end.name)
            }
        }

    @Test
    fun `the outcome mapping covers every ending`() {
        val test = started.copy(config = testConfig(testMode = true))
        assertEquals(SessionOutcome.Test, SessionRecorder.outcomeOf(test, SessionEnd.Completed))
        assertEquals(SessionOutcome.Test, SessionRecorder.outcomeOf(test, SessionEnd.Missed))
        assertEquals(SessionOutcome.OnTime, SessionRecorder.outcomeOf(started, SessionEnd.Completed))
        assertEquals(SessionOutcome.Snoozed, SessionRecorder.outcomeOf(started.copy(snoozesGranted = 1), SessionEnd.Completed))
        assertEquals(SessionOutcome.Missed, SessionRecorder.outcomeOf(started.copy(snoozesGranted = 3), SessionEnd.Missed))
    }

    @Test
    fun `the same end write repeated later leaves exactly one row with identical values`() =
        runTest {
            recorder.recordStart(started)
            val done = endedAfter(3.minutes)
            recorder.recordEnd(done, SessionEnd.Completed, SCHEDULED_AT + 3.minutes)
            val first = history.rows.getValue(SESSION_ID)

            // The process died before Recorded; the restored engine writes again 10 minutes later.
            assertEquals(Outcome.Success(Unit), recorder.recordEnd(done, SessionEnd.Completed, SCHEDULED_AT + 13.minutes))

            assertEquals(mapOf(SESSION_ID to first), history.rows.toMap())
            assertEquals(first, history.upserts.last())
        }

    @Test
    fun `a session stored before Story 1 13 uses the stored start row, then the scheduled time, and keeps a stored end time`() =
        runTest {
            recorder.recordStart(started)
            recorder.recordEnd(ringSession(), SessionEnd.Completed, SCHEDULED_AT + 3.minutes)
            assertEquals(firstRingAt, history.rows.getValue(SESSION_ID).firstRingAt)
            assertEquals(175_000L, history.rows.getValue(SESSION_ID).timeToCompleteMs)
            recorder.recordEnd(ringSession(), SessionEnd.Completed, SCHEDULED_AT + 13.minutes)
            assertEquals(SCHEDULED_AT + 3.minutes, history.rows.getValue(SESSION_ID).endedAt, "the stored end time is kept")

            history.rows.clear()
            recorder.recordEnd(ringSession(), SessionEnd.Completed, SCHEDULED_AT + 3.minutes)
            assertEquals(SCHEDULED_AT, history.rows.getValue(SESSION_ID).firstRingAt)
            assertEquals(180_000L, history.rows.getValue(SESSION_ID).timeToCompleteMs)
        }

    @Test
    fun `the end row keeps the start boot state after the phone was unlocked, even with no start row`() =
        runTest {
            val unlocked = endedAfter(3.minutes).copy(startedBeforeUnlock = true, beforeFirstUnlock = false)

            recorder.recordEnd(unlocked, SessionEnd.Completed, SCHEDULED_AT)

            assertEquals(true, history.rows.getValue(SESSION_ID).directBoot)
        }

    @Test
    fun `the end row has the check types of the plan that was run and whether the fallback replaced it`() =
        runTest {
            val run = CheckRun(FALLBACK_PLAN, SEEDS, step = 3, fallbackUsed = true)

            recorder.recordEnd(endedAfter(3.minutes).copy(checkRun = run), SessionEnd.Completed, SCHEDULED_AT)

            val row = history.rows.getValue(SESSION_ID)
            assertEquals(List(3) { "Placeholder" }, row.checkTypes)
            assertTrue(row.fallbackUsed)
        }

    @Test
    fun `without an end snapshot a wall clock set back gives a time to complete of zero, never negative`() =
        runTest {
            recorder.recordEnd(started, SessionEnd.Completed, SCHEDULED_AT - 1.minutes)

            assertEquals(0L, history.rows.getValue(SESSION_ID).timeToCompleteMs)
        }

    @Test
    fun `a failed read writes nothing and a failed write is returned`() =
        runTest {
            val locked = Outcome.Failure(DomainError.StorageFailure("locked"))
            history.findFailure = DomainError.StorageFailure("locked")
            assertEquals(locked, recorder.recordEnd(started, SessionEnd.Missed, SCHEDULED_AT))
            assertEquals(locked, recorder.recordStart(started))
            assertTrue(history.upserts.isEmpty())

            val full = Outcome.Failure(DomainError.StorageFailure("disk full"))
            history.findFailure = null
            history.upsertFailure = DomainError.StorageFailure("disk full")
            assertEquals(full, recorder.recordEnd(started, SessionEnd.Missed, SCHEDULED_AT))
            assertEquals(full, recorder.recordStart(started))
        }

    @Test
    fun `every check step type name is unique, non-empty and has no comma`() {
        // Exhaustive when: a new CheckStep subtype fails to compile here until it is added to the list.
        val every: List<CheckStep> =
            listOf<CheckStep>(CheckStep.Placeholder).onEach { step ->
                when (step) {
                    CheckStep.Placeholder -> Unit
                }
            }
        val names = every.map { it.typeName }

        assertEquals(names.size, names.toSet().size, "unique: $names")
        assertTrue(names.all { it.isNotEmpty() && ',' !in it }, "non-empty and comma-free: $names")
        assertEquals("Placeholder", CheckStep.Placeholder.typeName)
    }
}
