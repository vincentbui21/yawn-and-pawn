package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.PurchaseFailureKind
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StepResult
import com.yawnandpawn.app.core.session.TWO_STEPS
import com.yawnandpawn.app.core.session.testConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Story 4.11: the Story 4.9 sequences end to end, through the coordinator, the engine's commit, the grant ledger, the
 * purchase records and Play ([CoordinatorWorld]); a crash is a new process over the same stores. Each one counts the
 * grants (snoozes and ledger rows), the consumes and the records.
 */
class PurchaseCoordinatorSequenceTest {
    private val tok = PurchaseToken("tok-1")

    private fun CoordinatorWorld.snoozes(): Int = (engine.state.value as? SessionState.Active)?.session?.snoozesGranted ?: -1

    @Test
    fun `pending, then PURCHASED mid-check gives one grant and drops the check progress, and repeats only consume or ignore`() =
        runTest {
            val w = CoordinatorWorld(this, check = StepResult.ValidNext)
            w.dispatch(SessionEvent.AlarmFired(SESSION, testConfig(checkPlan = TWO_STEPS), beforeFirstUnlock = false))
            w.pay()
            assertTrue(assertIs<SessionState.Ringing>(w.buy(purchase(pending = true))).session.paymentPending)
            w.dispatch(SessionEvent.ImUpTapped)
            val midCheck = assertIs<SessionState.Grace>(w.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)))
            assertEquals(1, midCheck.session.checkRun.step.entry, "the first step is done")

            // The payment clears while the user is on step 2 (AD-2: the snooze wins, the progress is dropped).
            val snoozed = assertIs<SessionState.Snoozed>(w.buy(purchase()))
            assertEquals(1, snoozed.session.snoozesGranted)
            assertEquals(0, snoozed.session.checkRun.step.entry, "the check starts over at the next ring")
            assertTrue(!snoozed.session.paymentPending)

            w.deliver(PurchaseUpdate.Purchases(listOf(purchase())))
            w.play.own(purchase())
            w.recover()
            assertEquals(1, w.snoozes())
            assertEquals(1, w.store.grants.size)
            assertEquals(listOf(tok), w.play.calls, "consumed once; the repeats found it settled")
            assertEquals(
                RecordStatus.Consumed,
                w.world.recordRows
                    .getValue(tok.hash())
                    .status,
            )
        }

    @Test
    fun `pending, then PURCHASED after the session ended is recorded stranded and never consumed`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            w.buy(purchase(pending = true))
            w.dispatch(SessionEvent.ImUpTapped)
            w.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
            assertEquals(SessionState.Idle, w.engine.state.value)

            w.buy(purchase())
            w.recover()
            assertEquals(emptyList(), w.play.calls)
            assertEquals(
                RecordStatus.Stranded,
                w.world.recordRows
                    .getValue(tok.hash())
                    .status,
            )
            assertEquals(emptyMap(), w.store.grants)
            assertEquals(setOf(PRODUCT_1), w.coordinator.strandedProducts.value)
        }

    @Test
    fun `a lost callback is granted when the wake screen opens`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            // Play took the money, but its update never arrived.
            w.play.own(purchase())
            assertIs<SessionState.Ringing>(w.engine.state.value)

            w.coordinator.onWakeScreenResumed()
            w.settle()
            assertEquals(1, w.snoozes())
            assertEquals(listOf(tok), w.play.calls)
            assertEquals(1, w.play.launches.size)
        }

    @Test
    fun `a lost callback found when Pay is tapped again grants, and the Play sheet never opens again`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            w.play.own(purchase())
            // The process dies before any recovery; the restore clears paying, so Pay works again.
            w.newProcess()
            w.engine.restore()
            assertNull(assertIs<SessionState.Ringing>(w.engine.state.value).session.paying)

            w.pay("intent-2")
            assertEquals(1, w.snoozes())
            assertEquals(1, w.play.launches.size, "only the first Pay opened Play")
            assertEquals(listOf(tok), w.play.calls)
        }

    @Test
    fun `the same token across a restart grants once, then consumes, then is ignored`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            w.play.hold = kotlinx.coroutines.CompletableDeferred()
            // Granted; the process dies while Play consumes.
            assertIs<SessionState.Snoozed>(w.buy(purchase()))
            w.play.hold = null
            w.newProcess()
            w.engine.restore()

            w.recover()
            assertEquals(1, w.snoozes())
            assertEquals(
                RecordStatus.Consumed,
                w.world.recordRows
                    .getValue(tok.hash())
                    .status,
            )
            assertNotNull(
                w.store.grants
                    .getValue(tok)
                    .settledAt,
            )
            w.deliver(PurchaseUpdate.Purchases(listOf(purchase())))
            assertEquals(1, w.snoozes())
            assertEquals(1, w.store.grants.size)
            assertEquals(2, w.play.calls.size, "the consume the kill cut short, then the replay")
        }

    @Test
    fun `a crash between the grant commit and the consume is consumed on the next start`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            w.play.own(purchase())
            // The grant commits, then the process dies before its Consume effect runs.
            w.engine.dispatch(SessionEvent.PurchaseGranted(PRODUCT_1, tok, com.yawnandpawn.app.core.session.PurchaseVerdict.Grant, "GPA.1"))
            w.newProcess()
            assertEquals(emptyList(), w.play.calls)

            w.engine.restore()
            w.recover()
            assertEquals(listOf(tok), w.play.calls)
            assertEquals(
                RecordStatus.Consumed,
                w.world.recordRows
                    .getValue(tok.hash())
                    .status,
            )
            assertEquals(1, assertIs<SessionState.Snoozed>(w.engine.state.value).session.snoozesGranted)
        }

    /**
     * PRD UJ4: a payment error, the session completes, the token becomes PURCHASED later, recovery records it stranded,
     * next morning's Pay offers it, the user accepts: Snoozed with one record (reused) and one consume.
     */
    @Test
    fun `PRD UJ4 end to end - stranded, offered next morning, reused with one record and one consume`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            w.deliver(PurchaseUpdate.Failed(PurchaseFailureKind.Error))
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Failed)), w.outcomes)
            w.dispatch(SessionEvent.ImUpTapped)
            w.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
            assertEquals(SessionState.Idle, w.engine.state.value)

            // Later the bank approves it after all.
            w.play.own(purchase())
            w.recover()
            assertEquals(
                RecordStatus.Stranded,
                w.world.recordRows
                    .getValue(tok.hash())
                    .status,
            )

            // Next morning.
            w.time.advanceBy(23.hours)
            w.newProcess()
            w.ring("session-2")
            w.ran.clear()
            w.pay("intent-2")
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowReuseSheet(PRODUCT_1)), w.outcomes)
            assertEquals(1, w.play.launches.size, "Pay never opened Play again")
            assertEquals(
                PRODUCT_1,
                w.coordinator.reuseOffer.value
                    ?.productId,
            )

            val accepted = assertIs<Outcome.Success<SessionState>>(w.coordinator.acceptReuse())
            w.settle()
            val snoozed = assertIs<SessionState.Snoozed>(accepted.value)
            assertEquals(1, snoozed.session.snoozesGranted)
            assertEquals(1, w.world.recordRows.size, "exactly one record")
            val record = w.world.recordRows.getValue(tok.hash())
            assertEquals(RecordStatus.Reused, record.status)
            assertEquals("session-2", record.sessionId)
            assertNotNull(record.consumedAt)
            assertEquals(listOf(tok), w.play.calls, "exactly one consume")
            assertNull(w.coordinator.reuseOffer.value)
        }

    @Test
    fun `UJ4 with no recovery before the next Pay records the token stranded first, so the reuse ends reused`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring("session-0")
            w.dispatch(SessionEvent.ImUpTapped)
            w.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
            w.play.own(purchase(profileId = "session-0"))

            w.ring()
            w.pay()
            assertEquals(
                RecordStatus.Stranded,
                w.world.recordRows
                    .getValue(tok.hash())
                    .status,
                "recorded before the offer",
            )
            assertEquals(emptyList(), w.play.launches)
            assertIs<SessionState.Snoozed>(assertIs<Outcome.Success<SessionState>>(w.coordinator.acceptReuse()).value)
            w.settle()
            assertEquals(
                RecordStatus.Reused,
                w.world.recordRows
                    .getValue(tok.hash())
                    .status,
            )
            assertEquals(listOf(tok), w.play.calls)
        }

    @Test
    fun `declining the reuse keeps the record stranded and the token unconsumed, and the offer is gone`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.play.own(purchase(profileId = "yesterday"))
            w.ring()
            w.pay()
            val declined = assertIs<Outcome.Success<SessionState>>(w.coordinator.declineReuse())
            assertEquals(PRODUCT_1, assertIs<SessionState.Ringing>(declined.value).session.declinedReuseProduct)
            w.settle()
            assertNull(w.coordinator.acceptReuse(), "nothing is offered any more")
            assertEquals(
                RecordStatus.Stranded,
                w.world.recordRows
                    .getValue(tok.hash())
                    .status,
            )
            assertEquals(emptyList(), w.play.calls)
            assertEquals(setOf(PRODUCT_1), w.coordinator.strandedProducts.value)
        }

    @Test
    fun `an offer made to an earlier session is never accepted for a later one`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.play.own(purchase(profileId = "yesterday"))
            w.ring()
            w.pay()
            assertNotNull(w.coordinator.reuseOffer.value)
            w.dispatch(SessionEvent.ImUpTapped)
            w.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
            w.ring("session-2")
            assertNull(w.coordinator.acceptReuse())
            assertEquals(emptyList(), w.play.calls)
        }

    @Test
    fun `ITEM_ALREADY_OWNED for an unconsumed grant consumes it, retries the launch, then the new payment grants`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            // Snooze 1 was paid and granted, but its consume failed; its ledger row is gone (a restored app.db).
            val old = PurchaseToken("tok-old")
            w.world.recordRows[old.hash()] = record(RecordStatus.Granted, token = old, sessionId = "yesterday")
            w.play.own(purchase(token = "tok-old", profileId = "yesterday"))
            w.play.queryFailure = DomainError.BillingUnavailable("timeout")
            w.play.launchResults += Outcome.Success(LaunchResult.ItemAlreadyOwned)
            w.play.onLaunch = { w.play.queryFailure = null }
            w.pay()

            assertEquals(2, w.play.launches.size)
            assertIs<SessionState.Snoozed>(w.buy(purchase()))
            assertEquals(listOf(old, tok), w.play.calls)
            w.play.own(purchase(token = "tok-old", profileId = "yesterday"))
            w.recover()
            assertEquals(listOf(old, tok), w.play.calls, "a stale listing of the consumed token is ignored")
        }

    @Test
    fun `another install's payment is never touched in any context`() =
        runTest {
            val w = CoordinatorWorld(this)
            val foreign = purchase(accountId = "other-phone")
            w.play.own(foreign)
            w.ring()
            w.deliver(PurchaseUpdate.Purchases(listOf(foreign)))
            w.recover()
            w.pay()
            assertEquals(1, w.play.launches.size, "launched as if it were not there")
            w.play.launchResults += Outcome.Success(LaunchResult.ItemAlreadyOwned)
            w.deliver(PurchaseUpdate.Failed(PurchaseFailureKind.Error))
            w.pay("intent-2")
            assertEquals(emptyMap(), w.world.recordRows)
            assertEquals(emptyList(), w.play.calls)
            assertEquals(emptyMap(), w.store.grants)
            assertEquals(0, w.snoozes())
        }

    @Test
    fun `a restore never launches billing, and a result for the old launch after it is ignored`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.engine.dispatch(SessionEvent.PayConfirmed(PurchaseIntentId("intent-1"), QUOTE_1))
            // The process dies before the launch ran.
            w.newProcess()
            w.engine.restore()
            w.time.advanceBy(1.minutes)
            w.settle()
            assertEquals(emptyList(), w.play.launches)
            w.deliver(PurchaseUpdate.Cancelled)
            assertEquals(emptyList(), w.outcomes, "nothing is in flight in this process")
        }
}
