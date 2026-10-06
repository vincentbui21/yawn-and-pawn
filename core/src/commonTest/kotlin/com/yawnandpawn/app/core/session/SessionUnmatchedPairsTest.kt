package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.session.SessionEvent.CallEnded
import com.yawnandpawn.app.core.session.SessionEvent.CallStarted
import com.yawnandpawn.app.core.session.SessionState.Completed
import com.yawnandpawn.app.core.session.SessionState.Grace
import com.yawnandpawn.app.core.session.SessionState.Idle
import com.yawnandpawn.app.core.session.SessionState.Loud
import com.yawnandpawn.app.core.session.SessionState.Missed
import com.yawnandpawn.app.core.session.SessionState.Ringing
import com.yawnandpawn.app.core.session.SessionState.Snoozed
import com.yawnandpawn.app.core.time.Deadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every state against every event. The expected match below mirrors the AD-2 table independently of the reducer: a
 * pair with a row must not be ignored, and every other pair returns the same state with only `LogIgnored(event)`.
 */
class SessionUnmatchedPairsTest {
    /** One minute into the session: no snooze, timeout or reboot is due, so time guards fail. */
    private val now = at(1.minutes)

    private val states: List<SessionState> =
        listOf(Idle) +
            listOf(
                ringSession(),
                ringSession().copy(pausedAt = T0),
                ringSession().copy(beforeFirstUnlock = true),
            ).flatMap { ringStates(it) } +
            listOf(Snoozed(snoozedSession()), Completed(ringSession().noTimers()), Missed(ringSession().noTimers()))

