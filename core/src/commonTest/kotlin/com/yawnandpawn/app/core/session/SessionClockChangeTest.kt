package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Call
import com.yawnandpawn.app.core.alarm.RecordingScheduler
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.time.Deadline
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Story 2.2 (FR-SES-5, FR-SES-10, AD-3): a wall-clock jump never ends, skips or shortens a session, because every
 * session timer counts monotonic time on the same boot (a time-zone change moves neither clock); after a reboot the
 * timers compare wall time and a restored session still rings.
 */
class SessionClockChangeTest {
    /** One phone: its time, its runtime.db and app.db, and the engine of the running process. */
    private class Phone {
        val time = EngineTime()
        val store = InMemorySessionStore()
        val runner = RecordingRunner()
        val history = InMemoryHistory()
        var engine = newProcess()

        /** A new process over the same storage, as after a kill or a reboot. */
        fun newProcess(): SessionEngine =
            SessionEngine(
                productionReducer(),
                store,
                runner,
                SessionRecorder(history),
                time.clock,
                time.monotonicClock,
                time.bootCounter,
                EngineLogger(),
            )

        suspend fun ring(): SessionState.Ringing = assertIs(dispatch(ALARM_FIRED))

        suspend fun dispatch(event: SessionEvent): SessionState = engine.dispatch(event).state()

        /** Real time passes ([duration] on both clocks) and the engine ticks. */
        suspend fun pass(duration: Duration): SessionState {
            time.advanceBy(duration)
            return engine.tick().state()
        }

        /** The phone was off for [off]: a new process restores the stored session. */
        suspend fun rebootAndRestore(
            off: Duration,
            sameBootCount: Boolean = false,
        ): SessionState {
            time.reboot(off, bootCount = if (sameBootCount) time.now.bootCount else time.now.bootCount + 1)
            engine = newProcess()
            return engine.restore().state()
        }
    }

    /** The jumps every timer must ignore: the wall clock set 2 h forward and 2 h back. */
    private val jumps = listOf(2.hours, (-2).hours)

    @Test
    fun `in Ringing a 2 h wall jump either way keeps the ring, and the timeout fires 30 monotonic minutes after the last interaction`() =
        runTest {
            jumps.forEach { jump ->
                val phone = Phone()
                phone.ring()
                phone.time.advanceBy(5.minutes)
                phone.dispatch(SessionEvent.UserInteracted)

                phone.time.jumpWall(jump)

                assertIs<SessionState.Ringing>(phone.pass(29.minutes + 59.seconds), "still ringing after $jump")
                assertEquals(SessionState.Idle, phone.pass(1.seconds), "missed 30 monotonic minutes after the interaction ($jump)")
                assertEquals(
                    SessionOutcome.Missed,
                    phone.history.rows
                        .getValue(SESSION_ID)
                        .outcome,
                )
            }
        }

    @Test
    fun `in Loud a wall jump keeps the ring and the timeout still counts 30 monotonic minutes from I'm up`() =
        runTest {
            jumps.forEach { jump ->
                val phone = Phone()
                phone.ring()
                phone.dispatch(SessionEvent.ImUpTapped)
                assertIs<SessionState.Loud>(phone.pass(20.seconds))

                phone.time.jumpWall(jump)

                assertIs<SessionState.Loud>(phone.pass(29.minutes + 39.seconds), "still loud after $jump")
                assertEquals(SessionState.Idle, phone.pass(1.seconds), "missed ($jump)")
                assertEquals(
                    SessionOutcome.Missed,
                    phone.history.rows
                        .getValue(SESSION_ID)
                        .outcome,
                )
            }
        }

    @Test
    fun `in Grace a wall jump neither ends nor stretches the grace window - it ends after the same monotonic seconds`() =
        runTest {
            jumps.forEach { jump ->
                val phone = Phone()
                phone.ring()
                phone.dispatch(SessionEvent.ImUpTapped)
                phone.time.advanceBy(5.seconds)

                phone.time.jumpWall(jump)

                assertIs<SessionState.Grace>(phone.pass(Duration.ZERO), "a jump does not end it ($jump)")
                assertIs<SessionState.Grace>(phone.pass(14.seconds), "not before 20 s ($jump)")
                assertIs<SessionState.Loud>(phone.pass(1.seconds), "ends at 20 monotonic seconds ($jump)")
            }
        }

    @Test
    fun `a 9-minute snooze with a 1 h jump either way at minute 3 re-rings 6 monotonic minutes later`() =
        runTest {
            listOf(1.hours, (-1).hours).forEach { jump ->
                val phone = Phone()
                val snoozeEnd = Deadline.after(phone.time.now, 9.minutes)
                phone.store.commit(SessionState.Snoozed(snoozedSession().copy(snoozeEnd = snoozeEnd)))
                phone.engine.restore()
                phone.time.advanceBy(3.minutes)

                phone.time.jumpWall(jump)
                // A forward jump makes the wall-clock slot fire early: the snooze goes on and its slot is armed again.
                assertIs<SessionState.Snoozed>(phone.dispatch(SessionEvent.SlotFired), "not over after a $jump jump")
                assertEquals(EntryEffect.SlotArmedAt(snoozeEnd), phone.runner.entry.last())
                assertEquals(6.minutes, snoozeEnd.remaining(phone.time.now), "6 monotonic minutes left ($jump)")

                phone.time.advanceBy(6.minutes - 1.seconds)
                assertIs<SessionState.Snoozed>(phone.dispatch(SessionEvent.SlotFired))
                phone.time.advanceBy(1.seconds)
                val next = assertIs<SessionState.Ringing>(phone.dispatch(SessionEvent.SlotFired), "re-rings ($jump)")
                assertEquals(2, next.session.ringIndex)
                assertEquals(1, next.session.snoozesGranted)
            }
        }

