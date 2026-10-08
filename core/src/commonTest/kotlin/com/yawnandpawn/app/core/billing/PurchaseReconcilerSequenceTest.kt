package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.session.PurchaseToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Purchases over time: each delivery is decided, and the decision is applied the way Stories 4.10 and 4.11 will apply
 * it (grant → ledger row and record granted, session Snoozed; consume → record consumed, ledger row gone; leave →
 * record stranded). The ledger and the records survive a restart; the decisions do not depend on anything else.
 */
class PurchaseReconcilerSequenceTest {
    @Test
    fun `pending then PURCHASED while the check runs (Grace or Loud) - one Grant`() {
        listOf(ActiveSessionKind.Grace, ActiveSessionKind.Loud).forEach { kind ->
            val world = World(active(kind))
            assertEquals(PurchaseDecision.Ignore(IgnoreReason.Pending), world.deliver(snapshot(state = PlayPurchaseState.Pending)))
            assertEquals(PurchaseDecision.Grant, world.deliver(snapshot()))
            assertEquals(1, world.grants)
            assertEquals(ActiveSessionKind.Snoozed, world.session?.kind)
        }
    }

    @Test
    fun `pending then PURCHASED after the session ended - stranded, never consumed`() {
        val world = World(active())
        world.deliver(snapshot(state = PlayPurchaseState.Pending))
        world.session = active(ActiveSessionKind.Completed)
        assertEquals(PurchaseDecision.LeaveForAutoRefund, world.deliver(snapshot()))
        // The session row is gone by the next recovery; the token is still left alone.
        world.session = null
        assertEquals(PurchaseDecision.LeaveForAutoRefund, world.deliver(snapshot(), ReconcileContext.Recovery))
        assertEquals(RecordStatus.Stranded, world.records[TOKEN])
        assertEquals(0, world.grants)
        assertTrue(world.consumed.isEmpty())
    }

    @Test
    fun `a lost callback is found by recovery for the active session - Grant`() {
        val world = World(active())
        // The update never arrived; the wake screen opens and queries.
        assertEquals(PurchaseDecision.Grant, world.deliver(snapshot(), ReconcileContext.Recovery))
        assertEquals(1, world.grants)
    }

    @Test
    fun `the same token decided twice across a restart - one Grant in total`() {
        val world = World(active())
        assertEquals(PurchaseDecision.Grant, world.deliver(snapshot()))
        // The process dies before consume; the ledger and the record survive, the session is restored Snoozed.
        val restored = world.restart()
        assertEquals(PurchaseDecision.ConsumeOnly(retryLaunch = false), restored.deliver(snapshot(), ReconcileContext.Recovery))
        restored.consume(TOKEN)
        assertEquals(PurchaseDecision.Ignore(IgnoreReason.AlreadyHandled), restored.deliver(snapshot(), ReconcileContext.Recovery))
        assertEquals(1, world.grants + restored.grants)
        assertEquals(listOf(TOKEN), restored.consumed)
    }

    @Test
    fun `a duplicate update right after the grant - ConsumeOnly, one Grant`() {
        val world = World(active())
        assertEquals(PurchaseDecision.Grant, world.deliver(snapshot()))
        // The snooze ended and the alarm rings again, now expecting the next product: the old token still only consumes.
        world.session = active(expectedNext = "snooze_usd_04")
        assertEquals(PurchaseDecision.ConsumeOnly(retryLaunch = false), world.deliver(snapshot()))
        assertEquals(1, world.grants)
    }

    @Test
    fun `PRD UJ4 - a payment that completes after the session is offered again the next morning`() {
        val world = World(active())
        // Play returned an error; the user got up and the session completed. The payment clears later.
        world.session = null
        assertEquals(PurchaseDecision.LeaveForAutoRefund, world.deliver(snapshot(), ReconcileContext.Recovery))
        // Next morning a new session rings and the next snooze costs the same product.
        world.session = active(sessionId = "session-tomorrow")
        assertEquals(PurchaseDecision.LeaveForAutoRefund, world.deliver(snapshot(), ReconcileContext.Recovery))
        assertEquals(PurchaseDecision.OfferReuse, world.deliver(snapshot(), ReconcileContext.PreLaunch(NEXT)))
        assertEquals(PurchaseDecision.OfferReuse, world.deliver(snapshot(), ReconcileContext.AlreadyOwned(NEXT)))
        assertEquals(0, world.grants)
        assertTrue(world.consumed.isEmpty())
    }

    @Test
    fun `ITEM_ALREADY_OWNED for a token granted but not consumed - consume, then the launch is retried`() {
        val world = World(active())
        assertEquals(PurchaseDecision.Grant, world.deliver(snapshot()))
        // Consume failed (offline). The snooze ends at the same price tier, for example a second alarm's session.
        world.session = active(sessionId = "session-2")
        assertEquals(PurchaseDecision.ConsumeOnly(retryLaunch = true), world.deliver(snapshot(), ReconcileContext.AlreadyOwned(NEXT)))
        world.consume(TOKEN)
        assertEquals(PurchaseDecision.Ignore(IgnoreReason.AlreadyHandled), world.deliver(snapshot(), ReconcileContext.AlreadyOwned(NEXT)))
        assertEquals(1, world.grants)
    }

    /** The stores Stories 4.10 and 4.11 own, reduced to what the reconciler reads. */
    private class World(
        var session: ActiveSessionSummary?,
        val ledger: MutableMap<PurchaseToken, LedgerStatus> = mutableMapOf(),
        val records: MutableMap<PurchaseToken, RecordStatus> = mutableMapOf(),
    ) {
        var grants = 0
        val consumed = mutableListOf<PurchaseToken>()

        fun deliver(
            purchase: PurchaseSnapshot,
            context: ReconcileContext = ReconcileContext.Update,
        ): PurchaseDecision {
            val token = purchase.token
            val ledgerStatus = ledger[token] ?: LedgerStatus.Absent
            val recordStatus = records[token] ?: RecordStatus.Absent
            val decision = PurchaseReconciler.decide(ReconcileInput(purchase, session, ledgerStatus, recordStatus, context))
            when (decision) {
                PurchaseDecision.Grant -> {
                    grants++
                    ledger[token] = LedgerStatus.Granted
                    records[token] = RecordStatus.Granted
                    session = session?.copy(kind = ActiveSessionKind.Snoozed)
                }

                PurchaseDecision.LeaveForAutoRefund -> {
                    records[token] = RecordStatus.Stranded
                }

                // Consuming is a separate step (it can fail); OfferReuse waits for the user.
                is PurchaseDecision.ConsumeOnly, PurchaseDecision.OfferReuse, is PurchaseDecision.Ignore -> {
                    Unit
                }
            }
            return decision
        }

        fun consume(token: PurchaseToken) {
            consumed += token
            records[token] = RecordStatus.Consumed
            ledger.remove(token)
        }

        /** A new process: the stored ledger, records and session, nothing in memory. */
        fun restart(): World = World(session, ledger.toMutableMap(), records.toMutableMap())
    }

    private companion object {
        val TOKEN = PurchaseToken("token-1")
    }
}
