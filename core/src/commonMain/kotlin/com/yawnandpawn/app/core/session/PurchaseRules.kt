package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.session.SessionState.Ring
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlin.time.Duration.Companion.minutes

/**
 * AD-2 snooze and purchase rows from Ringing, Grace and Loud. A null result means no row matches. [availability] is
 * the reducer's AD-7 availability (the policy, with test mode always unavailable).
 */
internal class PurchaseRules(
    private val availability: (SessionData) -> SnoozeAvailability,
) {
    /** SnoozeTapped while Available: show the confirm sheet. */
    fun onSnoozeTapped(state: Ring): Transition? = offer(state)?.let { Transition(state, listOf(SessionEffect.ShowSnoozeConfirm(it))) }

    /**
     * PayConfirmed while Available and no purchase is in flight: `paying` is set, the intent persisted and billing
     * launched. A second confirm while paying never launches billing again.
     */
    fun onPayConfirmed(
        state: Ring,
        intentId: PurchaseIntentId,
    ): Transition? =
        offer(state)?.takeIf { state.session.paying == null }?.let { offer ->
            val session = state.session
            Transition(
                state.with(session.copy(paying = intentId)),
                listOf(
                    SessionEffect.PersistPurchaseIntent(intentId, session.sessionId, offer),
                    SessionEffect.LaunchBilling(intentId, session.sessionId, offer),
                ),
            )
        }

    /** ReuseDeclined: remember the product (its snooze shows "earlier payment is being refunded") and hide the sheet. */
    fun onReuseDeclined(
        state: Ring,
        productId: String,
    ): Transition = Transition(state.with(state.session.copy(declinedReuseProduct = productId)), listOf(SessionEffect.HideReuseSheet))

    /** Billing results: ReuseOffered, PurchaseGranted (reconciler says Grant), PurchaseFailed / Cancelled / Pending. */
    fun onPurchase(
        state: Ring,
        event: SessionEvent.PurchaseEvent,
        now: TimeSnapshot,
    ): Transition? {
        val notPaying = state.session.copy(paying = null)
        return when (event) {
            is SessionEvent.ReuseOffered -> {
                if (event.verdict == PurchaseVerdict.OfferReuse) {
                    Transition(state.with(notPaying), listOf(SessionEffect.ShowReuseSheet(event.productId)))
                } else {
                    null
                }
            }

            is SessionEvent.PurchaseGranted -> {
                if (event.verdict == PurchaseVerdict.Grant) onPaidSnooze(state, event.token, now) else null
            }

            SessionEvent.PurchaseFailed -> {
                Transition(state.with(notPaying), listOf(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Failed)))
            }

            SessionEvent.PurchaseCancelled -> {
                Transition(state.with(notPaying), listOf(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Cancelled)))
            }

            SessionEvent.PurchasePending -> {
                Transition(state.with(notPaying.copy(paymentPending = true)), listOf(SessionEffect.ShowPaymentPending))
            }
        }
    }

    /** A paid snooze (a grant, or ReuseAccepted); ignored in a test session, which can never charge. */
    fun onPaidSnooze(
        state: Ring,
        token: PurchaseToken,
        now: TimeSnapshot,
    ): Transition? = if (state.session.config.testMode) null else snoozed(state, token, now)

    /**
     * A snooze was paid for (a grant, or a reused stranded payment [token]): the sound stops, the check progress is
     * dropped (the next ring resolves the plan again with new seeds), `snoozesGranted` goes up and the slot is armed at
     * the snooze end. Snooze time never counts toward the interaction timeout, so the ring timers are cleared.
     */
    private fun snoozed(
        state: Ring,
        token: PurchaseToken,
        now: TimeSnapshot,
    ): Transition {
        val session = state.session
        val snoozeEnd = Deadline.after(now, session.config.snoozeLengthMinutes.minutes)
        val snoozed =
            session.withoutTimers().copy(
                snoozesGranted = session.snoozesGranted + 1,
                checkRun = session.checkRun.restart(),
                paying = null,
                paymentPending = false,
                snoozeEnd = snoozeEnd,
            )
        return Transition(
            SessionState.Snoozed(snoozed),
            listOf(SessionEffect.StopSound, SessionEffect.ArmSlot(snoozeEnd), SessionEffect.Consume(token)),
        )
    }

    private fun offer(state: Ring): SnoozeOffer? = (availability(state.session) as? SnoozeAvailability.Available)?.offer
}
