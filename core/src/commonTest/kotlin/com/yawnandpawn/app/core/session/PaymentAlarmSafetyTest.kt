package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.time.Deadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Story 4.8 alarm safety: a payment in flight (`paying`, with or without `unlocking`) never changes what the alarm does.
 * Every non-payment event gives exactly the transition it gives without the payment: a ring that stays a ring keeps the
 * payment as it was, and a ring that ends (Completed, Missed) drops it. Everything that ends the payment clears both
 * fields together.
 */
class PaymentAlarmSafetyTest {
    private val payments: List<Pair<String, (SessionData) -> SessionData>> =
        listOf(
            "paying" to { s: SessionData -> s.copy(paying = INTENT) },
            "unlocking" to { s: SessionData -> s.copy(paying = INTENT, unlocking = true) },
        )

    private val twoSteps = ringSession(testConfig(checkPlan = TWO_STEPS))

    /** Ring sessions in every shape the alarm rules look at. */
    private val sessions: List<Pair<String, SessionData>> =
        listOf(
            "first ring" to ringSession(),
            "paused by a call" to ringSession().copy(pausedAt = T0),
            "before first unlock" to ringSession().copy(beforeFirstUnlock = true, directBootRing = true),
            "mid-check" to twoSteps.copy(checkRun = twoSteps.checkRun.copy(step = StepPointer(1, 0), failedAttempts = 2)),
            "fallback used" to twoSteps.copy(checkRun = twoSteps.checkRun.copy(fallbackUsed = true)),
            "grace over" to ringSession().copy(graceEnd = Deadline.after(T0, 20.seconds)),
        )

