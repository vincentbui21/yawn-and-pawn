package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.Call
import com.yawnandpawn.app.core.alarm.RecordingScheduler
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.time.Deadline
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Story 2.1: the session slot armed from `runtime.db` without the engine. */
class SessionSlotRearmTest {
    private val time = EngineTime()
    private val store = InMemorySessionStore()
    private val scheduler = RecordingScheduler()
    private val logger = EngineLogger()
    private val rearm = SessionSlotRearm(store, scheduler, time.clock, time.monotonicClock, time.bootCounter, logger)

    private val session = ringSession()
    private val heartbeat = Deadline.after(T0, SessionReducer.HEARTBEAT)
    private val immediately = Deadline.after(T0, 1.seconds)

    /** T0 as an instant: the first refusal of a payload-less retry. */
    private val now = Instant.fromEpochMilliseconds(T0.wallMillis)

    private suspend fun stored(state: SessionState) = store.commit(state)

    /** An alarm scheduled [ago] before now. */
    private fun alarm(ago: Duration) = AlarmFired("alarm-b", Instant.fromEpochMilliseconds(T0.wallMillis - ago.inWholeMilliseconds))

    @Test
    fun `after a system event a ringing, quiet or loud session gets the slot at once, logged with its session id`() =
        runTest {
            ringStates(session).forEach { state ->
                scheduler.calls.clear()
                logger.events.clear()
                stored(state)

                assertEquals(immediately, rearm.afterSystemEvent(), state.kind)

                assertEquals(listOf<Call>(Call.Slot(immediately)), scheduler.calls, state.kind)
                assertEquals(
                    listOf<LogEvent>(LogEvent.SessionSlotRearmed(SessionSlotRearm.SYSTEM_EVENT, 1_000, session.sessionId, alarmId = null)),
                    logger.events,
                )
            }
        }

    @Test
    fun `after a system event a snoozed session gets the slot at its snooze end`() =
        runTest {
            val snoozed = SessionState.Snoozed(snoozedSession())
            stored(snoozed)

            assertEquals(snoozed.session.snoozeEnd, rearm.afterSystemEvent())

            assertEquals(listOf<Call>(Call.Slot(Deadline.after(T0, 9.minutes))), scheduler.calls)
        }

    @Test
    fun `after a system event a snoozed session without a snooze end gets the slot at once`() =
        runTest {
            stored(SessionState.Snoozed(snoozedSession().copy(snoozeEnd = null)))

            assertEquals(immediately, rearm.afterSystemEvent())
        }

    @Test
    fun `after a system event nothing stored, an ended session or an unreadable row arms nothing`() =
        runTest {
            assertNull(rearm.afterSystemEvent(), "nothing stored")
            stored(SessionState.Completed(session.noTimers()))
            assertNull(rearm.afterSystemEvent(), "completed")
            stored(SessionState.Missed(session.noTimers()))
            assertNull(rearm.afterSystemEvent(), "missed")
            store.row = "{not json"
            assertNull(rearm.afterSystemEvent(), "unreadable")

            assertEquals(emptyList(), scheduler.calls)
            assertEquals(emptyList(), logger.events)
        }

