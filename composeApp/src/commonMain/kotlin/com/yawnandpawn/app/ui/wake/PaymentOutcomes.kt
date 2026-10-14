package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.payment_cancelled
import com.yawnandpawn.app.ui.resources.payment_error
import com.yawnandpawn.app.ui.resources.payment_offline
import com.yawnandpawn.app.ui.resources.payment_pending_body
import com.yawnandpawn.app.ui.resources.payment_unlock_failed
import org.jetbrains.compose.resources.StringResource

/** What `button-snooze` offers after a payment outcome, back on the ring or the check. */
enum class SnoozeAfterPayment {
    /** Still on sale at the same price. */
    Offered,

    /** "Snooze unavailable: offline" until connectivity returns (Story 4.7's live policy), then back in place. */
    UnavailableOffline,

    /** "Snooze unavailable: payment pending" for the rest of this session. */
    UnavailablePaymentPending,
}

/** One row of the Payment outcomes table: the wake snackbar's [message] and what Snooze offers next. */
data class PaymentOutcomeRow(
    val message: WakeMessage,
    val snooze: SnoozeAfterPayment,
)

/**
 * The Payment outcomes table (EXPERIENCE.md > State Patterns, FR-RNG-4/5/7) for the engine's `ShowPurchaseOutcome`: an
 * exhaustive `when`, so a new outcome cannot compile without its row. Every outcome where nothing was charged says
 * "No charge."; [PurchaseOutcome.Unconfirmed] (Play's result never came, a charge can still follow and grant) shows the
 * pending message instead (Story 4.14 review, default taken, owner can change). A paid snooze has no outcome at all: the
 * engine goes to Snoozed and no message shows.
 */
fun paymentOutcome(outcome: PurchaseOutcome): PaymentOutcomeRow =
    when (outcome) {
        PurchaseOutcome.UnlockFailed -> PaymentOutcomeRow(WakeMessage.UnlockFailed, SnoozeAfterPayment.Offered)
        PurchaseOutcome.Cancelled -> PaymentOutcomeRow(WakeMessage.PaymentCancelled, SnoozeAfterPayment.Offered)
        PurchaseOutcome.Failed -> PaymentOutcomeRow(WakeMessage.PaymentError, SnoozeAfterPayment.Offered)
        PurchaseOutcome.Offline -> PaymentOutcomeRow(WakeMessage.PaymentOffline, SnoozeAfterPayment.UnavailableOffline)
        PurchaseOutcome.Unconfirmed -> PaymentOutcomeRow(WakeMessage.PaymentPending, SnoozeAfterPayment.Offered)
    }

/** The Pending row (`ShowPaymentPending`): the pending message, and Snooze unavailable for this session. */
val PaymentPendingRow: PaymentOutcomeRow = PaymentOutcomeRow(WakeMessage.PaymentPending, SnoozeAfterPayment.UnavailablePaymentPending)

/** The row an engine [effect] shows on the wake screen, or null: only `ShowPurchaseOutcome` and `ShowPaymentPending` have one. */
fun paymentOutcomeOf(effect: SessionEffect): PaymentOutcomeRow? =
    when (effect) {
        is SessionEffect.ShowPurchaseOutcome -> paymentOutcome(effect.outcome)
        SessionEffect.ShowPaymentPending -> PaymentPendingRow
        else -> null
    }

/** The one string resource of each wake message (EXPERIENCE.md Key strings, verbatim). */
val WakeMessage.text: StringResource
    get() =
        when (this) {
            WakeMessage.UnlockFailed -> Res.string.payment_unlock_failed
            WakeMessage.PaymentCancelled -> Res.string.payment_cancelled
            WakeMessage.PaymentError -> Res.string.payment_error
            WakeMessage.PaymentOffline -> Res.string.payment_offline
            WakeMessage.PaymentPending -> Res.string.payment_pending_body
        }
