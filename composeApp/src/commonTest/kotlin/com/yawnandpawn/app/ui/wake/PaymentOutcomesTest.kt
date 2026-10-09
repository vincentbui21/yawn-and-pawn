package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.billing.LaunchResult
import com.yawnandpawn.app.core.billing.PurchaseUpdate
import com.yawnandpawn.app.core.session.PurchaseFailureKind
import com.yawnandpawn.app.core.session.PurchaseOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Story 4.14: the Payment outcomes table (EXPERIENCE.md > State Patterns), one row per domain result. */
class PaymentOutcomesTest {
    private fun row(
        message: WakeMessage?,
        returnsTo: PaymentReturn,
        snooze: SnoozeAfterPayment,
    ) = PaymentOutcomeRow(message, returnsTo, snooze)

    @Test
    fun `each result maps to its row of the EXPERIENCE table`() {
        val back = PaymentReturn.RingingOrCheck
        val expected =
            mapOf(
                PaymentResult.NotCharged(PurchaseOutcome.UnlockFailed) to row(WakeMessage.UnlockFailed, back, SnoozeAfterPayment.Offered),
                PaymentResult.NotCharged(PurchaseOutcome.Cancelled) to row(WakeMessage.PaymentCancelled, back, SnoozeAfterPayment.Offered),
                PaymentResult.NotCharged(PurchaseOutcome.Failed) to row(WakeMessage.PaymentError, back, SnoozeAfterPayment.Offered),
                PaymentResult.NotCharged(PurchaseOutcome.Offline) to
                    row(WakeMessage.PaymentOffline, back, SnoozeAfterPayment.UnavailableOffline),
                PaymentResult.Pending to row(WakeMessage.PaymentPending, back, SnoozeAfterPayment.UnavailablePaymentPending),
                PaymentResult.Purchased to row(null, PaymentReturn.Snoozed, SnoozeAfterPayment.NotApplicable),
                PaymentResult.EarlierPaymentUsed to row(null, PaymentReturn.Snoozed, SnoozeAfterPayment.NotApplicable),
                PaymentResult.EarlierPaymentDeclined to row(null, back, SnoozeAfterPayment.EarlierPaymentRefunding),
            )
        expected.forEach { (result, row) -> assertEquals(row, paymentOutcome(result), "$result") }
        // Every PurchaseOutcome is a key above.
        assertEquals(PurchaseOutcome.entries.map(PaymentResult::NotCharged).toSet(), expected.keys.filterIsInstance<PaymentResult.NotCharged>().toSet())
    }

    @Test
    fun `every result that charged nothing has a message, every message is used once, and a paid snooze has none`() {
        val notCharged = PurchaseOutcome.entries.map(PaymentResult::NotCharged) + PaymentResult.Pending + PaymentResult.EarlierPaymentDeclined
        val messages = notCharged.mapNotNull { paymentOutcome(it).message }
        // "Not now" returns to the ring with the refund label on Snooze and no message (EXPERIENCE.md).
        assertEquals(notCharged.size - 1, messages.size)
        assertEquals(WakeMessage.entries.toSet(), messages.toSet())
        assertEquals(messages.size, messages.toSet().size, "one message per result")
        assertNull(paymentOutcome(PaymentResult.Purchased).message)
        assertNull(paymentOutcome(PaymentResult.EarlierPaymentUsed).message)
    }

    /**
     * What a failed `PurchaseFailed(kind)` becomes in the reducer (AD-2 R19, `PurchaseRules.outcomeOf`, tested in core).
     * Exhaustive: a new kind does not compile without its outcome.
     */
    private fun outcomeOf(kind: PurchaseFailureKind): PurchaseOutcome =
        when (kind) {
            PurchaseFailureKind.Offline -> PurchaseOutcome.Offline
            PurchaseFailureKind.UnlockFailed -> PurchaseOutcome.UnlockFailed
            PurchaseFailureKind.Error -> PurchaseOutcome.Failed
        }

    /**
     * The result each `Billing.launch` answer comes to on the wake screen (Story 4.11's coordinator), or null when it is
     * not a result yet: the sheet is open, or Play's `ITEM_ALREADY_OWNED` is reconciled again (it then ends as one of the
     * other results). Exhaustive: a new launch result does not compile without its row.
     */
    private fun resultOf(launch: LaunchResult): PaymentResult? =
        when (launch) {
            LaunchResult.Launched, LaunchResult.ItemAlreadyOwned -> null
            LaunchResult.Cancelled -> PaymentResult.NotCharged(PurchaseOutcome.Cancelled)
            is LaunchResult.Failed -> PaymentResult.NotCharged(outcomeOf(launch.kind))
        }

    /** The same for a purchase update (Purchases go through the reconciler: Grant, Pending, stranded or ignored). */
    private fun resultOf(update: PurchaseUpdate): PaymentResult? =
        when (update) {
            is PurchaseUpdate.Purchases, PurchaseUpdate.ItemAlreadyOwned -> null
            PurchaseUpdate.Cancelled -> PaymentResult.NotCharged(PurchaseOutcome.Cancelled)
            is PurchaseUpdate.Failed -> PaymentResult.NotCharged(outcomeOf(update.kind))
        }

    @Test
    fun `every launch and update failure kind has a row with No charge`() {
        val launches = listOf(LaunchResult.Cancelled) + PurchaseFailureKind.entries.map(LaunchResult::Failed)
        val updates = listOf(PurchaseUpdate.Cancelled) + PurchaseFailureKind.entries.map(PurchaseUpdate::Failed)
        val results = launches.mapNotNull(::resultOf) + updates.mapNotNull(::resultOf)
        assertEquals(launches.size + updates.size, results.size)
        results.forEach { result ->
            val row = paymentOutcome(result)
            assertEquals(PaymentReturn.RingingOrCheck, row.returnsTo, "$result")
            checkNotNull(row.message) { "$result has a message" }
        }
        assertNull(resultOf(LaunchResult.Launched))
        assertEquals(
            mapOf(
                PurchaseFailureKind.Offline to WakeMessage.PaymentOffline,
                PurchaseFailureKind.UnlockFailed to WakeMessage.UnlockFailed,
                PurchaseFailureKind.Error to WakeMessage.PaymentError,
            ),
            PurchaseFailureKind.entries.associateWith { paymentOutcome(PaymentResult.NotCharged(outcomeOf(it))).message },
        )
    }
}
