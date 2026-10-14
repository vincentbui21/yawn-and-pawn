package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.config.CommitmentAction
import com.yawnandpawn.app.core.config.CommitmentEvent
import com.yawnandpawn.app.core.config.CommitmentLog
import com.yawnandpawn.app.core.config.InMemoryCommitmentEvents
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.ringSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 4.6: turning an alarm off or deleting it inside its lock window writes a commitment event in the same guarded
 * write (PRD §6.2), and a delete is always logged as `AlarmDeleted`, so no caller can skip either. A failed alarm write
 * leaves the alarm armed and writes no event.
 */
class CommitmentEventsOnAlarmChangeTest {
    /** The 07:30 UTC ring the alarm gives up. */
    private val sevenThirty = Instant.parse("2027-03-09T07:30:00Z")
    private val clock = TestClock(sevenThirty - 7.hours - 59.minutes)
    private val repository = InMemoryAlarms()
    private val lock = AlarmWriteLock()
    private val scheduler = RecordingScheduler()
    private val zone = TestZone(TimeZone.UTC)
    private val scheduling = AlarmScheduling(repository, scheduler, clock, zone, lock, RecordingLogger())
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val sessionLock = SessionLockGuard(sessionState, MutableStateFlow(true), MutableStateFlow(false))
    private val checkConfigs = InMemoryCheckConfigs(repository)
    private val events = InMemoryCommitmentEvents()
    private val logger = RecordingLogger()
    private val commitments = CommitmentLog(events, SequentialIds(), zone)
    private val setEnabled = SetAlarmEnabled(repository, clock, lock, scheduling, sessionLock, commitments, logger)
    private val delete = DeleteAlarm(repository, lock, scheduling, sessionLock, checkConfigs, clock, commitments, logger)

    private val alarm =
        Alarm(
            id = "a",
            time = LocalTime(7, 30),
            requestCode = 1_001,
            createdAt = Instant.parse("2027-03-01T10:00:00Z"),
            updatedAt = Instant.parse("2027-03-01T10:00:00Z"),
        )

    private fun store(alarm: Alarm = this.alarm) {
        repository.alarms.value += alarm.id to alarm
    }

    @Test
    fun `turning an alarm off at 7 h 59 min writes a Disabled event for the ring it gave up`() =
        runTest {
            store()
            assertIs<Outcome.Success<Alarm>>(setEnabled("a", enabled = false))

            assertEquals(listOf(CommitmentEvent("id-1", "a", sevenThirty, CommitmentAction.Disabled, clock.now)), events.events)
            assertEquals(
                false,
                repository.alarms.value
                    .getValue("a")
                    .enabled,
            )
        }

    @Test
    fun `turning an alarm off at 8 h 01 min writes no event`() =
        runTest {
            clock.now = sevenThirty - 8.hours - 1.minutes
            store()
            assertIs<Outcome.Success<Alarm>>(setEnabled("a", enabled = false))

            assertEquals(emptyList(), events.events)
        }

    @Test
    fun `turning an alarm on, or off when it was already off, writes no event`() =
        runTest {
            store(alarm.copy(enabled = false))
            setEnabled("a", enabled = false)
            setEnabled("a", enabled = true)

            assertEquals(emptyList(), events.events)
        }

    @Test
    fun `a failed turn-off keeps the alarm on and armed and writes no event`() =
        runTest {
            store()
            repository.failure = DomainError.StorageFailure("disk full")

            assertIs<Outcome.Failure<DomainError>>(setEnabled("a", enabled = false))

            assertEquals(emptyList(), events.events)
            assertTrue(
                repository.alarms.value
                    .getValue("a")
                    .enabled,
            )
            assertTrue(scheduler.calls.none { it is Call.Cancel })
        }

    @Test
    fun `a failed event write is logged and the alarm stays off`() =
        runTest {
            store()
            events.failure = DomainError.StorageFailure("disk full")

            assertIs<Outcome.Success<Alarm>>(setEnabled("a", enabled = false))

            assertEquals(
                false,
                repository.alarms.value
                    .getValue("a")
                    .enabled,
            )
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed.of("record commitment event", DomainError.StorageFailure("disk full"))),
                logger.events,
            )
        }

    @Test
    fun `deleting inside the window logs AlarmDeleted and writes a Deleted event`() =
        runTest {
            store()
            assertIs<Outcome.Success<Unit>>(delete("a"))

            assertEquals(listOf<LogEvent>(LogEvent.AlarmDeleted("a", clock.now)), logger.events)
            assertEquals(listOf(CommitmentEvent("id-1", "a", sevenThirty, CommitmentAction.Deleted, clock.now)), events.events)
        }

    @Test
    fun `deleting outside the window logs AlarmDeleted only`() =
        runTest {
            clock.now = sevenThirty - 8.hours - 1.minutes
            store()
            delete("a")

            assertEquals(listOf<LogEvent>(LogEvent.AlarmDeleted("a", clock.now)), logger.events)
            assertEquals(emptyList(), events.events)
        }

    @Test
    fun `a failed delete keeps the alarm, logs no deletion and writes no event`() =
        runTest {
            store()
            checkConfigs.failure = DomainError.StorageFailure("disk full")

            assertIs<Outcome.Failure<DomainError>>(delete("a"))

            assertTrue("a" in repository.alarms.value)
            assertEquals(emptyList(), logger.events)
            assertEquals(emptyList(), events.events)
        }

    @Test
    fun `during a session nothing is turned off, deleted or logged`() =
        runTest {
            store()
            sessionState.value = SessionState.Ringing(ringSession())

            assertEquals(Outcome.Failure(DomainError.SessionActive), setEnabled("a", enabled = false))
            assertEquals(Outcome.Failure(DomainError.SessionActive), delete("a"))
            assertEquals(emptyList(), events.events)
            assertEquals(emptyList(), logger.events)
        }
}
