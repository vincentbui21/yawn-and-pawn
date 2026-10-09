package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.PurchaseFailureKind
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.UnlockResult
import com.yawnandpawn.app.core.session.testConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Story 4.11: what `PurchaseCoordinator` does with each reconciler decision, in each way a purchase reaches it (an update,
 * a recovery query, a launch), and how a launch ends. The engine, the grant ledger, the records and Play are the real
 * core classes over in-memory stores ([CoordinatorWorld]).
 */
class PurchaseCoordinatorTest {
    private val tok = PurchaseToken("tok-1")

    /** How a purchase reaches the coordinator outside a launch. */
    private enum class Via { Update, Recovery }

    /** One row of the decision → action table: the world before, the purchase, what must hold after. */
    private class Row(
        val name: String,
        val purchase: PurchaseSnapshot,
        val setUp: suspend CoordinatorWorld.() -> Unit = { ring() },
        val expect: CoordinatorWorld.(SessionState) -> Unit,
    )

    private fun CoordinatorWorld.recordStatus(): RecordStatus? = world.recordRows[tok.hash()]?.status

    private val rows =
        listOf(
            Row("PURCHASED for the ringing session's next product grants once and consumes once", purchase()) { state ->
                assertEquals(1, assertIs<SessionState.Snoozed>(state).session.snoozesGranted)
                assertEquals(listOf(tok), play.calls)
                assertEquals(RecordStatus.Consumed, recordStatus())
                assertEquals("GPA.1", world.recordRows.getValue(tok.hash()).orderId, "the order id reaches the record")
            },
            Row("PURCHASED for an ended session is recorded stranded, never consumed", purchase(profileId = "yesterday")) { state ->
                assertIs<SessionState.Ringing>(state)
                assertEquals(emptyList(), play.calls)
                assertEquals(RecordStatus.Stranded, recordStatus())
                assertNull(world.recordRows.getValue(tok.hash()).alarmId, "another session's alarm is unknown")
            },
            Row("PURCHASED for another product of this session is stranded with this alarm", purchase(productId = PRODUCT_3)) { state ->
                assertIs<SessionState.Ringing>(state)
                assertEquals(emptyList(), play.calls)
                assertEquals(RecordStatus.Stranded, recordStatus())
                assertEquals(ALARM, world.recordRows.getValue(tok.hash()).alarmId)
            },
            Row("PURCHASED with no session at all is stranded", purchase(), setUp = { engine.restore() }) { state ->
                assertEquals(SessionState.Idle, state)
                assertEquals(RecordStatus.Stranded, recordStatus())
                assertEquals(emptyList(), play.calls)
            },
            Row("PURCHASED while snoozed is stranded", purchase(token = "tok-2"), setUp = {
                ring()
                buy(purchase(token = "tok-0"))
            }) { state ->
                assertEquals(1, assertIs<SessionState.Snoozed>(state).session.snoozesGranted)
                assertEquals(RecordStatus.Stranded, world.recordRows[PurchaseToken("tok-2").hash()]?.status)
                assertEquals(listOf(PurchaseToken("tok-0")), play.calls)
            },
            Row("PURCHASED for a test session is stranded, never granted", purchase(), setUp = {
                dispatch(SessionEvent.TestAlarmFired(SESSION, testConfig(testMode = true), beforeFirstUnlock = false))
            }) { state ->
                assertIs<SessionState.Ringing>(state)
                assertEquals(RecordStatus.Stranded, recordStatus())
            },
            Row("PENDING for the ringing session says payment pending and writes nothing", purchase(pending = true)) { state ->
                assertTrue(assertIs<SessionState.Ringing>(state).session.paymentPending)
                assertEquals(listOf<SessionEffect>(SessionEffect.ShowPaymentPending), outcomes)
                assertNull(recordStatus())
                assertEquals(emptyList(), play.calls)
            },
            Row("PENDING for another session changes nothing", purchase(pending = true, profileId = "yesterday")) { state ->
                assertFalse(assertIs<SessionState.Ringing>(state).session.paymentPending)
                assertEquals(emptyList(), outcomes)
                assertNull(recordStatus())
            },
            Row("another install's payment is left entirely to that install", purchase(accountId = "other-phone")) { state ->
                assertIs<SessionState.Ringing>(state)
                assertNull(recordStatus())
                assertEquals(emptyList(), play.calls)
            },
            Row("a granted but unconsumed token (crash before the consume) is consumed, never granted again", purchase(), setUp = {
                ring()
                store.grants[tok] = grant(token = tok)
            }) { state ->
                assertIs<SessionState.Ringing>(state)
                assertEquals(listOf(tok), play.calls)
                assertEquals(RecordStatus.Consumed, recordStatus())
            },
            Row("an already consumed token is ignored", purchase(), setUp = {
                ring()
                world.recordRows[tok.hash()] = record(RecordStatus.Consumed, token = tok)
            }) { state ->
                assertIs<SessionState.Ringing>(state)
                assertEquals(emptyList(), play.calls)
                assertEquals(RecordStatus.Consumed, recordStatus())
            },
            Row("a stranded token is never granted outside a reuse (row C)", purchase(), setUp = {
                ring()
                world.recordRows[tok.hash()] = record(RecordStatus.Stranded, token = tok)
            }) { state ->
                assertIs<SessionState.Ringing>(state)
                assertEquals(emptyList(), play.calls)
                assertEquals(RecordStatus.Stranded, recordStatus())
            },
        )

