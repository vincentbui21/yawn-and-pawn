package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.session.SessionState.Grace
import com.yawnandpawn.app.core.session.SessionState.Loud
import com.yawnandpawn.app.core.session.SessionState.Missed
import com.yawnandpawn.app.core.session.SessionState.Ringing
import com.yawnandpawn.app.core.session.SessionState.Snoozed
import com.yawnandpawn.app.core.time.Deadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Story 1.16: when the wake service must tick the engine next, from the same deadline `dueEvents` reads. */
class NextTickTest {
    private val reducer = reducer(check = StepResult.ValidNext)

    private fun firstRing(): SessionState =
        reducer.reduce(SessionState.Idle, SessionEvent.AlarmFired(SESSION_ID, testConfig(), SEEDS, beforeFirstUnlock = false), T0).state

    @Test
    fun `a ring ticks when its 30-minute interaction deadline is due, and at once after it`() {
        val ringing = firstRing()

        assertEquals(30.minutes, nextTickIn(ringing, T0))
        assertEquals(10.minutes, nextTickIn(ringing, at(20.minutes)))
        assertEquals(Duration.ZERO, nextTickIn(ringing, at(30.minutes)))
        assertEquals(Duration.ZERO, nextTickIn(ringing, at(2.hours)))
        assertEquals(listOf<SessionEvent>(SessionEvent.NoInteractionTimeout), dueEvents(ringing, at(30.minutes)))
    }

    @Test
    fun `Grace ticks at the grace end and Loud at the interaction deadline`() {
        val grace = reducer.reduce(firstRing(), SessionEvent.ImUpTapped, at(1.minutes)).state

        assertEquals(20.seconds, nextTickIn(grace as Grace, at(1.minutes)))
        val loud = Loud(grace.session.copy(graceEnd = null))
        assertEquals(30.minutes, nextTickIn(loud, at(1.minutes)))
    }

    @Test
    fun `a wall-clock jump does not move the next tick on the same boot`() {
        val ringing = firstRing()
        val wallBackOneHour = at(10.minutes).let { it.copy(wallMillis = it.wallMillis - 1.hours.inWholeMilliseconds) }

        assertEquals(20.minutes, nextTickIn(ringing, wallBackOneHour))
    }

    @Test
    fun `after a reboot the next tick falls back to wall time`() {
        val ringing = firstRing()
        val rebooted = at(10.minutes).copy(elapsedMillis = 5_000, bootCount = T0.bootCount + 1)

        assertEquals(20.minutes, nextTickIn(ringing, rebooted))
    }

    @Test
    fun `no tick is due while snoozed, paused by a call, or without a ring`() {
        val session = (firstRing() as Ringing).session
        val states =
            listOf(
                SessionState.Idle,
                Snoozed(session.copy(interactionDeadline = null, snoozeEnd = Deadline.after(T0, 9.minutes))),
                SessionState.Completed(session),
                Missed(session),
                Ringing(session.copy(pausedAt = T0)),
                Grace(session.copy(graceEnd = Deadline.after(T0, 20.seconds), pausedAt = T0)),
                Ringing(session.copy(interactionDeadline = null)),
            )

        states.forEach { assertNull(nextTickIn(it, at(1.minutes)), it.kind) }
    }
}
