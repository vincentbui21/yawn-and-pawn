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
 * Every combination of the inputs (about 40,000), checked against the money rules that must hold whatever the table
 * says: nothing is granted twice or in test mode, nothing is consumed unless it granted a snooze, and reuse is only
 * offered for the product being bought.
 */
class PurchaseReconcilerInvariantsTest {
    private val sessions: List<ActiveSessionSummary?> =
        listOf(null) +
            ActiveSessionKind.entries.flatMap { kind ->
                listOf(NEXT, OTHER_PRODUCT, null).flatMap { next -> listOf(false, true).map { active(kind, next, it) } }
            }

    private val inputs: List<ReconcileInput> =
        PlayPurchaseState.entries.flatMap { state ->
            listOf(NEXT, OTHER_PRODUCT).flatMap { product ->
                listOf(ACTIVE, OTHER_SESSION, null).flatMap { profileId ->
                    sessions.flatMap { session ->
                        LedgerStatus.entries.flatMap { ledger ->
                            RecordStatus.entries.flatMap { record ->
                                ALL_CONTEXTS.map { context ->
                                    ReconcileInput(snapshot(state, product, profileId), session, ledger, record, context)
                                }
                            }
                        }
                    }
                }
            }
        }

    private val decided: List<Pair<ReconcileInput, PurchaseDecision>> = inputs.map { it to PurchaseReconciler.decide(it) }

    @Test
    fun `every decision is reachable and the same input always gets the same decision`() {
        val kinds = decided.map { (_, decision) -> decision }.toSet()
        assertEquals(
            setOf(
                PurchaseDecision.Grant,
                PurchaseDecision.ConsumeOnly(retryLaunch = false),
                PurchaseDecision.ConsumeOnly(retryLaunch = true),
                PurchaseDecision.LeaveForAutoRefund,
                PurchaseDecision.OfferReuse,
                PurchaseDecision.Ignore(IgnoreReason.Pending),
                PurchaseDecision.Ignore(IgnoreReason.AlreadyHandled),
            ),
            kinds,
        )
        decided.forEach { (input, decision) -> assertEquals(decision, PurchaseReconciler.decide(input)) }
    }

    @Test
    fun `Grant only for an unseen PURCHASED token of the ringing, charging session and its next product`() {
        decided.filter { it.second == PurchaseDecision.Grant }.forEach { (input, _) ->
            val session = checkNotNull(input.session)
            assertEquals(PlayPurchaseState.Purchased, input.purchase.purchaseState)
            assertEquals(LedgerStatus.Absent, input.ledger)
            assertEquals(RecordStatus.Absent, input.record)
            assertEquals(session.sessionId, input.purchase.profileId)
            assertEquals(session.expectedNextProductId, input.purchase.productId)
            assertTrue(session.kind.canSnooze)
            assertFalse(session.testMode)
        }
    }

    @Test
    fun `ConsumeOnly only for a token that already granted a snooze, and a relaunch only after ITEM_ALREADY_OWNED for it`() {
        val consumeOnly = decided.mapNotNull { (input, decision) -> (decision as? PurchaseDecision.ConsumeOnly)?.let { input to it } }
        consumeOnly.forEach { (input, decision) ->
            val grantedRecordOnly = input.ledger == LedgerStatus.Absent && input.record == RecordStatus.Granted
            assertEquals(PlayPurchaseState.Purchased, input.purchase.purchaseState)
            assertTrue(input.ledger == LedgerStatus.Granted || grantedRecordOnly)
            assertEquals(input.context == ReconcileContext.AlreadyOwned(input.purchase.productId), decision.retryLaunch)
        }
    }

    @Test
    fun `OfferReuse only for the product being bought by a ringing, charging session, and never for a granted token`() {
        decided.filter { it.second == PurchaseDecision.OfferReuse }.forEach { (input, _) ->
            val session = checkNotNull(input.session)
            val product = input.purchase.productId
            assertTrue(input.context == ReconcileContext.PreLaunch(product) || input.context == ReconcileContext.AlreadyOwned(product))
            assertEquals(LedgerStatus.Absent, input.ledger)
            assertTrue(input.record == RecordStatus.Absent || input.record == RecordStatus.Stranded)
            assertEquals(session.expectedNextProductId, product)
            assertTrue(session.kind.canSnooze)
            assertFalse(session.testMode)
        }
    }

    @Test
    fun `LeaveForAutoRefund only for PURCHASED tokens that never granted anything`() {
        decided.filter { it.second == PurchaseDecision.LeaveForAutoRefund }.forEach { (input, _) ->
            assertEquals(PlayPurchaseState.Purchased, input.purchase.purchaseState)
            assertEquals(LedgerStatus.Absent, input.ledger)
            assertTrue(input.record == RecordStatus.Absent || input.record == RecordStatus.Stranded)
        }
    }

    @Test
    fun `a test session never grants or reuses, and a pending payment is always ignored`() {
        decided.forEach { (input, decision) ->
            if (input.session?.testMode == true) {
                assertTrue(decision != PurchaseDecision.Grant && decision != PurchaseDecision.OfferReuse, "$input")
            }
            if (input.purchase.purchaseState == PlayPurchaseState.Pending) {
                assertEquals(PurchaseDecision.Ignore(IgnoreReason.Pending), decision)
            }
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
    fun `a snapshot never prints its token`() {
        assertFalse("token-1" in snapshot().toString())
        val input = ReconcileInput(snapshot(), active(), LedgerStatus.Absent, RecordStatus.Absent, ReconcileContext.Update)
        assertFalse("token-1" in input.toString())
    }
}