    private suspend fun TestScope.check(
        row: Row,
        via: Via,
    ) {
        val world = CoordinatorWorld(this)
        row.setUp(world)
        world.ran.clear()
        val state =
            when (via) {
                Via.Update -> {
                    world.buy(row.purchase)
                }

                Via.Recovery -> {
                    world.play.own(row.purchase)
                    assertTrue(world.recover(), "${row.name}: recovered")
                    world.engine.state.value
                }
            }
        try {
            row.expect(world, state)
        } catch (e: AssertionError) {
            throw AssertionError("${row.name} ($via): ${e.message}", e)
        }
    }

    @Test
    fun `every decision is carried out the same way from an update and from a recovery query`() =
        runTest {
            Via.entries.forEach { via -> rows.forEach { row -> check(row, via) } }
        }

    @Test
    fun `a launch reads the committed intent and opens Play with the install id and the session`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            val paying = assertIs<SessionState.Ringing>(w.pay())

            assertEquals(PurchaseIntentId("intent-1"), paying.session.paying)
            val (intent, installId) = w.play.launches.single()
            assertEquals(INSTALL, installId)
            assertEquals(SESSION, intent.sessionId)
            assertEquals(PRODUCT_1, intent.productId)
            assertEquals(1, w.play.queries, "the owned purchases are reconciled before Play opens")

