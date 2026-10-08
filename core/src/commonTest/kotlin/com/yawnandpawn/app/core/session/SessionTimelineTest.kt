package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.session.SessionEvent.NoInteractionTimeout
import com.yawnandpawn.app.core.session.SessionState.Grace
import com.yawnandpawn.app.core.session.SessionState.Loud
import com.yawnandpawn.app.core.session.SessionState.Missed
import com.yawnandpawn.app.core.session.SessionState.Ringing
import com.yawnandpawn.app.core.session.SessionState.Snoozed
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** FR-ALM-9 timers through `dueEvents`, driven by the reducer from the first ring at [T0]. */
class SessionTimelineTest {
    private val reducer = reducer(check = StepResult.ValidNext)
    private val timeout = listOf<SessionEvent>(NoInteractionTimeout)

    /** A session that started ringing at [T0]. */
    private fun firstRing(): SessionState =
        reducer.reduce(SessionState.Idle, SessionEvent.AlarmFired(SESSION_ID, testConfig(), beforeFirstUnlock = false), T0).state

    private fun SessionState.after(
        event: SessionEvent,
        now: TimeSnapshot,
    ): SessionState = reducer.reduce(this, event, now).state

    /** The last moment before [due] gives nothing, and [due] itself gives [expected]. */
    private fun assertDueAt(
        state: SessionState,
        due: TimeSnapshot,
        expected: List<SessionEvent> = timeout,
    ) {
        assertEquals(emptyList(), dueEvents(state, due.minusMillis(1)), "just before")
        assertEquals(expected, dueEvents(state, due), "at the deadline")
    }

    private fun TimeSnapshot.minusMillis(millis: Long) = copy(wallMillis = wallMillis - millis, elapsedMillis = elapsedMillis - millis)

    @Test
    fun `29 59 after the last user event nothing is due, 30 00 times out and the session is Missed`() {
        val ringing = firstRing()
        assertEquals(emptyList(), dueEvents(ringing, at(29.minutes + 59.seconds)))
        val due = dueEvents(ringing, at(30.minutes))
        assertEquals(timeout, due)
        assertIs<Missed>(ringing.after(due.single(), at(30.minutes)))
    }

    @Test
    fun `a tap at 20 00 moves the timeout to 50 00`() {
        val touched = firstRing().after(SessionEvent.UserInteracted, at(20.minutes))
        assertEquals(emptyList(), dueEvents(touched, at(30.minutes)))
        assertDueAt(touched, at(50.minutes))
    }

    @Test
    fun `a 10 minute call from 05 00 to 15 00 moves the timeout to 40 00 and nothing is due during it`() {
        val inCall = firstRing().after(SessionEvent.CallStarted, at(5.minutes))
        assertEquals(emptyList(), dueEvents(inCall, at(35.minutes)), "paused")
        val afterCall = inCall.after(SessionEvent.CallEnded, at(15.minutes))
        assertDueAt(afterCall, at(40.minutes))
    }

    @Test
    fun `a tap during a call gives 30 minutes from the end of the call`() {
        val touched =
            firstRing()
                .after(SessionEvent.CallStarted, at(5.minutes))
                .after(SessionEvent.UserInteracted, at(10.minutes))
                .after(SessionEvent.CallEnded, at(15.minutes))
        assertDueAt(touched, at(45.minutes))
    }

    @Test
    fun `a grace window started during a call ends graceSeconds after the call ends`() {
        val grace =
            firstRing()
                .after(SessionEvent.CallStarted, at(5.minutes))
                .after(SessionEvent.ImUpTapped, at(6.minutes))
        assertIs<Grace>(grace)
        assertEquals(emptyList(), dueEvents(grace, at(14.minutes)), "paused")
        val afterCall = grace.after(SessionEvent.CallEnded, at(15.minutes))
        assertDueAt(afterCall, at(15.minutes + 20.seconds), listOf(SessionEvent.GraceElapsed))
    }

    @Test
    fun `a restore during a call clears the pause and times out 30 minutes after the restore`() {
        val inCall = firstRing().after(SessionEvent.CallStarted, at(5.minutes))
        val restored = inCall.after(SessionEvent.ProcessRestored, at(12.minutes))
        val ringing = assertIs<Ringing>(restored)
        assertFalse(ringing.session.paused)
        assertEquals(listOf(EntryEffect.SoundAt("builtin:default", 80)), entryEffects(ringing).take(1))
        assertDueAt(ringing, at(42.minutes))
    }

    @Test
    fun `a call that spans a reboot is measured in wall time`() {
        val inCall = firstRing().after(SessionEvent.CallStarted, at(5.minutes))
        val rebootedAt15 = TimeSnapshot(T0.wallMillis + 15.minutes.inWholeMilliseconds, elapsedMillis = 5_000, bootCount = 4)
        val afterCall = inCall.after(SessionEvent.CallEnded, rebootedAt15)
        assertEquals(Deadline.after(T0, 40.minutes), (afterCall as Ringing).session.interactionDeadline)
    }

    @Test
    fun `a call that spans a reboot with the same boot count is measured in wall time`() {
        // No BOOT_COUNT: the count stays the same, but the elapsed clock went back below the pause.
        val inCall = firstRing().after(SessionEvent.CallStarted, at(5.minutes))
        val rebootedAt15 = TimeSnapshot(T0.wallMillis + 15.minutes.inWholeMilliseconds, elapsedMillis = 5_000, bootCount = T0.bootCount)
        val afterCall = inCall.after(SessionEvent.CallEnded, rebootedAt15)
        assertEquals(Deadline.after(T0, 40.minutes), (afterCall as Ringing).session.interactionDeadline)
    }

