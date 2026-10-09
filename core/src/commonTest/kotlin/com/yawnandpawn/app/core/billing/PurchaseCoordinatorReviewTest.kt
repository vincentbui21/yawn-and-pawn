package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.UnlockResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Story 4.11 review fixes (2 reviewers, fast mode): the update collector, interleavings under the coordinator's Mutex, no
 * "No charge." after a payment, one launch per intent, updates before the restore, a result Play gives twice, a stale
 * sheet, and the unlock rules the coordinator owns for Story 4.13.
 */
class PurchaseCoordinatorReviewTest {
    private val tok = PurchaseToken("tok-1")
    private val old = PurchaseToken("tok-old")

    private fun CoordinatorWorld.paying(): PurchaseIntentId? = (engine.state.value as? SessionState.Ring)?.session?.paying

    /** Yesterday's snooze was granted with the same product and is not consumed; its ledger row is gone (row B). */
    private fun CoordinatorWorld.ownGrantedFromYesterday() {
        world.recordRows[old.hash()] = record(RecordStatus.Granted, token = old, sessionId = "yesterday")
        play.own(purchase(token = "tok-old", profileId = "yesterday"))
    }

    /** The pre-launch query fails, so the owned token comes back as `ITEM_ALREADY_OWNED` from the launch. */
    private fun CoordinatorWorld.preLaunchQueryFails(onLaunch: () -> Unit = {}) {
        play.queryFailure = DomainError.BillingUnavailable("timeout")
        play.onLaunch = {
            play.queryFailure = null
            onLaunch()
        }
        play.launchResults += Outcome.Success(LaunchResult.ItemAlreadyOwned)
    }

    @Test
    fun `review 1 - the collector grants what Play delivers and survives a failing stream and a failing update`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            w.play.failNextCollects = 1
            w.coordinator.start()
            w.settle()
            assertTrue(w.logger.events.contains(LogEvent.OperationFailed(PurchaseCoordinator.UPDATES, "IllegalStateException")))

            w.advance(PurchaseCoordinator.COLLECT_RETRY_FIRST)
            w.installThrows = IllegalStateException("datastore")
            assertTrue(w.play.updates.tryEmit(PurchaseUpdate.Purchases(listOf(purchase(token = "x", profileId = "yesterday")))))
            w.settle()
            w.installThrows = null