            val snoozed = assertIs<SessionState.Snoozed>(w.buy(purchase()))
            assertEquals(1, snoozed.session.snoozesGranted)
            assertEquals(listOf(tok), w.play.calls)
        }

    @Test
    fun `the launch results end the payment with their own message and keep the alarm ringing`() =
        runTest {
            val cases =
                listOf<Pair<Outcome<LaunchResult, DomainError>, PurchaseOutcome>>(
                    Outcome.Success(LaunchResult.Cancelled) to PurchaseOutcome.Cancelled,
                    Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.Offline)) to PurchaseOutcome.Offline,
                    Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.Error)) to PurchaseOutcome.Failed,
                    Outcome.Failure(DomainError.BillingUnavailable("not connected")) to PurchaseOutcome.Failed,
                )
            cases.forEach { (result, outcome) ->
                val w = CoordinatorWorld(this)
                w.ring()
                w.play.launchResults += result
                val state = assertIs<SessionState.Ringing>(w.pay())
                assertNull(state.session.paying, "$result clears paying")
                assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(outcome)), w.outcomes, "$result")
            }
        }

    @Test
    fun `cancel and failure updates answer only the open sheet`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.deliver(PurchaseUpdate.Cancelled)
            w.deliver(PurchaseUpdate.Failed(PurchaseFailureKind.Error))
            assertEquals(emptyList(), w.outcomes, "nothing is open: no message")

            w.pay()
            val state = assertIs<SessionState.Ringing>(w.deliver(PurchaseUpdate.Failed(PurchaseFailureKind.Offline)))
            assertNull(state.session.paying)
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Offline)), w.outcomes)

            w.pay("intent-2")
            w.deliver(PurchaseUpdate.Cancelled)
            assertEquals(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Cancelled), w.outcomes.last())
            w.deliver(PurchaseUpdate.Cancelled)
            assertEquals(2, w.outcomes.size, "a repeated cancel shows nothing more")
        }

    @Test
    fun `a missing intent or install id fails the payment without opening Play`() =
        runTest {
            val noIntent = CoordinatorWorld(this)
            noIntent.ring()
            noIntent.intents.failure = DomainError.StorageFailure("runtime.db")
            assertNull(assertIs<SessionState.Ringing>(noIntent.pay()).session.paying)
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Failed)), noIntent.outcomes)
            assertEquals(emptyList(), noIntent.play.launches)

            val noInstall = CoordinatorWorld(this)
            noInstall.ring()
            noInstall.installFailure = DomainError.StorageFailure("datastore")
            assertNull(assertIs<SessionState.Ringing>(noInstall.pay()).session.paying)
            assertEquals(emptyList(), noInstall.play.launches)
        }

    @Test
    fun `a launch for a session that no longer pays its intent never opens Play`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.engine.dispatch(SessionEvent.PayConfirmed(PurchaseIntentId("intent-1"), QUOTE_1))
            // The payment ended (any result) before the launch got to run.
            w.engine.dispatch(SessionEvent.PurchaseCancelled)
            w.settle()
            assertEquals(emptyList(), w.play.launches)
            assertEquals(0, w.play.queries)
        }

    @Test
    fun `a failed pre-launch query still launches, and ITEM_ALREADY_OWNED then reconciles`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.queryFailure = DomainError.BillingUnavailable("timeout")
            w.pay()
            assertEquals(1, w.play.launches.size)
            assertTrue(w.logger.events.any { it == LogEvent.OperationFailed(PurchaseCoordinator.QUERY, "billing unavailable: timeout") })
        }

    @Test
    fun `ITEM_ALREADY_OWNED for a granted token consumes it and retries the launch once`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            // Granted yesterday with the same product, its ledger row lost (row B): only the record says granted.
            val old = PurchaseToken("tok-old")
            w.world.recordRows[old.hash()] = record(RecordStatus.Granted, token = old, sessionId = "yesterday")
            w.play.own(purchase(token = "tok-old", profileId = "yesterday"))
            w.play.queryFailure = DomainError.BillingUnavailable("timeout")
            w.play.launchResults += Outcome.Success(LaunchResult.ItemAlreadyOwned)
            w.play.onLaunch = { w.play.queryFailure = null }

            val state = assertIs<SessionState.Ringing>(w.pay())

            assertEquals(listOf(old), w.play.calls, "consumed once")
            assertEquals(
                RecordStatus.Consumed,
                w.world.recordRows
                    .getValue(old.hash())
                    .status,
            )
            assertEquals(2, w.play.launches.size, "launched again after the consume")
            assertEquals(PurchaseIntentId("intent-1"), state.session.paying, "the retry's sheet is open")
            assertEquals(emptyList(), w.outcomes)
        }

    @Test
    fun `a second ITEM_ALREADY_OWNED, or one with nothing to consume, fails the payment with no charge`() =
        runTest {
            val twice = CoordinatorWorld(this)
            twice.ring()
            val old = PurchaseToken("tok-old")
            twice.world.recordRows[old.hash()] = record(RecordStatus.Granted, token = old, sessionId = "yesterday")
            twice.play.own(purchase(token = "tok-old", profileId = "yesterday"))
            twice.play.queryFailure = DomainError.BillingUnavailable("timeout")
            twice.play.onLaunch = { twice.play.queryFailure = null }
            repeat(2) { twice.play.launchResults += Outcome.Success(LaunchResult.ItemAlreadyOwned) }
            assertNull(assertIs<SessionState.Ringing>(twice.pay()).session.paying)
            assertEquals(2, twice.play.launches.size)
            assertEquals(2, twice.play.queries, "the pre-launch query and the first ITEM_ALREADY_OWNED's; the second asks nothing")
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Failed)), twice.outcomes)

            val nothing = CoordinatorWorld(this)
            nothing.ring()
            nothing.play.launchResults += Outcome.Success(LaunchResult.ItemAlreadyOwned)
            assertNull(assertIs<SessionState.Ringing>(nothing.pay()).session.paying)
            assertEquals(1, nothing.play.launches.size)
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Failed)), nothing.outcomes)
        }

    @Test
    fun `ITEM_ALREADY_OWNED as an update is handled like the launch result`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            // The purchase went through but its update was lost; Play answers the open sheet with ITEM_ALREADY_OWNED.
            w.play.own(purchase())
            val state = assertIs<SessionState.Snoozed>(w.deliver(PurchaseUpdate.ItemAlreadyOwned))
            assertEquals(1, state.session.snoozesGranted)
            assertEquals(1, w.play.launches.size, "never launched again")
        }

    @Test
    fun `a pending payment of the product being launched stops the launch`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(pending = true))
            val state = assertIs<SessionState.Ringing>(w.pay())
            assertTrue(state.session.paymentPending)
            assertNull(state.session.paying)
            assertEquals(emptyList(), w.play.launches)
        }

    @Test
    fun `a pending payment shows its message once per session, unless it answers the open sheet`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(pending = true))
            w.recover()
            w.recover()
            w.deliver(PurchaseUpdate.Purchases(listOf(purchase(pending = true))))
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPaymentPending), w.outcomes)
        }

    @Test
    fun `a locked Pay asks for the unlock first, then launches, and a cancelled unlock says phone still locked`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.unlock.locked = true
            w.ring()
            w.pay()
            assertEquals(1, w.unlock.requests)
            assertEquals(1, w.play.launches.size, "Play opens after the unlock succeeded")

            val cancelled = CoordinatorWorld(this)
            cancelled.unlock.locked = true
            cancelled.unlock.result = UnlockResult.Failed
            cancelled.ring()
            val state = assertIs<SessionState.Ringing>(cancelled.pay())
            assertNull(state.session.paying)
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.UnlockFailed)), cancelled.outcomes)
            assertEquals(emptyList(), cancelled.play.launches)

            val throwing = CoordinatorWorld(this)
            throwing.unlock.locked = true
            throwing.unlock.throwing = IllegalStateException("no activity")
            throwing.ring()
            assertNull(assertIs<SessionState.Ringing>(throwing.pay()).session.paying, "a throwing unlock counts as failed")
        }

    @Test
    fun `an unlock the wake screen finds done on resume opens Play once, even if its callback comes later`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.unlock.locked = true
            w.unlock.hold = true
            w.ring()
            w.pay()
            w.coordinator.onWakeScreenResumed()
            w.advance(2.seconds)
            assertTrue(assertIs<SessionState.Ringing>(w.engine.state.value).session.unlocking, "locked while a request waits: nothing")

            // A fingerprint unlock: the keyguard is gone, the callback has not come.
            w.unlock.locked = false
            w.coordinator.onWakeScreenResumed()
            w.settle()
            assertEquals(1, w.play.launches.size, "unlocked: Play opens")
            w.unlock.release(UnlockResult.Succeeded)
            w.settle()
            assertEquals(1, w.play.launches.size, "the late callback is ignored")
        }

    @Test
    fun `a lost unlock request with the phone still locked ends the payment on resume`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.unlock.locked = true
            w.forwardUnlock = false
            w.ring()
            w.pay()
            assertTrue(assertIs<SessionState.Ringing>(w.engine.state.value).session.unlocking)

            w.coordinator.onWakeScreenResumed()
            w.settle()
            assertTrue(assertIs<SessionState.Ringing>(w.engine.state.value).session.unlocking, "the keyguard may lag the resume")
            w.advance(PurchaseCoordinator.UNLOCK_SETTLE)
            val state = assertIs<SessionState.Ringing>(w.engine.state.value)
            assertNull(state.session.paying)
            assertEquals(listOf<SessionEffect>(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.UnlockFailed)), w.outcomes)
            assertEquals(emptyList(), w.play.launches)
        }

    @Test
    fun `recovery never runs before the first unlock and waits for the session restore`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.userLock.unlocked = false
            assertFalse(w.coordinator.recover())
            assertEquals(0, w.play.queries)

            w.userLock.unlocked = true
            w.coordinator.onAppResumed()
            w.settle()
            assertEquals(0, w.play.queries, "the stored session is not restored yet")
            w.engine.restore()
            w.settle()
            assertEquals(1, w.play.queries)
        }

    @Test
    fun `the stranded set lists this install's unspent payments from the latest full query`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.play.own(purchase(token = "a", productId = PRODUCT_3, profileId = "yesterday"))
            w.play.own(purchase(token = "b", accountId = "other-phone"))
            w.play.own(purchase(token = "c", pending = true, profileId = "yesterday"))
            w.recover()
            assertEquals(setOf(PRODUCT_3), w.coordinator.strandedProducts.value)

            w.play.listed.clear()
            w.recover()
            assertEquals(emptySet(), w.coordinator.strandedProducts.value, "refunded: no longer listed")
        }

    @Test
    fun `a duplicate delivery grants one snooze, also when both arrive at once`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.pay()
            w.play.own(purchase())
            val update = PurchaseUpdate.Purchases(listOf(purchase()))
            launch { w.coordinator.onUpdate(update) }
            launch { w.coordinator.onUpdate(update) }
            launch { w.coordinator.recover() }
            w.settle()
            w.deliver(update)
            assertEquals(1, assertIs<SessionState.Snoozed>(w.engine.state.value).session.snoozesGranted)
            assertEquals(1, w.store.grants.size)
            assertEquals(listOf(tok), w.play.calls)
        }

    @Test
    fun `nothing in billing ever holds up I'm up, a check or the timeout`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            val hang = CompletableDeferred<Unit>()
            w.play.hold = hang
            // A granted token Play still owns: the launch first settles the ledger, and that consume hangs.
            w.store.grants[PurchaseToken("tok-old")] = grant(token = PurchaseToken("tok-old"))
            w.pay()
            assertIs<SessionState.Grace>(w.dispatch(SessionEvent.ImUpTapped))
            w.dispatch(SessionEvent.CheckAnswerSubmitted(com.yawnandpawn.app.core.checks.CheckAnswer.Placeholder))
            assertEquals(SessionState.Idle, w.engine.state.value, "the check ended the session while billing hung")
            hang.complete(Unit)
            w.settle()
            assertEquals(emptyList(), w.play.launches, "an ended session never opens Play")
        }

    @Test
    fun `tokens never reach the log or the offer's text`() =
        runTest {
            val w = CoordinatorWorld(this)
            w.ring()
            w.world.recordRows[tok.hash()] = record(RecordStatus.Stranded, token = tok)
            w.play.own(purchase(profileId = "yesterday"))
            w.pay()
            val offer = w.coordinator.reuseOffer.value
            assertEquals(PRODUCT_1, offer?.productId)
            assertFalse("tok-1" in offer.toString())
            assertTrue(w.logger.events.none { "tok-1" in it.toString() })
            assertTrue(w.oneShot.none { "tok-1" in it.toString() })
        }

    @Test
    fun `the expected next product follows the ladder and is none at max snoozes, over the cap or for a broken fee`() {
        val session =
            com.yawnandpawn.app.core.session
                .ringSession()
        assertEquals("snooze_usd_01", UsdFeeLadder.expectedNextProduct(session))
        assertEquals("snooze_usd_03", UsdFeeLadder.expectedNextProduct(session.copy(snoozesGranted = 2)))
        assertNull(UsdFeeLadder.expectedNextProduct(session.copy(snoozesGranted = 5)), "max snoozes")
        val dear = session.copy(config = session.config.copy(baseFeeTier = 10, maxSnoozes = 5), snoozesGranted = 4)
        assertEquals("snooze_usd_50", UsdFeeLadder.expectedNextProduct(dear))
        val overCap = session.copy(config = session.config.copy(baseFeeTier = 10, maxSnoozes = 5), snoozesGranted = 5)
        assertNull(UsdFeeLadder.expectedNextProduct(overCap.copy(config = overCap.config.copy(maxSnoozes = 6))), "over the cap")
        assertNull(UsdFeeLadder.expectedNextProduct(session.copy(config = session.config.copy(baseFeeTier = 0))), "invalid fee")
    }
}