    @Test
    fun `after a system event a store that cannot be read is logged and arms nothing`() =
        runTest {
            store.loadFailure = DomainError.StorageFailure("disk I/O error")

            assertNull(rearm.afterSystemEvent())

            assertEquals(emptyList(), scheduler.calls)
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed("read session for slot", "storage failure: disk I/O error")),
                logger.events,
            )
        }

    @Test
    fun `a slot that cannot be armed is logged and returns nothing`() =
        runTest {
            stored(SessionState.Ringing(session))
            scheduler.failure = DomainError.ExactAlarmNotPermitted

            assertNull(rearm.afterSystemEvent())

            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("arm session slot", "exact alarms not permitted")), logger.events)
        }

    @Test
    fun `a refused start for an alarm arms the slot one heartbeat later carrying the alarm, with or without a session`() =
        runTest {
            val fired = alarm(ago = 2.minutes)

            assertEquals(heartbeat, rearm.afterRefusedStart(fired), "no session")
            stored(SessionState.Snoozed(snoozedSession()))
            assertEquals(heartbeat, rearm.afterRefusedStart(fired), "snoozed: the alarm merges at the next slot")

            // The retry window counts from the alarm's scheduled time, and the slot carries it.
            val since = fired.scheduledAt
            assertEquals(listOf<Call>(Call.Slot(heartbeat, fired, since), Call.Slot(heartbeat, fired, since)), scheduler.calls)
            assertEquals(
                LogEvent.SessionSlotRearmed(SessionSlotRearm.REFUSED_START, 60_000, sessionId = null, alarmId = "alarm-b"),
                logger.events.first(),
            )
        }

    @Test
    fun `a refused start for an alarm 30 minutes late drops it and falls back to the stored session`() =
        runTest {
            val stale = alarm(ago = 30.minutes)

            assertNull(rearm.afterRefusedStart(stale), "no session: nothing")
            stored(SessionState.Loud(session))
            assertEquals(heartbeat, rearm.afterRefusedStart(stale))

            assertEquals(listOf<Call>(Call.Slot(heartbeat, retrySince = now)), scheduler.calls)
        }

    @Test
    fun `a refused slot or restore start re-arms a ringing session one heartbeat later and a snooze at its end`() =
        runTest {
            stored(SessionState.Grace(session.copy(graceEnd = Deadline.after(T0, 20.seconds))))
            assertEquals(heartbeat, rearm.afterRefusedStart(null))

            val snoozed = SessionState.Snoozed(snoozedSession())
            stored(snoozed)
            assertEquals(snoozed.session.snoozeEnd, rearm.afterRefusedStart(null), "a snooze still running")

            time.advanceBy(10.minutes)
            assertEquals(Deadline.after(time.now, SessionReducer.HEARTBEAT), rearm.afterRefusedStart(null), "a snooze that is over")
        }

    @Test
    fun `a refused start with no session, an ended one or no alarm arms nothing`() =
        runTest {
            assertNull(rearm.afterRefusedStart(null))
            stored(SessionState.Completed(session.noTimers()))
            assertNull(rearm.afterRefusedStart(null))

            assertEquals(emptyList(), scheduler.calls)
        }

    @Test
    fun `a refused start whose store cannot be read still arms one heartbeat later`() =
        runTest {
            store.loadFailure = DomainError.StorageFailure("locked")

            assertEquals(heartbeat, rearm.afterRefusedStart(null))

            assertTrue(logger.events.first() is LogEvent.OperationFailed)
            assertEquals(listOf<Call>(Call.Slot(heartbeat, retrySince = now)), scheduler.calls)
        }

    @Test
    fun `a refused slot or restore start re-arms a stored Ringing session and arms nothing for a Missed one or an unreadable row`() =
        runTest {
            stored(SessionState.Ringing(session))
            assertEquals(heartbeat, rearm.afterRefusedStart(null), "ringing")
            stored(SessionState.Missed(session.noTimers()))
            assertNull(rearm.afterRefusedStart(null), "missed")
            store.row = "{not json"
            assertNull(rearm.afterRefusedStart(null), "unreadable row")

            assertEquals(listOf<Call>(Call.Slot(heartbeat, retrySince = now)), scheduler.calls)
        }

    @Test
    fun `a refused start whose slot cannot be armed is logged and returns nothing`() =
        runTest {
            scheduler.failure = DomainError.ExactAlarmNotPermitted

            assertNull(rearm.afterRefusedStart(alarm(ago = 1.minutes)))

            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("arm session slot", "exact alarms not permitted")), logger.events)
        }

    @Test
    fun `a payload-less re-arm keeps the alarm the armed slot still carries`() =
        runTest {
            val carried = alarm(ago = 1.minutes)
            scheduler.slotAlarm = carried
            stored(SessionState.Ringing(session))

            assertEquals(immediately, rearm.afterSystemEvent(), "ringing")
            stored(SessionState.Snoozed(snoozedSession()))
            assertEquals(immediately, rearm.afterSystemEvent(), "snoozed: the carried alarm comes at once, not at the snooze end")
            assertEquals(heartbeat, rearm.afterRefusedStart(null), "refused restore")

            assertEquals(
                listOf<Call>(
                    Call.Slot(immediately, carried),
                    Call.Slot(immediately, carried),
                    Call.Slot(heartbeat, carried, carried.scheduledAt),
                ),
                scheduler.calls,
            )
        }

    @Test
    fun `a carried alarm 30 minutes past is not kept`() =
        runTest {
            scheduler.slotAlarm = alarm(ago = 30.minutes)
            stored(SessionState.Ringing(session))

            rearm.afterSystemEvent()
            rearm.afterRefusedStart(null)

            assertEquals(listOf<Call>(Call.Slot(immediately), Call.Slot(heartbeat, retrySince = now)), scheduler.calls)
        }

    @Test
    fun `retries stop, logged, once starts have been refused for 30 minutes`() =
        runTest {
            stored(SessionState.Ringing(session))
            val firstRefusal = Instant.fromEpochMilliseconds(T0.wallMillis - 29.minutes.inWholeMilliseconds)
            assertEquals(heartbeat, rearm.afterRefusedStart(null, firstRefusal), "still within the window")
            assertEquals(
                listOf<Call>(Call.Slot(heartbeat, retrySince = firstRefusal)),
                scheduler.calls,
                "the slot carries the first refusal",
            )

            time.advanceBy(1.minutes)
            assertNull(rearm.afterRefusedStart(null, firstRefusal))

            assertEquals(1, scheduler.calls.size, "nothing armed any more")
            assertEquals(LogEvent.OperationFailed("re-arm session slot", "starts refused for 30m; giving up"), logger.events.last())
        }
}
