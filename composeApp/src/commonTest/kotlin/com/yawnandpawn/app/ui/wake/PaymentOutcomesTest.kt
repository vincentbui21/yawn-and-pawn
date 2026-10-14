package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.core.session.SessionEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Story 4.14: the Payment outcomes table (EXPERIENCE.md > State Patterns), one row per engine outcome. The chain from
 * Play's results (`LaunchResult`, `PurchaseUpdate`) through the coordinator, the engine and the wake screen is tested end to
 * end in `PaymentOutcomeMessagesTest`.
 */
class PaymentOutcomesTest {
    @Test
    fun `each outcome maps to its row of the EXPERIENCE table`() {
        assertEquals(
            mapOf(
                PurchaseOutcome.UnlockFailed to PaymentOutcomeRow(WakeMessage.UnlockFailed, SnoozeAfterPayment.Offered),
                PurchaseOutcome.Cancelled to PaymentOutcomeRow(WakeMessage.PaymentCancelled, SnoozeAfterPayment.Offered),
                PurchaseOutcome.Failed to PaymentOutcomeRow(WakeMessage.PaymentError, SnoozeAfterPayment.Offered),
                PurchaseOutcome.Offline to PaymentOutcomeRow(WakeMessage.PaymentOffline, SnoozeAfterPayment.UnavailableOffline),
                // Review: a lost Play result may still charge, so it is the pending message, never "No charge.".
                PurchaseOutcome.Unconfirmed to PaymentOutcomeRow(WakeMessage.PaymentPending, SnoozeAfterPayment.Offered),
            ),
            PurchaseOutcome.entries.associateWith(::paymentOutcome),
        )
        assertEquals(PaymentOutcomeRow(WakeMessage.PaymentPending, SnoozeAfterPayment.UnavailablePaymentPending), PaymentPendingRow)
    }

    @Test
    fun `only the outcome and pending effects have a row`() {
        PurchaseOutcome.entries.forEach { outcome ->
            assertEquals(paymentOutcome(outcome), paymentOutcomeOf(SessionEffect.ShowPurchaseOutcome(outcome)))
        }
        assertEquals(PaymentPendingRow, paymentOutcomeOf(SessionEffect.ShowPaymentPending))
        listOf(SessionEffect.HideReuseSheet, SessionEffect.ShowReuseSheet("snooze_usd_01")).forEach { assertNull(paymentOutcomeOf(it)) }
    }

    @Test
    fun `every message is used, each by its own outcome`() {
        val messages = PurchaseOutcome.entries.map { paymentOutcome(it).message } + PaymentPendingRow.message
        assertEquals(WakeMessage.entries.toSet(), messages.toSet())
    }
}
