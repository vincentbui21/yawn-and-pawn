package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.billing.GrantLedgerEntry
import com.yawnandpawn.app.core.billing.LedgerStatus
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.session.SessionState.Ring
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

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
     * PayConfirmed while Available, no purchase in flight and the live price is for the offered product: `paying` is set and
     * the [PurchaseIntent] is persisted with the state (the engine writes it in the commit). Unlocked, billing launches;
     * with the keyguard up ([keyguardLocked], Spike S1) the unlock is requested first. A second confirm while billing is
     * in flight never persists or launches again. While only the unlock is pending, billing never launched, so a new
     * confirm replaces it (Story 4.8 review): a lost keyguard callback can never block Pay for the rest of the ring.
     */
    fun onPayConfirmed(
        state: Ring,
        event: SessionEvent.PayConfirmed,
        now: TimeSnapshot,
        keyguardLocked: Boolean,
    ): Transition? {
        val session = state.session
        val free = session.paying == null || session.unlocking
        val offer = offer(state)?.takeIf { free && event.livePrice.productId == it.productId } ?: return null
        val live = event.livePrice
        val intent =
            PurchaseIntent(
                intentId = event.intentId,
                sessionId = session.sessionId,
                productId = offer.productId,
                snoozeNumber = offer.snoozeNumber,
                price = live.price,
                formattedPrice = live.formattedPrice,
                createdAt = Instant.fromEpochMilliseconds(now.wallMillis),
            )
        val persist = SessionEffect.PersistPurchaseIntent(intent)
        val paying = session.copy(paying = event.intentId, unlocking = keyguardLocked)
        val next = if (keyguardLocked) SessionEffect.RequestKeyguardDismiss(event.intentId) else launch(event.intentId, session)
        return Transition(state.with(paying), listOf(persist, next))
    }

    /**
     * The unlock step's result while `unlocking` (Spike S1): unlocked, billing launches for the intent already persisted;
     * cancelled or failed, the payment is dropped with "Phone still locked. No charge." and the sound goes on.
     */
    fun onUnlock(
        state: Ring,
        event: SessionEvent.UnlockEvent,
    ): Transition? {
        val session = state.session
        val intentId = session.paying?.takeIf { session.unlocking } ?: return null
        return when (event) {
            SessionEvent.UnlockSucceeded -> {
                Transition(state.with(session.copy(unlocking = false)), listOf(launch(intentId, session)))
            }

            SessionEvent.UnlockFailed -> {
                Transition(state.with(session.withoutPayment()), listOf(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.UnlockFailed)))
            }
        }
    }

    private fun launch(
        intentId: PurchaseIntentId,
        session: SessionData,
    ) = SessionEffect.LaunchBilling(intentId, session.sessionId)

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
        val notPaying = state.session.withoutPayment()
        return when (event) {
            is SessionEvent.ReuseOffered -> {
                if (event.verdict == PurchaseVerdict.OfferReuse) {
                    Transition(state.with(notPaying), listOf(SessionEffect.ShowReuseSheet(event.productId)))
                } else {
                    null
                }
            }

            is SessionEvent.PurchaseGranted -> {
                val paid = PaidWith(event.productId, event.token, event.orderId, event.price)
                if (event.verdict == PurchaseVerdict.Grant) onPaidSnooze(state, paid, now) else null
            }

            is SessionEvent.PurchaseFailed -> {
                Transition(state.with(notPaying), listOf(SessionEffect.ShowPurchaseOutcome(outcomeOf(event.kind))))
            }

            SessionEvent.PurchaseCancelled -> {
                Transition(state.with(notPaying), listOf(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Cancelled)))
            }

            SessionEvent.PurchasePending -> {
                Transition(state.with(notPaying.copy(paymentPending = true)), listOf(SessionEffect.ShowPaymentPending))
            }
        }
    }

    /**
     * ReuseAccepted (Story 4.11): the stranded payment pays for this snooze only when it is for the product Snooze offers
     * now (the same availability guard as Pay), so a reuse can never pay for another price. Otherwise no row matches.
     */
    fun onReuseAccepted(
        state: Ring,
        event: SessionEvent.ReuseAccepted,
        now: TimeSnapshot,
    ): Transition? {
        val paid = PaidWith(event.productId, event.token, price = event.price)
        return if (offer(state)?.productId == event.productId) onPaidSnooze(state, paid, now) else null
    }

    /** The wake message for a payment that failed for [kind]. */
    private fun outcomeOf(kind: PurchaseFailureKind): PurchaseOutcome =
        when (kind) {
            PurchaseFailureKind.Offline -> PurchaseOutcome.Offline
            PurchaseFailureKind.UnlockFailed -> PurchaseOutcome.UnlockFailed
            PurchaseFailureKind.Error -> PurchaseOutcome.Failed
        }

    /** A paid snooze (a grant, or ReuseAccepted); ignored in a test session, which can never charge. */
    fun onPaidSnooze(
        state: Ring,
        paid: PaidWith,
        now: TimeSnapshot,
    ): Transition? = if (state.session.config.testMode) null else snoozed(state, paid, now)

    /**
     * A snooze was paid for (a grant, or a reused stranded payment): the sound stops, the check progress is dropped (the
     * next ring resolves the plan again with new seeds), `snoozesGranted` goes up, a known price joins `paid` (Story 4.7,
     * wake-screen display only) and the slot is armed at the snooze end.
     * Snooze time never counts toward the interaction timeout, so the ring timers are cleared. The grant ledger row is
     * persisted with the state (the engine writes it in the commit, Story 4.10), and only then is the token settled.
     */
    private fun snoozed(
        state: Ring,
        paid: PaidWith,
        now: TimeSnapshot,
    ): Transition {
        val session = state.session
        val snoozeEnd = Deadline.after(now, session.config.snoozeLengthMinutes.minutes)
        val snoozed =
            session.withoutTimers().copy(
                snoozesGranted = session.snoozesGranted + 1,
                checkRun = session.checkRun.restart(),
                paying = null,
                unlocking = false,
                paymentPending = false,
                snoozeEnd = snoozeEnd,
                paid = session.paid + listOfNotNull(paid.price),
            )
        val grant =
            GrantLedgerEntry(
                token = paid.token,
                sessionId = session.sessionId,
                alarmId = session.config.alarmId,
                productId = paid.productId,
                snoozeNumber = snoozed.snoozesGranted,
                orderId = paid.orderId,
                status = LedgerStatus.Granted,
                createdAt = Instant.fromEpochMilliseconds(now.wallMillis),
            )
        return Transition(
            SessionState.Snoozed(snoozed),
            listOf(
                SessionEffect.PersistGrant(grant),
                SessionEffect.StopSound,
                SessionEffect.ArmSlot(snoozeEnd),
                SessionEffect.Consume(paid.token),
            ),
        )
    }

    private fun offer(state: Ring): SnoozeOffer? = (availability(state.session) as? SnoozeAvailability.Available)?.offer
}

/**
 * The payment behind a paid snooze: the [productId] bought, its [token], Play's [orderId] and what it cost ([price],
 * Story 4.7, for `SessionData.paid`) when known.
 */
internal class PaidWith(
    val productId: String,
    val token: PurchaseToken,
    val orderId: String? = null,
    val price: Money? = null,
)

/** [this] with no purchase in flight: `paying` and `unlocking` are cleared together. */
internal fun SessionData.withoutPayment(): SessionData = copy(paying = null, unlocking = false)