    @Test
    fun `a wall clock jump of 2 hours with monotonic time unchanged makes nothing due`() {
        val grace = firstRing().after(SessionEvent.ImUpTapped, at(1.minutes))
        val jumped = at(1.minutes).let { it.copy(wallMillis = it.wallMillis + 2.hours.inWholeMilliseconds) }
        assertEquals(emptyList(), dueEvents(firstRing(), jumped))
        assertEquals(emptyList(), dueEvents(grace, jumped))
    }

    @Test
    fun `after a reboot the deadlines compare wall time`() {
        val ringing = firstRing()

        fun rebooted(wallAfterT0: Duration) =
            TimeSnapshot(T0.wallMillis + wallAfterT0.inWholeMilliseconds, elapsedMillis = 5_000, bootCount = 4)
        assertEquals(emptyList(), dueEvents(ringing, rebooted(29.minutes)))
        assertEquals(timeout, dueEvents(ringing, rebooted(30.minutes)))
    }

    @Test
    fun `the grace window ends after graceSeconds and goes Loud`() {
        val grace = firstRing().after(SessionEvent.ImUpTapped, at(1.minutes))
        assertIs<Grace>(grace)
        assertDueAt(grace, at(1.minutes + 20.seconds), listOf(SessionEvent.GraceElapsed))
        assertIs<Loud>(grace.after(SessionEvent.GraceElapsed, at(1.minutes + 20.seconds)))
    }

    @Test
    fun `grace keeps counting while paying is set`() {
        val grace =
            firstRing().after(SessionEvent.ImUpTapped, at(1.minutes)).after(
                PAY,
                at(1.minutes + 5.seconds),
            )
        assertEquals(INTENT, assertIs<Grace>(grace).session.paying)
        assertDueAt(grace, at(1.minutes + 20.seconds), listOf(SessionEvent.GraceElapsed))
    }

    @Test
    fun `a call during the grace window moves the grace end by the call`() {
        val grace =
            firstRing()
                .after(SessionEvent.ImUpTapped, at(1.minutes))
                .after(SessionEvent.CallStarted, at(1.minutes + 10.seconds))
                .after(SessionEvent.CallEnded, at(3.minutes + 10.seconds))
        assertDueAt(grace, at(3.minutes + 20.seconds), listOf(SessionEvent.GraceElapsed))
    }

    @Test
    fun `a snooze of 9 minutes is not counted and the new ring gets a fresh 30 minutes`() {
        val snoozed =
            firstRing()
                .after(SessionEvent.ImUpTapped, at(10.minutes))
                .after(SessionEvent.GraceElapsed, at(10.minutes + 20.seconds))
                .after(SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant), at(11.minutes))
        assertIs<Snoozed>(snoozed)
        assertEquals(emptyList(), dueEvents(snoozed, at(45.minutes)), "nothing is due while snoozed")
        val rang = snoozed.after(SessionEvent.SlotFired, at(20.minutes))
        assertIs<Ringing>(rang)
        assertEquals(2, rang.session.ringIndex)
        assertDueAt(rang, at(50.minutes))
    }

    @Test
    fun `a merged ring after a snooze starts a fresh 30 minutes`() {
        val snoozed = Snoozed(snoozedSession())
        val merged = snoozed.after(SessionEvent.OverlapAlarmFired("alarm-2", SCHEDULED_AT), at(3.minutes))
        assertDueAt(merged, at(33.minutes))
    }

    @Test
    fun `a restored ring gets a fresh 30 minutes and paying is cleared`() {
        val paying = Loud(ringSession().copy(paying = INTENT))
        val restored = reducer.reduce(paying, SessionEvent.ProcessRestored, at(25.minutes))
        assertEquals(emptyList(), restored.effects)
        val state = restored.state as Loud
        assertNull(state.session.paying)
        assertDueAt(state, at(55.minutes))
    }

    @Test
    fun `restoring a snooze past its end rings immediately as the next ring`() {
        val snoozed = Snoozed(snoozedSession())
        val restored = reducer.reduce(snoozed, SessionEvent.ProcessRestored, at(20.minutes))
        val ringing = assertIs<Ringing>(restored.state)
        assertEquals(2, ringing.session.ringIndex)
        assertNull(ringing.session.paying)
        assertNull(ringing.session.snoozeEnd)
        assertTrue(SessionEffect.StartWakeRuntime(SESSION_ID) in restored.effects)
    }

    @Test
    fun `pending then granted clears paymentPending and counts the snooze`() {
        val paying = Ringing(ringSession().copy(paying = INTENT))
        val pending = reducer.reduce(paying, SessionEvent.PurchasePending, at(2.minutes)).state as Ringing
        assertTrue(pending.session.paymentPending)
        assertNull(pending.session.paying)
        val granted = reducer.reduce(pending, SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant), at(4.minutes))
        val snoozed = assertIs<Snoozed>(granted.state)
        assertFalse(snoozed.session.paymentPending)
        assertEquals(1, snoozed.session.snoozesGranted)
        assertEquals(Deadline.after(at(4.minutes), 9.minutes), snoozed.session.snoozeEnd)
    }

    @Test
    fun `nothing is due in Idle, Completed or Missed, or while a call pauses Grace`() {
        listOf(SessionState.Idle, SessionState.Completed(ringSession()), Missed(ringSession())).forEach { state ->
            assertEquals(emptyList(), dueEvents(state, at(2.hours)), state.kind)
        }
        val pausedGrace = Grace(ringSession().copy(graceEnd = Deadline.after(T0, 20.seconds), pausedAt = T0))
        assertEquals(emptyList(), dueEvents(pausedGrace, at(2.hours)))
        assertEquals(emptyList(), dueEvents(Ringing(ringSession().copy(interactionDeadline = null)), at(2.hours)))
    }
}
