package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.time.Deadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Story 4.8 alarm safety: a payment in flight (`paying`, with or without `unlocking`) never changes what the alarm does.
 * Every non-payment event gives the same transition as without the payment, apart from the payment fields themselves,
 * and everything that ends the ring or the payment clears both fields.
 */
class PaymentAlarmSafetyTest {
    private val payments: List<Pair<String, (SessionData) -> SessionData>> =
        listOf(
            "paying" to { s: SessionData -> s.copy(paying = INTENT) },
            "unlocking" to { s: SessionData -> s.copy(paying = INTENT, unlocking = true) },
        )

    /** The events that drive the alarm and the check, each at a time its guard passes for some ring state. */
    private val alarmEvents: List<Pair<SessionEvent, kotlin.time.Duration>> =
        listOf(
            SessionEvent.ImUpTapped to 1.minutes,
            SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder) to 1.minutes,
            SessionEvent.FallbackRequested(FALLBACK_REQUEST.type, FALLBACK_REQUEST.reason) to 1.minutes,
            SessionEvent.GraceElapsed to 1.minutes,
            SessionEvent.NoInteractionTimeout to 31.minutes,
            SessionEvent.CallStarted to 1.minutes,
            SessionEvent.SlotFired to 1.minutes,
            SessionEvent.OverlapAlarmFired("alarm-2", SCHEDULED_AT) to 1.minutes,
            SessionEvent.UserInteracted to 1.minutes,
            SessionEvent.SnoozeTapped to 1.minutes,
        )

    private fun SessionState.withoutPayment(): SessionState =
        when (this) {
            SessionState.Idle -> this
            is SessionState.Active -> with(session.copy(paying = null, unlocking = false))
        }

    @Test
    fun `every alarm and check event does exactly what it does without a payment in flight`() {
        val reducer = reducer()
        payments.forEach { (name, pay) ->
            alarmEvents.forEach { (event, after) ->
                val graceOver = ringSession().copy(graceEnd = Deadline.after(T0, 20.seconds))
                (ringStates() + ringStates(graceOver)).forEach { plain ->
                    val withPayment = plain.with(pay(plain.session))
                    val expected = reducer.reduce(plain, event, at(after))
                    val actual = reducer.reduce(withPayment, event, at(after))

                    assertEquals(expected.effects, actual.effects, "$name ${plain.kind} + $event: effects")
                    assertEquals(expected.state, actual.state.withoutPayment(), "$name ${plain.kind} + $event: state")
                    assertFalse(actual.effects.any { it is SessionEffect.LaunchBilling || it is SessionEffect.RequestKeyguardDismiss })
                }
            }
        }
    }

    @Test
    fun `a call pause and its end leave the payment untouched`() {
        payments.forEach { (name, pay) ->
            ringStates(pay(ringSession())).forEach { from ->
                val paused = reducer().reduce(from, SessionEvent.CallStarted, at(1.minutes)).state as SessionState.Ring
                val resumed = reducer().reduce(paused, SessionEvent.CallEnded, at(2.minutes)).state as SessionState.Active
                assertEquals(from.session.paying, resumed.session.paying, name)
                assertEquals(from.session.unlocking, resumed.session.unlocking, name)
            }
        }
    }

    @Test
    fun `everything that ends the payment or the ring clears paying and unlocking together`() {
        val ends: List<Pair<String, SessionEvent>> =
            listOf(
                "failed" to SessionEvent.PurchaseFailed,
                "cancelled" to SessionEvent.PurchaseCancelled,
                "pending" to SessionEvent.PurchasePending,
                "granted" to SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant),
                "reuse offered" to SessionEvent.ReuseOffered(PRODUCT, PurchaseVerdict.OfferReuse),
                "reuse accepted" to SessionEvent.ReuseAccepted(PRODUCT, TOKEN),
                "restored" to SessionEvent.ProcessRestored,
                "unlock failed" to SessionEvent.UnlockFailed,
            )
        ends.forEach { (name, event) ->
            ringStates(ringSession().copy(paying = INTENT, unlocking = true)).forEach { from ->
                val session = (reducer().reduce(from, event, at(1.minutes)).state as SessionState.Active).session
                assertNull(session.paying, "$name from ${from.kind}")
                assertFalse(session.unlocking, "$name from ${from.kind}")
            }
        }
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
        ringStates().forEach { from ->
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
