package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.payment_cancelled
import com.yawnandpawn.app.ui.resources.payment_error
import com.yawnandpawn.app.ui.resources.payment_offline
import com.yawnandpawn.app.ui.resources.payment_pending_body
import com.yawnandpawn.app.ui.resources.payment_unlock_failed
import org.jetbrains.compose.resources.StringResource

/**
 * What a payment came to, as the engine reports it to the wake screen (Story 4.14): the keys of the Payment outcomes
 * table in EXPERIENCE.md.
 */
sealed interface PaymentResult {
    /** `ShowPurchaseOutcome`: the payment failed or was cancelled, nothing was charged. */
    data class NotCharged(
        val outcome: PurchaseOutcome,
    ) : PaymentResult

    /** `ShowPaymentPending`: Play has not confirmed the payment yet. */
    data object Pending : PaymentResult

    /** `PurchaseGranted`: the snooze is paid. */
    data object Purchased : PaymentResult

    /** Already paid, "Use it" (`ReuseAccepted`): the earlier payment pays this snooze. */
    data object EarlierPaymentUsed : PaymentResult

    /** Already paid, "Not now" (`ReuseDeclined`): Google refunds the earlier payment. */
    data object EarlierPaymentDeclined : PaymentResult
}

/** Where the user is after a payment result. */
enum class PaymentReturn {
    /** Back to the ringing screen, or to the check the user was on. */
    RingingOrCheck,

    /** The Snoozed surface (Story 4.15). */
    Snoozed,
}

/** What `button-snooze` offers after a payment result. */
enum class SnoozeAfterPayment {
    /** Still on sale at the same price. */
    Offered,

    /** "Snooze unavailable: offline" until connectivity returns (Story 4.7's policy), then back in place. */
    UnavailableOffline,

    /** "Snooze unavailable: payment pending" for the rest of this session. */
    UnavailablePaymentPending,

    /** "An earlier {price} payment is being refunded" at that price. */
    EarlierPaymentRefunding,

    /** The snooze was paid: the next ring offers B × (N + 1) (Story 4.15). */
    NotApplicable,
}

/** One row of the Payment outcomes table: the snackbar [message] (none on a paid morning), the return and Snooze. */
data class PaymentOutcomeRow(
    val message: WakeMessage?,
    val returnsTo: PaymentReturn,
    val snooze: SnoozeAfterPayment,
)

/**
 * The Payment outcomes table (EXPERIENCE.md > State Patterns, FR-RNG-4/5/7): every result maps to exactly one row, by an
 * exhaustive `when`, so a new result cannot compile without one. Every result that charged nothing says "No charge.";
 * a paid snooze shows no message at all.
 */
fun paymentOutcome(result: PaymentResult): PaymentOutcomeRow =
    when (result) {
        is PaymentResult.NotCharged -> {
            when (result.outcome) {
                PurchaseOutcome.UnlockFailed -> {
                    retry(WakeMessage.UnlockFailed)
                }

                PurchaseOutcome.Cancelled -> {
                    retry(WakeMessage.PaymentCancelled)
                }

                PurchaseOutcome.Failed -> {
                    retry(WakeMessage.PaymentError)
                }

                PurchaseOutcome.Offline -> {
                    PaymentOutcomeRow(WakeMessage.PaymentOffline, PaymentReturn.RingingOrCheck, SnoozeAfterPayment.UnavailableOffline)
                }
            }
        }

        PaymentResult.Pending -> {
            PaymentOutcomeRow(WakeMessage.PaymentPending, PaymentReturn.RingingOrCheck, SnoozeAfterPayment.UnavailablePaymentPending)
        }

        PaymentResult.Purchased, PaymentResult.EarlierPaymentUsed -> {
            PaymentOutcomeRow(null, PaymentReturn.Snoozed, SnoozeAfterPayment.NotApplicable)
        }

        PaymentResult.EarlierPaymentDeclined -> {
            PaymentOutcomeRow(null, PaymentReturn.RingingOrCheck, SnoozeAfterPayment.EarlierPaymentRefunding)
        }
    }

private fun retry(message: WakeMessage) = PaymentOutcomeRow(message, PaymentReturn.RingingOrCheck, SnoozeAfterPayment.Offered)

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
