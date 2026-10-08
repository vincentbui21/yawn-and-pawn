package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.checkStates
import com.yawnandpawn.app.core.session.noTimers
import com.yawnandpawn.app.core.session.ringSession
import com.yawnandpawn.app.core.session.snoozedSession
import com.yawnandpawn.app.core.session.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every combination of the inputs (about 120,000), checked against the money rules that must hold whatever the table
 * says, in both directions where the rule is an "if and only if": a payment for the ringing session is always granted,
 * nothing is granted twice or in test mode, nothing is consumed unless it granted a snooze, another install's payments
 * are never touched, and reuse is only offered for the product being bought.
 */
class PurchaseReconcilerInvariantsTest {
    private val sessions: List<ActiveSessionSummary?> =
        listOf(null) +
            ActiveSessionKind.entries.flatMap { kind ->
                listOf(NEXT, OTHER_PRODUCT, null).flatMap { next -> listOf(false, true).map { active(kind, next, it) } }
            }

    private val snapshots: List<PurchaseSnapshot> =
        PlayPurchaseState.entries.flatMap { state ->
            listOf(NEXT, OTHER_PRODUCT).flatMap { product ->
                listOf(ACTIVE, OTHER_SESSION, null).flatMap { profileId ->
                    listOf(INSTALL, OTHER_INSTALL, null).map { accountId -> snapshot(state, product, profileId, accountId = accountId) }
                }
            }
        }

    private val inputs: List<ReconcileInput> =
        snapshots.flatMap { purchase ->
            sessions.flatMap { session ->
                LedgerStatus.entries.flatMap { ledger ->
                    RecordStatus.entries.flatMap { record ->
                        ALL_CONTEXTS.map { context -> ReconcileInput(purchase, session, ledger, record, context, INSTALL) }
                    }
                }
            }
        }

    private val decided: List<Pair<ReconcileInput, PurchaseDecision>> = inputs.map { it to PurchaseReconciler.decide(it) }

    private val ReconcileInput.purchased: Boolean get() = purchase.purchaseState == PlayPurchaseState.Purchased
    private val ReconcileInput.foreign: Boolean get() = purchase.accountId != null && purchase.accountId != installId
    private val ReconcileInput.launch: Boolean
        get() = context is ReconcileContext.PreLaunch || context is ReconcileContext.AlreadyOwned
    private val ReconcileInput.launchingThis: Boolean
        get() = context == ReconcileContext.PreLaunch(purchase.productId) || context == ReconcileContext.AlreadyOwned(purchase.productId)

    @Test
    fun `every decision is reachable`() {
        assertEquals(
            setOf(
                PurchaseDecision.Grant(abortLaunch = false),
                PurchaseDecision.Grant(abortLaunch = true),
                PurchaseDecision.ConsumeOnly(retryLaunch = false),
                PurchaseDecision.ConsumeOnly(retryLaunch = true),
                PurchaseDecision.LeaveForAutoRefund,
                PurchaseDecision.OfferReuse(recordStrandedFirst = true),
                PurchaseDecision.OfferReuse(recordStrandedFirst = false),
                PurchaseDecision.Ignore(IgnoreReason.OtherInstall),
                PurchaseDecision.Ignore(IgnoreReason.Pending),
                PurchaseDecision.Ignore(IgnoreReason.AlreadyHandled),
            ),
            decided.map { it.second }.toSet(),
        )
    }

    @Test
    fun `must grant - an unseen PURCHASED payment for the ringing, charging session and its next product, in every context`() {
        val mustGrant =
            decided.filter { (input, _) ->
                val session = input.session
                input.purchased &&
                    !input.foreign &&
                    input.ledger == LedgerStatus.Absent &&
                    input.record == RecordStatus.Absent &&
                    session != null &&
                    input.purchase.profileId == session.sessionId &&
                    session.kind.canSnooze &&
                    !session.testMode &&
                    session.expectedNextProductId == input.purchase.productId
            }
        // Every ringing kind, every context, both products (each the next one in some session), both own-install account
        // ids (this install's, and none).
        assertEquals(RING_KINDS.size * ALL_CONTEXTS.size * 2 * 2, mustGrant.size)
        mustGrant.forEach { (input, decision) -> assertEquals(PurchaseDecision.Grant(abortLaunch = input.launch), decision, "$input") }
    }

    @Test
    fun `Grant only for an unseen PURCHASED token of the ringing, charging session and its next product, cancelling any launch`() {
        decided.mapNotNull { (input, decision) -> (decision as? PurchaseDecision.Grant)?.let { input to it } }.forEach { (input, grant) ->
            val session = checkNotNull(input.session)
            assertTrue(input.purchased && !input.foreign)
            assertEquals(LedgerStatus.Absent, input.ledger)
            assertEquals(RecordStatus.Absent, input.record)
            assertEquals(session.sessionId, input.purchase.profileId)
            assertEquals(session.expectedNextProductId, input.purchase.productId)
            assertTrue(session.kind.canSnooze)
            assertFalse(session.testMode)
            assertEquals(input.launch, grant.abortLaunch)
        }
    }