    /** The events that drive the alarm and the check, each at a time its guard passes for some ring state. */
    private val alarmEvents: List<Pair<SessionEvent, Duration>> =
        listOf(
            SessionEvent.ImUpTapped to 1.minutes,
            SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder) to 1.minutes,
            SessionEvent.ImageMatchCompleted(matched = true) to 1.minutes,
            SessionEvent.ImageMatchFailed to 1.minutes,
            FALLBACK_REQUEST to 1.minutes,
            SessionEvent.GraceElapsed to 1.minutes,
            SessionEvent.NoInteractionTimeout to 31.minutes,
            SessionEvent.CallStarted to 1.minutes,
            SessionEvent.CallEnded to 2.minutes,
            SessionEvent.UserUnlocked to 1.minutes,
            SessionEvent.SlotFired to 1.minutes,
            SessionEvent.OverlapAlarmFired("alarm-2", SCHEDULED_AT) to 1.minutes,
            SessionEvent.UserInteracted to 1.minutes,
            SessionEvent.SnoozeTapped to 1.minutes,
        )

    private val reducers: List<Pair<String, SessionReducer>> =
        listOf(
            "valid last" to reducer(check = StepResult.ValidLast),
            "valid next" to reducer(check = StepResult.ValidNext),
            "invalid" to reducer(check = StepResult.Invalid),
            "fallback refused" to reducer(fallback = FallbackDecision.NotAllowed),
        )

    @Test
    fun `every alarm and check event does exactly what it does without a payment, and keeps or drops the payment`() {
        val cases =
            reducers.flatMap { r ->
                payments.flatMap { p -> sessions.flatMap { s -> ringStates(s.second).map { Triple(r, p, s.first to it) } } }
            }
        var compared = 0
        cases.forEach { (named, payment, shaped) ->
            val (reducerName, reducer) = named
            val (payName, pay) = payment
            val (sessionName, plain) = shaped
            alarmEvents.forEach { (event, after) ->
                val name = "$reducerName / $payName / $sessionName / ${plain.kind} + $event"
                val expected = reducer.reduce(plain, event, at(after))
                val actual = reducer.reduce(plain.with(pay(plain.session)), event, at(after))
                assertEquals(wantedWithPayment(plain, expected.state, pay), actual.state, "$name: state")
                assertEquals(expected.effects, actual.effects, "$name: effects")
                compared++
            }
        }
        assertEquals(reducers.size * payments.size * sessions.size * 3 * alarmEvents.size, compared)
    }

    /**
     * The state [expected] (the transition without a payment) should be with one: an ignored event leaves the paying
     * state as it was, a ring that stays a ring keeps the payment untouched, and a ring that ends drops it.
     */
    private fun wantedWithPayment(
        plain: SessionState.Ring,
        expected: SessionState,
        pay: (SessionData) -> SessionData,
    ): SessionState =
        when {
            expected == plain -> plain.with(pay(plain.session))
            expected is SessionState.Ring -> expected.with(pay(expected.session))
            else -> expected
        }

    @Test
    fun `I'm up while the unlock is pending goes to Grace and keeps the payment`() {
        val from = SessionState.Ringing(ringSession().copy(paying = INTENT, unlocking = true))
        val grace = reducer().reduce(from, SessionEvent.ImUpTapped, at(1.minutes)).state as SessionState.Grace
        assertEquals(INTENT, grace.session.paying)
        assertEquals(true, grace.session.unlocking)
    }

    @Test
    fun `everything that ends the payment or the ring clears paying and unlocking together`() {
        val ends: List<Triple<String, SessionEvent, Duration>> =
            listOf(
                Triple("failed", SessionEvent.PurchaseFailed(), 1.minutes),
                Triple("cancelled", SessionEvent.PurchaseCancelled, 1.minutes),
                Triple("pending", SessionEvent.PurchasePending, 1.minutes),
                Triple("granted", SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant), 1.minutes),
                Triple("reuse offered", SessionEvent.ReuseOffered(PRODUCT, TOKEN, PurchaseVerdict.OfferReuse), 1.minutes),
                Triple("reuse accepted", SessionEvent.ReuseAccepted(PRODUCT, TOKEN), 1.minutes),
                Triple("restored", SessionEvent.ProcessRestored, 1.minutes),
                Triple("unlock failed", SessionEvent.UnlockFailed, 1.minutes),
                Triple("missed", SessionEvent.NoInteractionTimeout, 31.minutes),
                Triple("check passed", SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder), 1.minutes),
            )
        var checked = 0
        ends.forEach { (name, event, after) ->
            ringStates(ringSession().copy(paying = INTENT, unlocking = true)).forEach { from ->
                val transition = reducer().reduce(from, event, at(after))
                // Missed comes from Ringing and Loud only (Grace ignores the timeout); the check passes from Grace and Loud
                // only (an answer while Ringing only resets the deadline).
                val noRow = transition.effects.any { it is SessionEffect.LogIgnored }
                val answerWhileRinging = from is SessionState.Ringing && event is SessionEvent.CheckAnswerSubmitted
                if (!noRow && !answerWhileRinging) {
                    val session = (transition.state as SessionState.Active).session
                    assertNull(session.paying, "$name from ${from.kind}")
                    assertFalse(session.unlocking, "$name from ${from.kind}")
                    checked++
                }
            }
        }
        assertEquals(ends.size * 3 - 2, checked, "every end reached from every state it has a row in")
        // The next ring after a snooze starts with no payment.
        val snoozed = SessionState.Snoozed(snoozedSession().copy(paying = INTENT, unlocking = true))
        val next = (reducer().reduce(snoozed, SessionEvent.SlotFired, at(9.minutes)).state as SessionState.Active).session
        assertNull(next.paying)
        assertFalse(next.unlocking)
    }

    @Test
    fun `an unlock result outside a pending unlock, or after the ring ended, launches nothing`() {
        val unlockEvents = listOf(SessionEvent.UnlockSucceeded, SessionEvent.UnlockFailed)
        val pending = ringSession().copy(paying = INTENT, unlocking = true)
        val states: List<SessionState> =
            ringStates() +
                ringStates(ringSession().copy(paying = INTENT)) +
                listOf(
                    SessionState.Idle,
                    SessionState.Snoozed(snoozedSession()),
                    SessionState.Completed(pending.noTimers()),
                    SessionState.Missed(pending.noTimers()),
                )
        states.forEach { state ->
            unlockEvents.forEach { event ->
                assertEquals(ignored(state, event), reducer().reduce(state, event, at(1.minutes)), "${state.kind} + $event")
            }
        }
    }

    @Test
    fun `the keyguard input matters only to a Pay`() {
        sessions.forEach { (_, session) ->
            ringStates(session).forEach { from ->
                alarmEvents.forEach { (event, after) ->
                    assertEquals(
                        reducer().reduce(from, event, at(after)),
                        reducer().reduce(from, event, at(after), keyguardLocked = true),
                        "${from.kind} + $event",
                    )
                }
            }
        }
    }
}
