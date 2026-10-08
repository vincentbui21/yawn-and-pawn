package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.session.PurchaseToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Purchases over time: each delivery is decided, and the decision is applied the way Stories 4.10 and 4.11 will apply
 * it. The grant ledger (`runtime.db`) and the purchase records (`app.db`) are separate stores written one at a time, so
 * a test can stop between the two writes. Both survive a restart; the decisions depend on nothing else.
 */
class PurchaseReconcilerSequenceTest {
    @Test
    fun `pending then PURCHASED while the check runs (Grace or Loud) - one Grant, repeats only consume`() {
        listOf(ActiveSessionKind.Grace, ActiveSessionKind.Loud).forEach { kind ->
            val world = World(active(kind))
            assertEquals(PurchaseDecision.Ignore(IgnoreReason.Pending), world.deliver(snapshot(state = PlayPurchaseState.Pending)))
            assertEquals(PurchaseDecision.Grant(abortLaunch = false), world.deliver(snapshot()))
            assertEquals(ActiveSessionKind.Snoozed, world.session?.kind)
            // Play repeats the update, then the wake screen's recovery query lists the token again.
            assertEquals(PurchaseDecision.ConsumeOnly(retryLaunch = false), world.deliver(snapshot()))
            assertEquals(PurchaseDecision.ConsumeOnly(retryLaunch = false), world.deliver(snapshot(), ReconcileContext.Recovery))
            assertEquals(1, world.grants)
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
        assertEquals(PurchaseDecision.Grant(abortLaunch = false), world.deliver(snapshot(), ReconcileContext.Recovery))
        assertEquals(1, world.grants)
    }

    @Test
    fun `a lost callback found when Pay is tapped again - Grant, and no second Play charge`() {
        val world = World(active())
        world.owned += snapshot()
        // The first payment's callback was lost; the user taps Pay again for the same snooze.
        world.pay(NEXT)
        assertEquals(1, world.grants)
        assertEquals(0, world.launches, "the pre-launch grant cancels the launch")
        assertEquals(ActiveSessionKind.Snoozed, world.session?.kind)
    }

    @Test
    fun `the same token decided twice across a restart - one Grant in total`() {
        val world = World(active())
        assertEquals(PurchaseDecision.Grant(abortLaunch = false), world.deliver(snapshot()))
        // The process dies before consume; the ledger and the record survive, the session is restored Snoozed.
        val restored = world.restart()
        assertEquals(PurchaseDecision.ConsumeOnly(retryLaunch = false), restored.deliver(snapshot(), ReconcileContext.Recovery))
        restored.consume(TOKEN)
        assertEquals(PurchaseDecision.Ignore(IgnoreReason.AlreadyHandled), restored.deliver(snapshot(), ReconcileContext.Recovery))
        assertEquals(1, world.grants + restored.grants)
        assertEquals(listOf(TOKEN), restored.consumed)
    }

    @Test
    fun `a crash between the two stores' writes never grants twice`() {
        // After the ledger commit, before the record upsert.
        val beforeRecord = World(active())
        beforeRecord.deliver(snapshot(), writeRecord = false)
        val afterCommit = beforeRecord.restart().deliver(snapshot(), ReconcileContext.Recovery)
        assertEquals(PurchaseDecision.ConsumeOnly(retryLaunch = false), afterCommit)

        // Only the record survives (runtime.db lost, for example cleared): the record alone still says granted.
        val recordOnly = World(active())
        recordOnly.deliver(snapshot())
        recordOnly.ledger.remove(TOKEN)
        assertEquals(PurchaseDecision.ConsumeOnly(retryLaunch = false), recordOnly.restart().deliver(snapshot(), ReconcileContext.Recovery))

        // After the record is marked consumed, before the ledger row is deleted: a repeat consume, then nothing.
        val beforeDelete = World(active())
        beforeDelete.deliver(snapshot())
        beforeDelete.markConsumed(TOKEN)
        val restored = beforeDelete.restart()
        assertEquals(PurchaseDecision.ConsumeOnly(retryLaunch = false), restored.deliver(snapshot(), ReconcileContext.Recovery))
        restored.consume(TOKEN)
        assertEquals(PurchaseDecision.Ignore(IgnoreReason.AlreadyHandled), restored.deliver(snapshot(), ReconcileContext.Recovery))
        assertEquals(listOf(1, 1, 1, 0), listOf(beforeRecord.grants, recordOnly.grants, beforeDelete.grants, restored.grants))
    }

    @Test
    fun `a duplicate update right after the grant - ConsumeOnly, one Grant`() {
        val world = World(active())
        assertEquals(PurchaseDecision.Grant(abortLaunch = false), world.deliver(snapshot()))
        // The snooze ended and the alarm rings again, now expecting the next product: the old token still only consumes.
        world.session = active(expectedNext = "snooze_usd_04")
        assertEquals(PurchaseDecision.ConsumeOnly(retryLaunch = false), world.deliver(snapshot()))
        assertEquals(1, world.grants)
    }

    @Test
    fun `PRD UJ4 - a payment that completes after the session is reused the next morning, once`() {
        val world = World(active())
        // Play returned an error; the user got up and the session completed. The payment clears later.
        world.session = null
        assertEquals(PurchaseDecision.LeaveForAutoRefund, world.deliver(snapshot(), ReconcileContext.Recovery))
        // Next morning a new session rings and the next snooze costs the same product.
        world.session = active(sessionId = "session-tomorrow")
        assertEquals(PurchaseDecision.LeaveForAutoRefund, world.deliver(snapshot(), ReconcileContext.Recovery))
        assertEquals(PurchaseDecision.OfferReuse(recordStrandedFirst = false), world.deliver(snapshot(), ReconcileContext.PreLaunch(NEXT)))
        // "Use it": Snoozed and the ledger row in one commit, then the record turns reused.
        world.acceptReuse(TOKEN)
        assertEquals(ActiveSessionKind.Snoozed, world.session?.kind)
        assertEquals(PurchaseDecision.ConsumeOnly(retryLaunch = false), world.deliver(snapshot(), ReconcileContext.Recovery))
        world.consume(TOKEN)
        assertEquals(PurchaseDecision.Ignore(IgnoreReason.AlreadyHandled), world.deliver(snapshot(), ReconcileContext.Recovery))
        assertEquals(1, world.grants)
        assertEquals(listOf(TOKEN), world.consumed)
        assertEquals(mapOf(TOKEN to RecordStatus.Reused), world.records)
    }

    @Test
    fun `PRD UJ4 with no recovery before the next Pay - recorded stranded first, so the reuse ends with one reused record`() {
        // The payment cleared while the app was closed: no recovery ran, so there is no record yet.
        val world = World(active(sessionId = "session-tomorrow"))
        val offer = world.deliver(snapshot(profileId = "session-yesterday"), ReconcileContext.PreLaunch(NEXT))
        assertEquals(PurchaseDecision.OfferReuse(recordStrandedFirst = true), offer)
        assertEquals(RecordStatus.Stranded, world.records[TOKEN])
        world.acceptReuse(TOKEN)
        world.consume(TOKEN)
        assertEquals(mapOf(TOKEN to RecordStatus.Reused), world.records)
        assertEquals(1, world.grants)
        // markReused starts only from stranded (Story 4.10): without the first record the reuse would stop halfway.
        assertFailsWith<IllegalStateException> { World(active()).acceptReuse(TOKEN) }
    }

    @Test
    fun `ITEM_ALREADY_OWNED for a token granted but not consumed - consume, then the launch is retried`() {
        val world = World(active())
        assertEquals(PurchaseDecision.Grant(abortLaunch = false), world.deliver(snapshot()))
        // Consume failed (offline). The snooze ends at the same price tier, for example a second alarm's session.
        world.session = active(sessionId = "session-2")
        assertEquals(PurchaseDecision.ConsumeOnly(retryLaunch = true), world.deliver(snapshot(), ReconcileContext.AlreadyOwned(NEXT)))
        world.consume(TOKEN)
        assertEquals(PurchaseDecision.Ignore(IgnoreReason.AlreadyHandled), world.deliver(snapshot(), ReconcileContext.AlreadyOwned(NEXT)))
        assertEquals(1, world.grants)
    }

    @Test
    fun `another install's payment on the same account is never recorded, consumed, reused or granted here`() {
        val world = World(active())
        val tablet = snapshot(accountId = OTHER_INSTALL)
        listOf(ReconcileContext.Update, ReconcileContext.Recovery, ReconcileContext.PreLaunch(NEXT), ReconcileContext.AlreadyOwned(NEXT))
            .forEach { context -> assertEquals(PurchaseDecision.Ignore(IgnoreReason.OtherInstall), world.deliver(tablet, context)) }
        assertTrue(world.records.isEmpty() && world.ledger.isEmpty() && world.consumed.isEmpty())
        assertEquals(0, world.grants)
    }

    /** The stores Stories 4.10 and 4.11 own, reduced to what the reconciler reads. */
    private class World(
        var session: ActiveSessionSummary?,
        val ledger: MutableMap<PurchaseToken, LedgerStatus> = mutableMapOf(),
        val records: MutableMap<PurchaseToken, RecordStatus> = mutableMapOf(),
    ) {
        var grants = 0
        var launches = 0
        val consumed = mutableListOf<PurchaseToken>()

        /** What `queryPurchasesAsync` returns. */
        val owned = mutableListOf<PurchaseSnapshot>()

        /**
         * Decides [purchase] and applies the decision. A grant commits the ledger row with Snoozed, then upserts the
         * record unless [writeRecord] is false (the process died in between).
         */
        fun deliver(
            purchase: PurchaseSnapshot,
            context: ReconcileContext = ReconcileContext.Update,
            writeRecord: Boolean = true,
        ): PurchaseDecision {
            val token = purchase.token
            val ledgerStatus = ledger[token] ?: LedgerStatus.Absent
            val recordStatus = records[token] ?: RecordStatus.Absent
            val decision = PurchaseReconciler.decide(ReconcileInput(purchase, session, ledgerStatus, recordStatus, context, INSTALL))
            when (decision) {
                is PurchaseDecision.Grant -> {
                    commitSnooze(token)
                    if (writeRecord) records[token] = RecordStatus.Granted
                }

                PurchaseDecision.LeaveForAutoRefund -> {
                    records[token] = RecordStatus.Stranded
                }

                is PurchaseDecision.OfferReuse -> {
                    if (decision.recordStrandedFirst) records[token] = RecordStatus.Stranded
                }

                // Consuming is a separate step (it can fail).
                is PurchaseDecision.ConsumeOnly, is PurchaseDecision.Ignore -> {
                    Unit
                }
            }
            return decision
        }

        /** The coordinator's "launch billing": the owned purchases first, then Play's sheet unless one of them paid. */
        fun pay(product: String) {
            val decisions = owned.map { deliver(it, ReconcileContext.PreLaunch(product)) }
            val abort = decisions.any { (it as? PurchaseDecision.Grant)?.abortLaunch == true || it is PurchaseDecision.OfferReuse }
            if (!abort) launches++
        }

        /** "Use it": Snoozed and a ledger row in one commit, then `markReused`, which starts only from stranded. */
        fun acceptReuse(token: PurchaseToken) {
            check(records[token] == RecordStatus.Stranded) { "markReused refuses ${records[token]}" }
            commitSnooze(token)
            records[token] = RecordStatus.Reused
        }

        fun consume(token: PurchaseToken) {
            consumed += token
            markConsumed(token)
            ledger.remove(token)
        }

        /** A reused record keeps its status, so history still shows the reuse. */
        fun markConsumed(token: PurchaseToken) {
            if (records[token] != RecordStatus.Reused) records[token] = RecordStatus.Consumed
        }

        /** A new process: the stored ledger, records and session, nothing in memory. */
        fun restart(): World = World(session, ledger.toMutableMap(), records.toMutableMap())

        private fun commitSnooze(token: PurchaseToken) {
            grants++
            ledger[token] = LedgerStatus.Granted
            session = session?.copy(kind = ActiveSessionKind.Snoozed)
        }
    }

    private companion object {
        val TOKEN = PurchaseToken("token-1")
    }
}
