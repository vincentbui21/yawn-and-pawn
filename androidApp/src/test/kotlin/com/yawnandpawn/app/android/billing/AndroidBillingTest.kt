package com.yawnandpawn.app.android.billing

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.Purchase
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.LaunchResult
import com.yawnandpawn.app.core.billing.LivePrice
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PlayPurchaseState
import com.yawnandpawn.app.core.billing.ProductDetailsResult
import com.yawnandpawn.app.core.billing.ProductPrice
import com.yawnandpawn.app.core.billing.PurchaseUpdate
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseFailureKind
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.aPurchaseIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowKeyguardManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Story 4.12: `AndroidBilling` over a fake [BillingClientFacade], never Play. Calls run with short real time limits, so
 * a hung Play is tested as it happens.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidBillingTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val facade = FakeBillingClientFacade(available = true)
    private val userLock = FakeUserLockState(unlocked = true)
    private val logger = FakeLogger()
    private val clock = FakeMonotonicClock(1_000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).create().get()
    private var screen: Activity? = activity
    private val timeouts =
        BillingTimeouts(
            connect = 200.milliseconds,
            query = 200.milliseconds,
            consumeCall = 200.milliseconds,
            launch = 200.milliseconds,
            wakeScreen = 50.milliseconds,
            reconnectFirst = 10.milliseconds,
            reconnectMax = 40.milliseconds,
            reconnectAttempts = 3,
        )

    private fun billing(main: kotlin.coroutines.CoroutineContext = Dispatchers.Unconfined): AndroidBilling =
        AndroidBilling(facade, userLock, { screen }, clock, logger, scope, main, timeouts)

    @After
    fun tearDown() = scope.cancel()

    // ---- before the first unlock (AD-15) ---------------------------------------------------------------------------

    @Test
    fun `before the first unlock nothing connects and every call says billing is unavailable`() =
        runBlocking<Unit> {
            userLock.unlocked = false
            val billing = billing()
            billing.init()

            val unavailable = DomainError.BillingUnavailable(AndroidBilling.BEFORE_UNLOCK)
            assertEquals(Outcome.Failure(unavailable), billing.launch(aPurchaseIntent(), "install-1"))
            assertEquals(Outcome.Failure(unavailable), billing.queryPurchases())
            assertEquals(ConsumeResult.Failed(AndroidBilling.BEFORE_UNLOCK), billing.consume(PurchaseToken("t")))
            val details = assertIs<Outcome.Failure<DomainError>>(billing.productDetails(SnoozeProducts.all)).error
            assertEquals(DomainError.ProductDetailsFailed(transient = true, cause = AndroidBilling.BEFORE_UNLOCK), details)
            assertEquals(0, facade.startConnections)
            assertTrue(facade.launches.isEmpty())

            // init() before the unlock connects once the user unlocks.
            userLock.unlock()
            withTimeout(2.seconds) { while (facade.startConnections < 1) delay(5) }
            assertTrue(facade.isReady)
        }

    // ---- connection --------------------------------------------------------------------------------------------------

    @Test
    fun `init connects once after the unlock and reads the billing country`() {
        val billing = billing()

        billing.init()
        billing.init()

        assertEquals(1, facade.startConnections)
        assertTrue(facade.isReady)
        assertEquals("US", billing.billingCountry.value)
    }

    @Test
    fun `init returns at once even when Play never answers the connection`() {
        facade.setupCode = null
        val billing = billing()

        val started = TimeSource.Monotonic.markNow()
        billing.init()

        assertTrue(started.elapsedNow() < 100.milliseconds, "init waited ${started.elapsedNow()}")
    }

    @Test
    fun `a lost service reconnects, and a permanent failure stops the backoff`() =
        runBlocking<Unit> {
            val billing = billing()
            billing.init()
            assertEquals(1, facade.startConnections)

            facade.disconnect()
            withTimeout(2.seconds) { while (facade.startConnections < 2) delay(5) }
            assertTrue(facade.isReady)

            facade.setupCode = BillingResponseCode.BILLING_UNAVAILABLE
            facade.disconnect()
            withTimeout(2.seconds) { while (facade.startConnections < 3) delay(5) }
            delay(150)
            assertEquals(3, facade.startConnections, "no Play Store: no retry")
            assertTrue(logger.events.contains(LogEvent.OperationFailed("connect billing", "BILLING_UNAVAILABLE")))
        }

    @Test
    fun `a transient connection failure is retried with the backoff, at most the set number of times`() =
        runBlocking<Unit> {
            facade.setupCode = BillingResponseCode.SERVICE_UNAVAILABLE
            billing().init()

            withTimeout(2.seconds) { while (facade.startConnections < 3) delay(5) }
            delay(150)
            assertEquals(3, facade.startConnections)
        }

    @Test
    fun `the backoff doubles up to its maximum and stops after the set number of tries`() =
        runTest {
            facade.setupCode = BillingResponseCode.SERVICE_UNAVAILABLE
            val starts = mutableListOf<Long>()
            facade.onStartConnection = { starts += testScheduler.currentTime }
            val billing =
                AndroidBilling(
                    facade,
                    userLock,
                    { screen },
                    clock,
                    logger,
                    backgroundScope,
                    Dispatchers.Unconfined,
                    BillingTimeouts(reconnectFirst = 1.seconds, reconnectMax = 4.seconds, reconnectAttempts = 6),
                )

            billing.init()
            advanceTimeBy(100.seconds)

            assertEquals(listOf(0L, 1_000, 3_000, 7_000, 11_000, 15_000), starts)
        }

    @Test
    fun `a flapping Play Store is not rebound in a storm, the backoff grows across drops`() =
        runTest {
            facade.dropAfterConnect = true
            val billing =
                AndroidBilling(
                    facade,
                    userLock,
                    { screen },
                    clock,
                    logger,
                    backgroundScope,
                    Dispatchers.Unconfined,
                    BillingTimeouts(reconnectFirst = 1.seconds, reconnectMax = 60.seconds),
                )

            billing.init()
            advanceTimeBy(5.seconds)

            assertTrue(facade.startConnections <= 4, "${facade.startConnections} connections in 5 s")
            assertTrue(facade.startConnections >= 2, "it still reconnects")
        }

    @Test
    fun `callers that come while Play connects join the same setup`() =
        runBlocking<Unit> {
            facade.setupCode = null
            val billing = billing()

            val first = async(Dispatchers.Default) { billing.queryPurchases() }
            val second = async(Dispatchers.Default) { billing.queryPurchases() }
            assertIs<Outcome.Failure<DomainError>>(first.await())
            assertIs<Outcome.Failure<DomainError>>(second.await())

            assertEquals(1, facade.startConnections)
        }

    @Test
    fun `a setup that never answers is ended, so the next call connects afresh`() =
        runBlocking<Unit> {
            facade.setupCode = null
            val billing = billing()
            assertIs<Outcome.Failure<DomainError>>(billing.queryPurchases())
            assertEquals(1, facade.endConnections, "the hung client is ended")

            facade.setupCode = BillingResponseCode.OK
            facade.owned += FakeBillingClientFacade.purchase("snooze_usd_01")

            assertIs<Outcome.Success<*>>(billing.queryPurchases())
            assertEquals(2, facade.startConnections)
        }

    @Test
    fun `a service dropped during the setup answers the waiting call at once, and the next call reconnects`() =
        runBlocking<Unit> {
            facade.setupCode = null
            val billing = billing()
            val waiting = async(Dispatchers.Default) { billing.queryPurchases() }
            withTimeout(2.seconds) { while (facade.startConnections < 1) delay(5) }

            facade.setupCode = BillingResponseCode.OK
            facade.disconnect()

            assertEquals(
                Outcome.Failure(DomainError.BillingUnavailable("connect: SERVICE_DISCONNECTED")),
                waiting.await(),
            )
            assertIs<Outcome.Success<*>>(billing.queryPurchases())
            assertEquals(2, facade.startConnections)
        }

    @Test
    fun `DEVELOPER_ERROR from a setup while Play reconnects by itself is not permanent`() =
        runBlocking<Unit> {
            facade.nextSetupCodes += BillingResponseCode.DEVELOPER_ERROR
            val billing = billing()

            assertIs<Outcome.Failure<DomainError>>(billing.queryPurchases())
            assertIs<Outcome.Success<*>>(billing.queryPurchases(), "the next call succeeds")

            val other = FakeBillingClientFacade(available = true).apply { nextSetupCodes += BillingResponseCode.DEVELOPER_ERROR }
            AndroidBilling(other, userLock, { screen }, clock, logger, scope, Dispatchers.Unconfined, timeouts).init()
            withTimeout(2.seconds) { while (!other.isReady) delay(5) }
            assertEquals(2, other.startConnections, "the backoff retried")
        }

    // ---- launch ------------------------------------------------------------------------------------------------------

    @Test
    fun `launch opens Play from the wake screen with the install id and the session id, on the main thread`() =
        runBlocking<Unit> {
            facade.products["snooze_usd_02"] = FakeBillingClientFacade.product("snooze_usd_02")
            val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
            try {
                val result = billing(main).launch(aPurchaseIntent(productId = "snooze_usd_02", sessionId = "session-9"), "install-7")

                assertEquals(Outcome.Success(LaunchResult.Launched), result)
                val launch = facade.launches.single()
                assertEquals("snooze_usd_02", launch.productId)
                assertEquals("install-7", launch.accountId, "obfuscatedAccountId = install id")
                assertEquals("session-9", launch.profileId, "obfuscatedProfileId = session id")
                assertEquals(activity, launch.activity)
                assertTrue(launch.thread.startsWith("fake-main"), launch.thread)
            } finally {
                main.close()
            }
        }

    @Test
    fun `launch without a resumed wake screen fails without opening Play`() =
        runBlocking<Unit> {
            facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01")
            screen = null

            val result = billing().launch(aPurchaseIntent(), "install-1")

            assertEquals(Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.Error)), result)
            assertTrue(facade.launches.isEmpty())
        }

    @Test
    fun `launch of a product Play does not know fails, offline when the query was offline`() =
        runBlocking<Unit> {
            assertEquals(
                Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.Error)),
                billing().launch(aPurchaseIntent(), "install-1"),
            )
            facade.productCode = BillingResponseCode.NETWORK_ERROR
            assertEquals(
                Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.Offline)),
                billing().launch(aPurchaseIntent(), "install-1"),
            )
            assertTrue(facade.launches.isEmpty())
        }

    @Test
    fun `launch when Play cannot connect fails by the connection's code, and a hung connection is bounded`() =
        runBlocking<Unit> {
            facade.setupCode = BillingResponseCode.SERVICE_UNAVAILABLE
            assertEquals(
                Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.Offline)),
                billing().launch(aPurchaseIntent(), "install-1"),
            )

            facade.setupCode = null
            val started = TimeSource.Monotonic.markNow()
            assertEquals(
                Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.Error)),
                billing().launch(aPurchaseIntent(), "install-1"),
            )
            assertTrue(started.elapsedNow() < 2.seconds)
        }

    @Test
    fun `launch answers map through the table, and Play's echo of an error is not delivered as an update`() =
        runBlocking<Unit> {
            facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01")
            val billing = billing()
            val expected =
                mapOf(
                    BillingResponseCode.USER_CANCELED to LaunchResult.Cancelled,
                    BillingResponseCode.ITEM_ALREADY_OWNED to LaunchResult.ItemAlreadyOwned,
                    BillingResponseCode.NETWORK_ERROR to LaunchResult.Failed(PurchaseFailureKind.Offline),
                    BillingResponseCode.DEVELOPER_ERROR to LaunchResult.Failed(PurchaseFailureKind.Error),
                )
            expected.forEach { (code, result) ->
                facade.launchCode = code
                assertEquals(Outcome.Success(result), billing.launch(aPurchaseIntent(), "install-1"), "code $code")
            }

            // Every echo was dropped: the next update is the first one delivered.
            facade.deliver(BillingResponseCode.USER_CANCELED)
            assertEquals(PurchaseUpdate.Cancelled, billing.purchaseUpdates.first())
        }

    @Test
    fun `an echo Play posts after the launch returned is dropped once, a second copy is delivered`() =
        runBlocking<Unit> {
            facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01")
            facade.launchCode = BillingResponseCode.ITEM_ALREADY_OWNED
            facade.echoLaunchErrors = false
            val billing = billing()
            billing.launch(aPurchaseIntent(), "install-1")

            facade.deliver(BillingResponseCode.ITEM_ALREADY_OWNED) // the echo, posted after the launch returned
            facade.deliver(BillingResponseCode.ITEM_ALREADY_OWNED) // a real answer of a later sheet

            assertEquals(PurchaseUpdate.ItemAlreadyOwned, billing.purchaseUpdates.first())
            facade.deliver(BillingResponseCode.USER_CANCELED)
            assertEquals(PurchaseUpdate.Cancelled, billing.purchaseUpdates.first(), "only one ItemAlreadyOwned was delivered")
        }

    @Test
    fun `a launch that throws fails as an error`() =
        runBlocking<Unit> {
            facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01")
            facade.throwOnLaunch = IllegalStateException("boom")

            assertEquals(
                Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.Error)),
                billing().launch(aPurchaseIntent(), "install-1"),
            )
        }

    // ---- updates -----------------------------------------------------------------------------------------------------

    @Test
    fun `updates sent before anyone collects are kept, and map purchases with their ids`() =
        runBlocking<Unit> {
            val billing = billing()
            facade.deliver(
                BillingResponseCode.OK,
                listOf(
                    FakeBillingClientFacade.purchase("snooze_usd_01", accountId = "install-1", profileId = "session-1"),
                    FakeBillingClientFacade.purchase("snooze_usd_02", stateCode = PlayStateCodes.PENDING, orderId = ""),
                    FakeBillingClientFacade.purchase("snooze_usd_03", stateCode = PlayStateCodes.UNSPECIFIED),
                ),
            )
            facade.deliver(BillingResponseCode.SERVICE_UNAVAILABLE)

            val updates = billing.purchaseUpdates.take(2).toList()

            val purchases = assertIs<PurchaseUpdate.Purchases>(updates[0]).purchases
            assertEquals(listOf("snooze_usd_01", "snooze_usd_02"), purchases.map { it.productId }, "UNSPECIFIED dropped")
            assertEquals("install-1", purchases[0].accountId)
            assertEquals("session-1", purchases[0].profileId)
            assertNull(purchases[1].orderId)
            assertEquals(PurchaseUpdate.Failed(PurchaseFailureKind.Offline), updates[1])
        }

    // ---- queries and consume -----------------------------------------------------------------------------------------

    @Test
    fun `queryPurchases maps what Play owns, and a failure or a hang is a billing error`() =
        runBlocking<Unit> {
            facade.owned += FakeBillingClientFacade.purchase("snooze_usd_04")
            val billing = billing()

            val owned = assertIs<Outcome.Success<List<com.yawnandpawn.app.core.billing.PurchaseSnapshot>>>(billing.queryPurchases()).value
            assertEquals(listOf("snooze_usd_04"), owned.map { it.productId })

            facade.purchasesCode = BillingResponseCode.SERVICE_DISCONNECTED
            assertEquals(Outcome.Failure(DomainError.BillingUnavailable("query purchases: SERVICE_DISCONNECTED")), billing.queryPurchases())

            facade.purchasesCode = BillingResponseCode.OK
            facade.holdPurchases = true
            val error = assertIs<Outcome.Failure<DomainError>>(billing.queryPurchases()).error
            assertIs<DomainError.BillingUnavailable>(error)
        }

    @Test
    fun `consume maps OK, ITEM_NOT_OWNED and failures, and a hung consume fails in time`() =
        runBlocking<Unit> {
            val billing = billing()
            val token = PurchaseToken("secret-token-value")

            assertEquals(ConsumeResult.Consumed, billing.consume(token))
            facade.consumeCode = BillingResponseCode.ITEM_NOT_OWNED
            assertEquals(ConsumeResult.NotOwned, billing.consume(token))
            facade.consumeCode = BillingResponseCode.SERVICE_UNAVAILABLE
            assertEquals(ConsumeResult.Failed("SERVICE_UNAVAILABLE"), billing.consume(token))
            facade.holdConsume = true
            assertIs<ConsumeResult.Failed>(billing.consume(token))
            assertEquals(List(4) { "secret-token-value" }, facade.consumed)
        }

    @Test
    fun `no purchase token ever reaches the log`() =
        runBlocking<Unit> {
            val billing = billing()
            val token = "very-secret-token"
            facade.consumeCode = BillingResponseCode.ERROR
            billing.consume(PurchaseToken(token))
            facade.deliver(BillingResponseCode.OK, listOf(FakeBillingClientFacade.purchase("snooze_usd_01", token = token)))
            facade.deliver(BillingResponseCode.ERROR)
            facade.holdConsume = true
            billing.consume(PurchaseToken(token))
            val update = billing.purchaseUpdates.first()

            assertFalse(update.toString().contains(token))
            assertTrue(logger.events.isNotEmpty())
            logger.events.forEach { assertFalse(it.toString().contains(token), "$it") }
        }

    // ---- product details ---------------------------------------------------------------------------------------------

    @Test
    fun `product details map each offer's price and report what Play could not give, 20 ids per query`() =
        runBlocking<Unit> {
            facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01", "€1.19", 1_190_000, "EUR")
            facade.products["snooze_usd_02"] = PlayProduct("snooze_usd_02", null, null, null)
            facade.products["snooze_usd_03"] = FakeBillingClientFacade.product("snooze_usd_03", "x", 1_000_000, "euro")
            facade.products["snooze_usd_04"] = FakeBillingClientFacade.product("snooze_usd_04", "€4.76", 4_760_000, "EUR")
            facade.reportedUnfetched = setOf("snooze_usd_04")

            val result =
                assertIs<Outcome.Success<com.yawnandpawn.app.core.billing.ProductDetailsResult>>(
                    billing().productDetails(SnoozeProducts.all),
                ).value

            assertEquals(listOf(ProductPrice("snooze_usd_01", "€1.19", Money(1_190_000, "EUR"))), result.prices)
            assertEquals(SnoozeProducts.all.toSet() - "snooze_usd_01", result.unfetched)
            assertEquals(listOf(20, 20, 10), facade.productQueries.map { it.size })
        }

    @Test
    fun `a failed or hung product query fails the refresh, transient only when trying again may help`() =
        runBlocking<Unit> {
            facade.productCode = BillingResponseCode.SERVICE_UNAVAILABLE
            val offline = assertIs<Outcome.Failure<DomainError>>(billing().productDetails(listOf("snooze_usd_01"))).error
            assertEquals(DomainError.ProductDetailsFailed(true, "query product details: SERVICE_UNAVAILABLE"), offline)

            facade.productCode = BillingResponseCode.DEVELOPER_ERROR
            val broken = assertIs<Outcome.Failure<DomainError>>(billing().productDetails(listOf("snooze_usd_01"))).error
            assertEquals(DomainError.ProductDetailsFailed(false, "query product details: DEVELOPER_ERROR"), broken)

            facade.productCode = BillingResponseCode.OK
            facade.holdProducts = true
            val hung = assertIs<Outcome.Failure<DomainError>>(billing().productDetails(listOf("snooze_usd_01"))).error
            assertTrue((hung as DomainError.ProductDetailsFailed).transient)
        }

    @Test
    fun `the live price is Play's answer now, and the launch after it needs no second query`() =
        runBlocking<Unit> {
            facade.products["snooze_usd_05"] = FakeBillingClientFacade.product("snooze_usd_05", "$5.00", 5_000_000, "USD")
            val billing = billing()

            assertEquals(LivePrice("snooze_usd_05", Money(5_000_000, "USD"), "$5.00"), billing.livePrice("snooze_usd_05"))
            assertNull(billing.livePrice("snooze_usd_06"))
            billing.launch(aPurchaseIntent(productId = "snooze_usd_05"), "install-1")

            assertEquals(listOf(listOf("snooze_usd_05"), listOf("snooze_usd_06")), facade.productQueries)
            assertEquals(1, facade.launches.size)
        }

    // ---- review fixes ------------------------------------------------------------------------------------------------

    @Test
    fun `a main thread busy past the limit fails the launch, and the abandoned launch never runs later`() =
        runBlocking<Unit> {
            facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01")
            val gate = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor { Thread(it, "busy-main") }
            val main = executor.asCoroutineDispatcher()
            try {
                executor.execute { gate.await() } // the main thread is busy

                val result = billing(main).launch(aPurchaseIntent(), "install-1")

                assertEquals(Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.Error)), result)
                gate.countDown()
                delay(200)
                assertTrue(facade.launches.isEmpty(), "no late launch")
            } finally {
                gate.countDown()
                main.close()
            }
        }

    @Test
    fun `an Error thrown by the launch never leaves updates held, so a later purchase is delivered`() =
        runBlocking<Unit> {
            facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01")
            facade.throwOnLaunch = NoClassDefFoundError("play")
            val billing = billing()

            assertEquals(
                Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.Error)),
                billing.launch(aPurchaseIntent(), "install-1"),
            )
            facade.deliver(BillingResponseCode.OK, listOf(FakeBillingClientFacade.purchase("snooze_usd_01")))

            val update = withTimeout(1.seconds) { billing.purchaseUpdates.first() }
            assertEquals(listOf("snooze_usd_01"), assertIs<PurchaseUpdate.Purchases>(update).purchases.map { it.productId })
        }

    @Test
    fun `Play never opens behind the keyguard - a phone locked by the launch fails it as UnlockFailed`() =
        runBlocking<Unit> {
            facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01")
            val keyguard = ApplicationProvider.getApplicationContext<Context>().getSystemService(KeyguardManager::class.java)
            shadowOf(keyguard).setKeyguardLocked(true)
            try {
                val billing =
                    AndroidBilling(facade, userLock, { screen }, clock, logger, scope, Dispatchers.Unconfined, timeouts) {
                        keyguard.isKeyguardLocked
                    }

                val result = billing.launch(aPurchaseIntent(), "install-1")

                assertEquals(Outcome.Success(LaunchResult.Failed(PurchaseFailureKind.UnlockFailed)), result)
                assertTrue(facade.launches.isEmpty())
            } finally {
                ShadowKeyguardManager.reset()
            }
        }

    @Test
    fun `a pending purchase that completes arrives as two updates in order, Pending then Purchased, from Play JSON`() =
        runBlocking<Unit> {
            val billing = billing()
            facade.deliver(BillingResponseCode.OK, listOf(Purchase(purchaseJson(state = 4), "sig").toPlayPurchase()))
            facade.deliver(BillingResponseCode.OK, listOf(Purchase(purchaseJson(state = 0), "sig").toPlayPurchase()))

            val updates = withTimeout(1.seconds) { billing.purchaseUpdates.take(2).toList() }

            val states = updates.map { (it as PurchaseUpdate.Purchases).purchases.single() }
            assertEquals(listOf("tok", "tok"), states.map { it.token.value })
            assertEquals(listOf(PlayPurchaseState.Pending, PlayPurchaseState.Purchased), states.map { it.purchaseState })
            assertEquals("session-1", states[1].profileId)
        }

    @Test
    fun `a later product query that fails keeps the earlier queries' prices and reports the rest unfetched`() =
        runBlocking<Unit> {
            facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01")
            var queries = 0
            val failing =
                object : BillingClientFacade by facade {
                    override fun queryProducts(
                        productIds: List<String>,
                        onResult: (ProductQuery) -> Unit,
                    ) {
                        queries++
                        if (queries == 1) {
                            facade.queryProducts(productIds, onResult)
                        } else {
                            onResult(ProductQuery(PlayResult(BillingResponseCode.NETWORK_ERROR)))
                        }
                    }
                }
            val partial = AndroidBilling(failing, userLock, { screen }, clock, logger, scope, Dispatchers.Unconfined, timeouts)

            val result = assertIs<Outcome.Success<ProductDetailsResult>>(partial.productDetails(SnoozeProducts.all)).value

            assertEquals(listOf("snooze_usd_01"), result.prices.map { it.productId })
            assertEquals(SnoozeProducts.all.toSet() - "snooze_usd_01", result.unfetched)
            assertEquals(2, queries)
        }

    private fun purchaseJson(state: Int): String =
        """{"orderId":"GPA.1","packageName":"com.yawnandpawn.app","productId":"snooze_usd_01","purchaseTime":1800000000000,""" +
            """"purchaseState":$state,"purchaseToken":"tok","obfuscatedAccountId":"install-1","obfuscatedProfileId":"session-1",""" +
            """"quantity":1,"acknowledged":false}"""
}