    /** The 26 AD-2 events, plus guard-failing variants. */
    private val events: List<SessionEvent> =
        listOf(
            SessionEvent.AlarmFired(SESSION_ID, testConfig(), beforeFirstUnlock = false),
            SessionEvent.AlarmFired(SESSION_ID, config = null, beforeFirstUnlock = false),
            SessionEvent.TestAlarmFired(SESSION_ID, testConfig(), beforeFirstUnlock = false),
            SessionEvent.SlotFired,
            SessionEvent.ProcessRestored,
            SessionEvent.OverlapAlarmFired("alarm-2", SCHEDULED_AT),
            SessionEvent.GraceElapsed,
            SessionEvent.NoInteractionTimeout,
            CallStarted,
            CallEnded,
            SessionEvent.UserUnlocked,
            SessionEvent.Recorded(SESSION_ID),
            SessionEvent.Recorded("another-session"),
            SessionEvent.ImageMatchCompleted(matched = true),
            SessionEvent.ImageMatchCompleted(matched = false),
            SessionEvent.ImageMatchFailed,
            SessionEvent.ReuseOffered(PRODUCT, PurchaseVerdict.OfferReuse),
            SessionEvent.ReuseOffered(PRODUCT, PurchaseVerdict.Ignore),
            SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant),
            SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.ConsumeOnly),
            SessionEvent.PurchaseFailed,
            SessionEvent.PurchaseCancelled,
            SessionEvent.PurchasePending,
            SessionEvent.ImUpTapped,
            SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder),
            SessionEvent.FallbackRequested,
            SessionEvent.SnoozeTapped,
            SessionEvent.PayConfirmed(INTENT),
            SessionEvent.ReuseAccepted(PRODUCT, TOKEN),
            SessionEvent.ReuseDeclined(PRODUCT),
            SessionEvent.UserInteracted,
        )

    @Test
    fun `the pair list covers all 26 events and all 7 states`() {
        assertEquals(26, events.map { it::class }.toSet().size)
        assertEquals(7, states.map { it::class }.toSet().size)
    }

    @Test
    fun `every pair without a row returns the same state with only LogIgnored and never throws`() {
        val reducer = reducer()
        var ignoredPairs = 0
        states.forEach { state ->
            events.forEach { event ->
                val transition = reducer.reduce(state, event, now)
                if (hasRow(state, event)) {
                    assertNotEquals(ignored(state, event), transition, "${state.kind} + $event has a row")
                } else {
                    ignoredPairs++
                    assertEquals(ignored(state, event), transition, "${state.kind} + $event has no row")
                }
            }
        }
        assertEquals(true, ignoredPairs > 0)
    }

    @Test
    fun `the Snoozed and ImUpTapped pair from the matrix is ignored`() {
        val snoozed = Snoozed(snoozedSession())
        val transition = reducer().reduce(snoozed, SessionEvent.ImUpTapped, now)
        assertEquals(Transition(snoozed, listOf(SessionEffect.LogIgnored("ImUpTapped", SESSION_ID))), transition)
    }

    @Test
    fun `AlarmFired without a config (alarm missing or disabled) leaves Idle with only LogIgnored`() {
        val event = SessionEvent.AlarmFired(SESSION_ID, config = null, beforeFirstUnlock = false)
        assertEquals(Transition(Idle, listOf(SessionEffect.LogIgnored("AlarmFired", SESSION_ID))), reducer().reduce(Idle, event, now))
    }

    @Test
    fun `time guards fail before their deadline`() {
        val snoozed = Snoozed(snoozedSession())
        listOf(SessionEvent.SlotFired, SessionEvent.ProcessRestored).forEach { event ->
            assertEquals(ignored(snoozed, event), reducer().reduce(snoozed, event, at(9.minutes - 1.minutes)))
        }
        val ringing = Ringing(ringSession())
        val event = SessionEvent.NoInteractionTimeout
        assertEquals(ignored(ringing, event), reducer().reduce(ringing, event, at(29.minutes)))
        val paused = Loud(ringSession().copy(pausedAt = at(10.minutes)))
        assertEquals(ignored(paused, event), reducer().reduce(paused, event, at(45.minutes)))
    }

    @Test
    fun `GraceElapsed before the grace end is ignored`() {
        val grace = Grace(ringSession().copy(graceEnd = Deadline.after(T0, 20.seconds)))
        val event = SessionEvent.GraceElapsed
        assertEquals(ignored(grace, event), reducer().reduce(grace, event, at(19.seconds)))
    }

    @Test
    fun `GraceElapsed during a call is ignored, so the call is never unmuted over`() {
        val grace = Grace(ringSession().copy(graceEnd = Deadline.after(T0, 20.seconds), pausedAt = at(10.seconds)))
        val event = SessionEvent.GraceElapsed
        assertEquals(ignored(grace, event), reducer().reduce(grace, event, at(5.minutes)))
    }

    @Test
    fun `an ignored event logs only its type and the session id, never the alarm label or sound`() {
        val ringing = Ringing(ringSession())
        val event = SessionEvent.AlarmFired("session-2", testConfig(), beforeFirstUnlock = false)
        val transition = reducer().reduce(ringing, event, now)
        assertEquals(Transition(ringing, listOf(SessionEffect.LogIgnored("AlarmFired", "session-2"))), transition)
        val logged = transition.effects.single().toString()
        listOf("Work", "builtin:default", "alarm-1").forEach { assertFalse(it in logged, logged) }
        val unlocked = SessionEvent.UserUnlocked
        assertEquals(ignored(Idle, unlocked), Transition(Idle, listOf(SessionEffect.LogIgnored("UserUnlocked", null))))
        assertEquals(ignored(Idle, unlocked), reducer().reduce(Idle, unlocked, now))
    }

    /** The AD-2 table (and rule 2 for ProcessRestored) as a predicate, for the guards this test can reach. */
    private fun hasRow(
        state: SessionState,
        event: SessionEvent,
    ): Boolean =
        when (state) {
            Idle -> (event is SessionEvent.AlarmFired && event.config != null) || event is SessionEvent.TestAlarmFired
            is SessionState.Ring -> ringHasRow(state, event)
            is Snoozed -> event is SessionEvent.CallEvent || event is SessionEvent.OverlapAlarmFired
            is Completed, is Missed -> event == SessionEvent.Recorded(SESSION_ID)
        }

    private fun ringHasRow(
        state: SessionState.Ring,
        event: SessionEvent,
    ): Boolean =
        when (event) {
            is SessionEvent.UserEvent, SessionEvent.SlotFired, SessionEvent.ProcessRestored, is SessionEvent.OverlapAlarmFired -> true
            SessionEvent.PurchaseFailed, SessionEvent.PurchaseCancelled, SessionEvent.PurchasePending -> true
            is SessionEvent.ReuseOffered -> event.verdict == PurchaseVerdict.OfferReuse
            is SessionEvent.PurchaseGranted -> event.verdict == PurchaseVerdict.Grant
            CallStarted -> !state.session.paused
            CallEnded -> state.session.paused
            SessionEvent.GraceElapsed -> state is Grace && !state.session.paused && state.session.graceEnd?.isDue(now) == true
            is SessionEvent.ImageMatchEvent -> state !is Ringing
            SessionEvent.UserUnlocked -> state is Ringing && state.session.beforeFirstUnlock
            else -> false
        }
}