    @Test
    fun `an alarm due during the session after a clock change still merges into it`() =
        runTest {
            val phone = Phone()
            phone.ring()
            phone.time.jumpWall(2.hours)

            val merged = SessionEvent.OverlapAlarmFired("alarm-2", Instant.fromEpochMilliseconds(phone.time.now.wallMillis))
            val state = assertIs<SessionState.Ringing>(phone.dispatch(merged))

            assertEquals(SESSION_ID, state.session.sessionId)
            assertTrue(phone.runner.oneShot.any { it is SessionEffect.RecordMergedOccurrence && it.alarmId == "alarm-2" })
            assertTrue(SessionEffect.RescheduleAlarm("alarm-2") in phone.runner.oneShot)
        }

    @Test
    fun `a ringing session restored 10 hours after the phone went off still rings, with a fresh 30-minute deadline`() =
        runTest {
            val phone = Phone()
            val ringing = phone.ring()

            val restored = assertIs<SessionState.Ringing>(phone.rebootAndRestore(off = 10.hours))

            assertEquals(ringing.session.sessionId, restored.session.sessionId)
            assertEquals(Deadline.after(phone.time.now, 30.minutes), restored.session.interactionDeadline)
            assertIs<SessionState.Ringing>(phone.pass(29.minutes), "not missed for the time the phone was off")
        }

    @Test
    fun `a snooze that ended while the phone was off rings at once on restore as the next ring`() =
        runTest {
            val phone = Phone()
            phone.store.commit(SessionState.Snoozed(snoozedSession()))

            val next = assertIs<SessionState.Ringing>(phone.rebootAndRestore(off = 2.hours))

            assertEquals(2, next.session.ringIndex)
            assertEquals(1, next.session.snoozesGranted)
        }

    @Test
    fun `a snooze still ahead after a reboot waits for its end on wall time`() =
        runTest {
            val phone = Phone()
            phone.store.commit(SessionState.Snoozed(snoozedSession()))

            assertIs<SessionState.Snoozed>(phone.rebootAndRestore(off = 1.minutes))
            phone.time.advanceBy(7.minutes)
            assertIs<SessionState.Snoozed>(phone.dispatch(SessionEvent.SlotFired), "8 wall minutes into the 9-minute snooze")
            phone.time.advanceBy(1.minutes)
            assertIs<SessionState.Ringing>(phone.dispatch(SessionEvent.SlotFired), "9 wall minutes: the snooze is over")
        }

    @Test
    fun `without BOOT_COUNT a reboot is told by the elapsed clock going back, and a wall jump on the same boot changes nothing`() =
        runTest {
            val phone = Phone()
            val snoozeEnd = Deadline.after(phone.time.now, 9.minutes)
            phone.store.commit(SessionState.Snoozed(snoozedSession().copy(snoozeEnd = snoozeEnd)))
            phone.time.advanceBy(2.minutes)
            phone.time.jumpWall(1.hours)
            assertEquals(7.minutes, snoozeEnd.remaining(phone.time.now), "same boot: monotonic")

            // The device reports the same boot count every boot; only the elapsed clock tells the reboot.
            val next = assertIs<SessionState.Ringing>(phone.rebootAndRestore(off = 3.hours, sameBootCount = true))

            assertFalse(snoozeEnd.sameBoot(phone.time.now))
            assertEquals(2, next.session.ringIndex, "the snooze ended while the phone was off")
        }

    @Test
    fun `after a system event a snooze slot is its stored deadline, and one that passed while the phone was off fires at once`() =
        runTest {
            val phone = Phone()
            val scheduler = RecordingScheduler()
            val time = phone.time
            val rearm = SessionSlotRearm(phone.store, scheduler, time.clock, time.monotonicClock, time.bootCounter, EngineLogger())
            val snoozeEnd = Deadline.after(time.now, 9.minutes)
            phone.store.commit(SessionState.Snoozed(snoozedSession().copy(snoozeEnd = snoozeEnd)))

            time.jumpWall((-1).hours)
            assertEquals(snoozeEnd, rearm.afterSystemEvent(), "TIME_SET: the stored deadline, converted to wall time by the adapter")

            time.reboot(off = 10.hours)
            val passed = Deadline.after(time.now, SessionSlotRearm.IMMEDIATELY)
            assertEquals(passed, rearm.afterSystemEvent(), "the phone was off through the snooze end")
            assertEquals(listOf<Call>(Call.Slot(snoozeEnd), Call.Slot(passed)), scheduler.calls)
        }

    private companion object {
        val ALARM_FIRED = SessionEvent.AlarmFired(SESSION_ID, testConfig(), SEEDS, beforeFirstUnlock = false)

        fun Outcome<SessionState, *>.state(): SessionState = assertIs<Outcome.Success<SessionState>>(this).value
    }
}