    @Test
    fun `ConsumeOnly exactly for a PURCHASED token this install granted, relaunching only after ITEM_ALREADY_OWNED for it`() {
        decided.forEach { (input, decision) ->
            val grantedRecordOnly = input.ledger == LedgerStatus.Absent && input.record == RecordStatus.Granted && !input.foreign
            val granted = input.purchased && (input.ledger == LedgerStatus.Granted || grantedRecordOnly)
            assertEquals(granted, decision is PurchaseDecision.ConsumeOnly, "$input -> $decision")
            if (decision is PurchaseDecision.ConsumeOnly) {
                assertEquals(input.context == ReconcileContext.AlreadyOwned(input.purchase.productId), decision.retryLaunch)
            }
        }
    }

    @Test
    fun `Ignore exactly for another install's payment, a pending payment, or one already consumed or reused`() {
        decided.forEach { (input, decision) ->
            val otherInstall = input.foreign && input.ledger != LedgerStatus.Granted
            val pending = !otherInstall && !input.purchased
            val handledRecord = input.record == RecordStatus.Consumed || input.record == RecordStatus.Reused
            val handled =
                !otherInstall &&
                    input.purchased &&
                    (input.ledger == LedgerStatus.Consumed || (input.ledger == LedgerStatus.Absent && handledRecord))
            assertEquals(otherInstall, decision == PurchaseDecision.Ignore(IgnoreReason.OtherInstall), "$input -> $decision")
            assertEquals(pending, decision == PurchaseDecision.Ignore(IgnoreReason.Pending), "$input -> $decision")
            assertEquals(handled, decision == PurchaseDecision.Ignore(IgnoreReason.AlreadyHandled), "$input -> $decision")
        }
    }

    @Test
    fun `OfferReuse only for this install's unspent token for the product being bought by a ringing, charging session`() {
        val offers = decided.mapNotNull { (input, decision) -> (decision as? PurchaseDecision.OfferReuse)?.let { input to it } }
        offers.forEach { (input, offer) ->
            val session = checkNotNull(input.session)
            assertTrue(input.launchingThis && input.purchased && !input.foreign)
            assertEquals(LedgerStatus.Absent, input.ledger)
            assertTrue(input.record == RecordStatus.Absent || input.record == RecordStatus.Stranded)
            assertEquals(input.record == RecordStatus.Absent, offer.recordStrandedFirst)
            assertEquals(session.expectedNextProductId, input.purchase.productId)
            assertTrue(session.kind.canSnooze)
            assertFalse(session.testMode)
        }
    }

    @Test
    fun `LeaveForAutoRefund only for this install's PURCHASED tokens that never granted anything`() {
        decided.filter { it.second == PurchaseDecision.LeaveForAutoRefund }.forEach { (input, _) ->
            assertTrue(input.purchased && !input.foreign)
            assertEquals(LedgerStatus.Absent, input.ledger)
            assertTrue(input.record == RecordStatus.Absent || input.record == RecordStatus.Stranded)
        }
    }

    @Test
    fun `a test session never grants or reuses`() {
        decided.filter { it.first.session?.testMode == true }.forEach { (input, decision) ->
            assertTrue(decision !is PurchaseDecision.Grant && decision !is PurchaseDecision.OfferReuse, "$input")
        }
    }

    @Test
    fun `the session summary reads the kind, id and test mode of the stored state`() {
        assertNull(ActiveSessionSummary.of(SessionState.Idle, NEXT))
        val ring = ringSession()
        val expected =
            listOf(
                SessionState.Ringing(ring) to ActiveSessionKind.Ringing,
                checkStates()[0] to ActiveSessionKind.Grace,
                checkStates()[1] to ActiveSessionKind.Loud,
                SessionState.Snoozed(snoozedSession()) to ActiveSessionKind.Snoozed,
                SessionState.Completed(ring.noTimers()) to ActiveSessionKind.Completed,
                SessionState.Missed(ring.noTimers()) to ActiveSessionKind.Missed,
            )
        expected.forEach { (state, kind) ->
            assertEquals(ActiveSessionSummary(ring.sessionId, kind, NEXT, testMode = false), ActiveSessionSummary.of(state, NEXT))
        }
        val test = SessionState.Ringing(ringSession(testConfig(testMode = true)))
        assertEquals(
            ActiveSessionSummary(ring.sessionId, ActiveSessionKind.Ringing, null, testMode = true),
            ActiveSessionSummary.of(test, null),
        )
        assertEquals(RING_KINDS.toSet(), ActiveSessionKind.entries.filter { it.canSnooze }.toSet())
    }

    @Test
    fun `a snapshot never prints its token, and compares by value`() {
        assertFalse("token-1" in snapshot().toString())
        val input = ReconcileInput(snapshot(), active(), LedgerStatus.Absent, RecordStatus.Absent, ReconcileContext.Update, INSTALL)
        assertFalse("token-1" in input.toString())
        assertTrue("accountId=$INSTALL" in snapshot().toString())
        assertEquals(snapshot(), snapshot())
        assertEquals(snapshot().hashCode(), snapshot().hashCode())
        assertFalse(snapshot() == snapshot(accountId = OTHER_INSTALL))
        assertFalse(snapshot().equals("token-1"))
    }
}
