package com.yawnandpawn.app.android.wake

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.yawnandpawn.app.android.billing.AndroidBilling
import com.yawnandpawn.app.android.billing.AndroidProductDetailsSource
import com.yawnandpawn.app.android.billing.BillingTimeouts
import com.yawnandpawn.app.android.billing.FakeBillingClientFacade
import com.yawnandpawn.app.android.billing.PlayLivePrices
import com.yawnandpawn.app.core.billing.CachedPriceCatalog
import com.yawnandpawn.app.core.billing.LivePrice
import com.yawnandpawn.app.core.billing.LiveSnoozeAvailability
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.billing.PriceEntry
import com.yawnandpawn.app.core.billing.SnoozeConditions
import com.yawnandpawn.app.core.billing.UsdFeeLadder
import com.yawnandpawn.app.core.billing.followingProduct
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeOffer
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeConnectivity
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakePriceCacheStore
import com.yawnandpawn.app.testing.FakePurchaseIntentStore
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.ui.wake.SnoozeSheet
import com.yawnandpawn.app.ui.wake.WakeMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Story 4.18 device check (Oppo A96, real Play, EUR): Snooze opened the confirm sheet for about 0.1 s, then it closed.
 * The live price the sheet asks Play for at open is stored in the price cache with a new fetch time (Story 4.12), so
 * the availability's offer carried a new cached `PriceEntry`, and the sheet compared offers including that price: it no
 * longer looked "still wanted" and closed for good.
 *
 * Production wiring, without a screen: the real [ConfirmSheetHost], [LiveSnoozeAvailability] over [SnoozeConditions],
 * [CachedPriceCatalog] and [CatalogDisplayPrices] over one price cache, and [PlayLivePrices] over [AndroidBilling] with
 * a fake Play client. [screen] does what `WakeActivity.sheetOverlay` does on each frame: the availability now, the
 * sheet, then [ConfirmSheetHost.follow].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmSheetPlayPricesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val clock = FakeClock()
    private val logger = FakeLogger()
    private val facade = FakeBillingClientFacade(available = true)
    private val billing =
        AndroidBilling(
            facade,
            FakeUserLockState(unlocked = true),
            { null },
            FakeMonotonicClock(),
            logger,
            scope,
            Dispatchers.Unconfined,
            BillingTimeouts(connect = 100.milliseconds, query = 100.milliseconds),
        )
    private val session = aSession()
    private val first = "snooze_usd_01"
    private val next = assertNotNull(UsdFeeLadder.followingProduct(session, SnoozeOffer(first, 1)))

    // The cache as the editor's fee ladder left it, a day ago: "Snooze 1: €1.09 · 2: €2.19".
    private val cache =
        FakePriceCacheStore(
            PriceCatalogSnapshot(
                listOf(
                    PriceEntry(first, "€1.09", Money(1_090_000, "EUR"), clock.now() - 1.days),
                    PriceEntry(next, "€2.19", Money(2_190_000, "EUR"), clock.now() - 1.days),
                ).associateBy { it.productId },
            ),
        )
    private val catalog = CachedPriceCatalog(AndroidProductDetailsSource(billing), cache, clock, logger)
    private val conditions = SnoozeConditions(FakeConnectivity(online = true), catalog, FakeUserLockState(unlocked = true), clock)
    private val policy = LiveSnoozeAvailability(conditions, UsdFeeLadder, logger)
    private val display = CatalogDisplayPrices(catalog, clock, scope)
    private val host =
        ConfirmSheetHost(
            live = PlayLivePrices(billing, cache, clock, logger, timeout = 200.milliseconds),
            display = display,
            reuse = NoReuse,
            ladder = UsdFeeLadder,
            ids = FakeIdGenerator(),
            intents = FakePurchaseIntentStore(),
            scope = scope,
            logger = logger,
        )
    private val sent = mutableListOf<SessionEvent>()

    private object NoReuse : ReuseChoices {
        override suspend fun acceptReuse() = Unit

        override suspend fun declineReuse() = Unit
    }

    init {
        // The wake screen collects the env (Story 4.7); the reducer then reads its latest value.
        scope.launch { conditions.observe().collect { } }
        clock.advanceBy(1.minutes)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun play(
        productId: String,
        formatted: String,
        micros: Long,
    ) {
        facade.products[productId] = FakeBillingClientFacade.product(productId, formatted, micros, "EUR")
    }

    /** One frame of the wake screen: the sheet it shows with the availability now, then `follow` (its SideEffect). */
    private fun screen(): SnoozeSheet? {
        val availability = policy.availability(session)
        val shown = host.sheet(session, availability, host.request.value, host.livePrices.value, host.sentPrices.value, showTaxNote = false)
        host.follow(session, availability)
        return shown
    }

    /** "Snooze · €1.09": the engine's `ShowSnoozeConfirm` with the offer its availability had at the tap. */
    private suspend fun tapSnooze() {
        awaitScreenSeesCache()
        val offer = assertIs<SnoozeAvailability.Available>(policy.availability(session)).offer
        host.onEffect(SessionEffect.ShowSnoozeConfirm(offer), SessionState.Ringing(session))
    }

    private suspend fun awaitLive(productId: String): LivePrice =
        withTimeout(2.seconds) {
            while (productId !in host.livePrices.value) delay(5)
            awaitScreenSeesCache()
            host.livePrices.value.getValue(productId)
        }

    /** Until the env (the snooze button's availability) and the display prices both hold the cache as it is now. */
    private suspend fun awaitScreenSeesCache() =
        withTimeout(2.seconds) {
            while (conditions.current().prices != cache.snapshot || display.snapshot.value != cache.snapshot) delay(5)
        }

    @Test
    fun `the sheet stays open once Play's live price, the same as the cached one, is stored in the cache`() =
        runBlocking {
            play(first, "€1.09", 1_090_000)
            play(next, "€2.19", 2_190_000)
            tapSnooze()
            awaitLive(first)
            awaitLive(next)
            assertEquals(clock.now(), cache.snapshot.priceFor(first)?.fetchedAt, "the live price was written to the cache")

            assertEquals(SnoozeSheet.Confirm(9, Money(1_090_000, "EUR"), Money(2_190_000, "EUR")), screen())
            assertNotNull(host.request.value, "the sheet is still open after the next frame")
            assertEquals(SnoozeSheet.Confirm(9, Money(1_090_000, "EUR"), Money(2_190_000, "EUR")), screen())
            assertTrue(logger.events.none { it is LogEvent.ConfirmSheetClosed }, "${logger.events}")
        }

    @Test
    fun `a live price other than the cached one is shown in the open sheet and Pay sends it`() =
        runBlocking {
            play(first, "€1.19", 1_190_000)
            play(next, "€2.39", 2_390_000)
            tapSnooze()
            awaitLive(first)
            awaitLive(next)

            val shown = assertIs<SnoozeSheet.Confirm>(screen())
            assertEquals(Money(1_190_000, "EUR"), shown.price)
            assertNotNull(host.request.value)

            host.onUpper(session, policy.availability(session), shown.price) { sent += it }
            awaitPay()
            val pay = sent.filterIsInstance<SessionEvent.PayConfirmed>().single()
            assertEquals(LivePrice(first, Money(1_190_000, "EUR"), "€1.19"), pay.livePrice)
        }

    @Test
    fun `while Play is slow the cached price shows, and no live price at Pay closes the sheet with No connection, No charge`() =
        runBlocking {
            facade.holdProducts = true
            tapSnooze()

            assertEquals(SnoozeSheet.Confirm(9, Money(1_090_000, "EUR"), Money(2_190_000, "EUR")), screen())
            assertNotNull(host.request.value)

            host.onUpper(session, policy.availability(session), Money(1_090_000, "EUR")) { sent += it }
            withTimeout(5.seconds) { while (host.request.value != null) delay(5) }
            assertEquals(listOf<SessionEvent>(SessionEvent.UserInteracted), sent)
            assertEquals(WakeMessage.PaymentOffline, host.messageFor(session, host.message.value))
            assertTrue(LogEvent.ConfirmSheetClosed("confirm", "no live price at Pay") in logger.events, "${logger.events}")
        }

    @Test
    fun `a sheet whose snooze stops being on sale closes, and the log says why`() =
        runBlocking {
            facade.holdProducts = true
            tapSnooze()
            screen()
            cache.update { PriceCatalogSnapshot.EMPTY }
            awaitScreenSeesCache()

            assertNull(screen())
            assertNull(host.request.value)
            assertTrue(
                LogEvent.ConfirmSheetClosed("confirm", "snooze unavailable: CatalogueNotLoaded") in logger.events,
                "${logger.events}",
            )
        }

    /**
     * Story 4.18 device run: on a fresh morning Play's connection had dropped, and Pay, which asked again with its own
     * 3 s limit, failed as "No connection. No charge." while the phone was online. Here Play takes 5 s to reconnect (the
     * first query's answer comes then), with the production limits ([BillingTimeouts] and [PlayLivePrices] defaults), on
     * virtual time.
     */
    @Test
    fun `4_18 device run - Play reconnects for 5 s - a Pay tapped meanwhile pays the price on screen once Play answers`() {
        play(first, "€1.09", 1_090_000)
        play(next, "€2.19", 2_190_000)
        runBlocking { awaitScreenSeesCache() }
        val offer = assertIs<SnoozeAvailability.Available>(policy.availability(session)).offer
        facade.setupCode = null // The connection dropped overnight: Play holds the reconnect.
        runTest {
            val reconnecting =
                AndroidBilling(
                    facade,
                    FakeUserLockState(unlocked = true),
                    { null },
                    FakeMonotonicClock(),
                    logger,
                    backgroundScope,
                    Dispatchers.Unconfined,
                    BillingTimeouts(),
                )
            val morning =
                ConfirmSheetHost(
                    PlayLivePrices(reconnecting, cache, clock, logger),
                    display,
                    NoReuse,
                    UsdFeeLadder,
                    FakeIdGenerator(),
                    FakePurchaseIntentStore(),
                    backgroundScope,
                    logger,
                )
            morning.onEffect(SessionEffect.ShowSnoozeConfirm(offer), SessionState.Ringing(session))
            runCurrent()
            advanceTimeBy(1.seconds)
            val availability = policy.availability(session)
            val shown =
                assertIs<SnoozeSheet.Confirm>(
                    morning.sheet(session, availability, morning.request.value, morning.livePrices.value, morning.sentPrices.value, false),
                )
            assertEquals(Money(1_090_000, "EUR"), shown.price, "the cached price shows while Play reconnects")

            morning.onUpper(session, availability, shown.price) { sent += it }
            advanceTimeBy(4.seconds - 1.milliseconds)
            assertNotNull(morning.request.value, "no false No connection after the 3 s Pay limit")
            assertTrue(sent.isEmpty(), "$sent")

            facade.answerSetup(BillingResponseCode.OK) // 5 s in, Play is back and answers the query.
            runCurrent()

            val pay = sent.filterIsInstance<SessionEvent.PayConfirmed>().single()
            assertEquals(LivePrice(first, Money(1_090_000, "EUR"), "€1.09"), pay.livePrice)
            assertEquals(1, facade.startConnections, "one reconnect, joined by every question")
            assertNull(morning.messageFor(session, morning.message.value))
            assertTrue(logger.events.none { it is LogEvent.ConfirmSheetClosed }, "${logger.events}")
        }
    }

    private suspend fun awaitPay() =
        withTimeout(2.seconds) {
            while (sent.none { it is SessionEvent.PayConfirmed }) delay(5)
        }
}