            w.play.own(purchase())
            assertTrue(w.play.updates.tryEmit(PurchaseUpdate.Purchases(listOf(purchase()))))
            w.settle()
            assertEquals(1, assertIs<SessionState.Snoozed>(w.engine.state.value).session.snoozesGranted)
            assertEquals(listOf(tok), w.play.calls)
        }

    @Test
    fun `review 3 - an update for the token a held recovery is deciding grants once`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            w.play.own(purchase())
            val hold = CompletableDeferred<Unit>()
            w.play.queryHold = hold
            launch { w.coordinator.recover() }
            w.settle()
            launch { w.coordinator.onUpdate(PurchaseUpdate.Purchases(listOf(purchase()))) }
            w.settle()
            assertIs<SessionState.Ringing>(w.engine.state.value, "both wait: the recovery in its query, the update on the Mutex")

            w.play.queryHold = null
            hold.complete(Unit)
            w.settle()
            assertEquals(1, assertIs<SessionState.Snoozed>(w.engine.state.value).session.snoozesGranted)
            assertEquals(1, w.store.grants.size)
            assertEquals(listOf(tok), w.play.calls)
        }

    @Test
    fun `review 3 - a payment found by a held pre-launch query while its update arrives never opens Play`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase())
            val hold = CompletableDeferred<Unit>()
            w.play.queryHold = hold
            w.engine.dispatch(SessionEvent.PayConfirmed(PurchaseIntentId("intent-1"), QUOTE_1))
            w.settle()
            launch { w.coordinator.onUpdate(PurchaseUpdate.Purchases(listOf(purchase()))) }
            w.settle()

            w.play.queryHold = null
            hold.complete(Unit)
            w.settle()
            assertEquals(1, assertIs<SessionState.Snoozed>(w.engine.state.value).session.snoozesGranted)
            assertEquals(emptyList(), w.play.launches)
            assertEquals(listOf(tok), w.play.calls)
        }

    @Test
    fun `review 4 - a PURCHASED answer that grants nothing now never says No charge, and recovery grants it later`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            w.world.ledgerFailure = DomainError.StorageFailure("runtime.db")
            w.buy(purchase())
            assertEquals(emptyList(), w.outcomes)
            assertEquals(PurchaseIntentId("intent-1"), w.paying(), "the payment stays in flight")

            w.world.ledgerFailure = null
            w.recover()
            assertEquals(1, assertIs<SessionState.Snoozed>(w.engine.state.value).session.snoozesGranted)
        }

    @Test
    fun `review 5 - ITEM_ALREADY_OWNED from the launch for an owned PURCHASED P grants it`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.preLaunchQueryFails { w.play.own(purchase()) }
            assertEquals(1, assertIs<SessionState.Snoozed>(w.pay()).session.snoozesGranted)
            assertEquals(1, w.play.launches.size)
        }

    @Test
    fun `review 6 - ITEM_ALREADY_OWNED for a stranded token offers it`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.preLaunchQueryFails { w.play.own(purchase(profileId = "yesterday")) }
            w.pay()
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowReuseSheet(PRODUCT_1)), w.outcomes)
            assertEquals(1, w.play.launches.size)
            assertEquals(
                RecordStatus.Stranded,
                w.world.recordRows
                    .getValue(tok.hash())
                    .status,
            )
        }

    @Test
    fun `review 6 - ITEM_ALREADY_OWNED for a pending P says payment pending`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.preLaunchQueryFails { w.play.own(purchase(pending = true)) }
            assertTrue(assertIs<SessionState.Ringing>(w.pay()).session.paymentPending)
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPaymentPending), w.outcomes)
            assertEquals(1, w.play.launches.size)
        }

    @Test
    fun `review 6 - ITEM_ALREADY_OWNED whose consume does not settle fails the payment without a retry`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.ownGrantedFromYesterday()
            w.preLaunchQueryFails()
            w.play.results += ConsumeResult.Failed("SERVICE_UNAVAILABLE")
            assertNull(assertIs<SessionState.Ringing>(w.pay()).session.paying)
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Failed)), w.outcomes)
            assertEquals(1, w.play.launches.size)
        }

    @Test
    fun `review 6 - an ITEM_ALREADY_OWNED update for the retry's sheet fails it without another query`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.ownGrantedFromYesterday()
            w.preLaunchQueryFails()
            w.pay()
            assertEquals(2, w.play.launches.size)
            assertEquals(PurchaseIntentId("intent-1"), w.paying(), "the retry's sheet is open")
            w.deliver(PurchaseUpdate.ItemAlreadyOwned)
            assertNull(w.paying())
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Failed)), w.outcomes)
            assertEquals(2, w.play.queries)
        }

    @Test
    fun `review 7 - the same intent launched twice opens Play once`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            w.coordinator.onLaunchBilling(SessionEffect.LaunchBilling(PurchaseIntentId("intent-1"), SESSION))
            w.settle()
            assertEquals(1, w.play.launches.size)
        }

    @Test
    fun `review 7 - a launch held before its sheet never opens after a newer Pay, which opens alone`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            val hang = CompletableDeferred<Unit>()
            w.play.hold = hang
            w.store.grants[old] = grant(token = old)
            w.pay()
            w.dispatch(SessionEvent.PurchaseCancelled)
            w.pay("intent-2")
            hang.complete(Unit)
            w.settle()
            assertEquals(listOf(PurchaseIntentId("intent-2")), w.play.launches.map { it.first.intentId })
        }

    @Test
    fun `review 8 - Pay while a pending payment is shown, then a pending answer to the sheet says pending again`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(token = "p3", productId = PRODUCT_3, pending = true))
            w.recover()
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPaymentPending), w.outcomes)
            w.play.listed.clear()
            w.pay()
            assertEquals(1, w.play.launches.size)

            w.deliver(PurchaseUpdate.Purchases(listOf(purchase(pending = true))))
            assertEquals(List(2) { SessionEffect.ShowPaymentPending }, w.outcomes)
            assertNull(w.paying())
        }

    @Test
    fun `review 11 - a PURCHASED update before the restore waits for it, then grants instead of stranding`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            w.newProcess()
            w.play.own(purchase())
            launch { w.coordinator.onUpdate(PurchaseUpdate.Purchases(listOf(purchase()))) }
            w.settle()
            assertEquals(emptyMap(), w.world.recordRows, "nothing decided before the restore")

            w.engine.restore()
            w.settle()
            assertEquals(1, assertIs<SessionState.Snoozed>(w.engine.state.value).session.snoozesGranted)
            assertEquals(
                RecordStatus.Consumed,
                w.world.recordRows
                    .getValue(tok.hash())
                    .status,
            )
        }

    @Test
    fun `review 12 - ITEM_ALREADY_OWNED returned by the launch and also delivered as an update is handled once`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.coordinator.start()
            w.ring()
            w.ownGrantedFromYesterday()
            w.preLaunchQueryFails {
                if (w.play.launches.size == 1) w.play.updates.tryEmit(PurchaseUpdate.ItemAlreadyOwned)
            }
            w.pay()
            assertEquals(2, w.play.launches.size, "one retry")
            assertEquals(PurchaseIntentId("intent-1"), w.paying(), "the retry's sheet is open")
            assertEquals(emptyList(), w.outcomes, "no PurchaseFailed from the echo")
        }

    @Test
    fun `review 15 - a sheet whose result never came is cancelled on resume after 2 minutes, and a late payment still grants`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            w.time.advanceBy(1.minutes)
            w.coordinator.onWakeScreenResumed()
            w.settle()
            assertEquals(PurchaseIntentId("intent-1"), w.paying(), "too early to give up on it")

            w.time.advanceBy(PurchaseCoordinator.STALE_SHEET)
            w.coordinator.onWakeScreenResumed()
            w.settle()
            assertNull(w.paying())
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Cancelled)), w.outcomes)

            assertEquals(1, assertIs<SessionState.Snoozed>(w.buy(purchase())).session.snoozesGranted)
        }

    @Test
    fun `review 16 - a late unlock result for an older Pay never applies to the newer one`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.unlock.locked = true
            w.unlock.hold = true
            w.ring()
            w.pay()
            w.unlock.hold = false
            w.pay("intent-2")
            assertEquals(PurchaseIntentId("intent-2"), w.paying())
            assertEquals(listOf(PurchaseIntentId("intent-2")), w.play.launches.map { it.first.intentId })

            w.unlock.release(UnlockResult.Failed)
            w.settle()
            assertEquals(PurchaseIntentId("intent-2"), w.paying(), "intent-1's late failure is dropped")
            assertEquals(emptyList(), w.outcomes)
        }

    @Test
    fun `review 16 - an older Pay's lost request never blocks the newer one's resume rule`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.unlock.locked = true
            w.unlock.hold = true
            w.ring()
            w.pay()
            w.forwardUnlock = false
            w.pay("intent-2")
            w.coordinator.onWakeScreenResumed()
            w.advance(PurchaseCoordinator.UNLOCK_SETTLE)
            assertNull(w.paying())
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.UnlockFailed)), w.outcomes)
        }

    @Test
    fun `review 16 - a request waiting past the grace is taken as lost on resume`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.unlock.locked = true
            w.unlock.hold = true
            w.ring()
            w.pay()
            w.time.advanceBy(PurchaseCoordinator.UNLOCK_GRACE + 1.seconds)
            w.coordinator.onWakeScreenResumed()
            w.advance(PurchaseCoordinator.UNLOCK_SETTLE)
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.UnlockFailed)), w.outcomes)
        }

    @Test
    fun `review 16 - a keyguard that reads locked just after the resume and unlocked within the settle opens Play`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.unlock.locked = true
            w.forwardUnlock = false
            w.ring()
            w.pay()
            w.coordinator.onWakeScreenResumed()
            w.settle()
            w.unlock.locked = false
            w.advance(PurchaseCoordinator.UNLOCK_SETTLE)
            assertEquals(1, w.play.launches.size)
            assertEquals(emptyList(), w.outcomes)
        }
}
