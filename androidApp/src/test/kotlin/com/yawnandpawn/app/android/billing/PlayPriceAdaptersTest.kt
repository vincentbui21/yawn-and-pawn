package com.yawnandpawn.app.android.billing

import com.yawnandpawn.app.core.billing.LivePrice
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.billing.PriceEntry
import com.yawnandpawn.app.core.billing.ProductDetailsResult
import com.yawnandpawn.app.core.billing.ProductPrice
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakePriceCacheStore
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.FakeUserLockState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/** Story 4.12: the product details source, the live price, the billing country and the display prices over Play. */
class PlayPriceAdaptersTest {
    private val facade = FakeBillingClientFacade(available = true)
    private val clock = FakeClock()
    private val logger = FakeLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
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

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `the product details source asks Play for the products it is given`() =
        runBlocking<Unit> {
            facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01", "€1.19", 1_190_000, "EUR")

            val result = AndroidProductDetailsSource(billing).fetch(listOf("snooze_usd_01", "snooze_usd_02"))

            assertEquals(
                Outcome.Success(
                    ProductDetailsResult(listOf(ProductPrice("snooze_usd_01", "€1.19", Money(1_190_000, "EUR"))), setOf("snooze_usd_02")),
                ),
                result,
            )
        }

    @Test
    fun `the live price is stored in the price cache, keeping the other prices of the same currency`() =
        runBlocking<Unit> {
            val old = PriceEntry("snooze_usd_01", "€1.00", Money(1_000_000, "EUR"), clock.now() - 2.days)
            val kept = PriceEntry("snooze_usd_02", "€2.00", Money(2_000_000, "EUR"), clock.now() - 2.days)
            val cache = FakePriceCacheStore(PriceCatalogSnapshot(mapOf(old.productId to old, kept.productId to kept)))
            facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01", "€1.19", 1_190_000, "EUR")

            val live = PlayLivePrices(billing, cache, clock, logger).livePrice("snooze_usd_01")

            assertEquals(LivePrice("snooze_usd_01", Money(1_190_000, "EUR"), "€1.19"), live)
            assertEquals(
                PriceEntry("snooze_usd_01", "€1.19", Money(1_190_000, "EUR"), clock.now()),
                cache.snapshot.priceFor("snooze_usd_01"),
            )
            assertEquals(kept, cache.snapshot.priceFor("snooze_usd_02"))
        }

    @Test
    fun `no live price when Play has none or does not answer in time, and a failed cache write is only logged`() =
        runBlocking<Unit> {
            val cache = FakePriceCacheStore()
            assertNull(PlayLivePrices(billing, cache, clock, logger).livePrice("snooze_usd_01"))

            facade.holdProducts = true
            assertNull(PlayLivePrices(billing, cache, clock, logger, timeout = 50.milliseconds).livePrice("snooze_usd_01"))

            facade.holdProducts = false
            facade.products["snooze_usd_01"] = FakeBillingClientFacade.product("snooze_usd_01")
            cache.failure = DomainError.StorageFailure("disk")
            assertEquals("$1.00", PlayLivePrices(billing, cache, clock, logger).livePrice("snooze_usd_01")?.formattedPrice)
            assertEquals(LogEvent.OperationFailed.of("store live price", DomainError.StorageFailure("disk")), logger.events.last())
        }

    @Test
    fun `the billing country is Play's after the connection, unknown before`() =
        runBlocking<Unit> {
            val country = PlayBillingCountry(billing)
            assertNull(country.countryCode())

            facade.countryCode = "ca"
            billing.queryPurchases()

            assertEquals("CA", country.countryCode())
        }

    @Test
    fun `display prices follow the cache and never show an expired price`() {
        val fresh = PriceEntry("snooze_usd_01", "$1.00", Money(1_000_000, "USD"), clock.now())
        val expired = PriceEntry("snooze_usd_02", "$2.00", Money(2_000_000, "USD"), clock.now() - 31.days)
        val catalog = FakePriceCatalog(PriceCatalogSnapshot(mapOf(fresh.productId to fresh, expired.productId to expired)))

        val prices = CachedDisplayPrices(catalog, clock, scope)

        assertEquals(Money(1_000_000, "USD"), prices.priceOf("snooze_usd_01"))
        assertNull(prices.priceOf("snooze_usd_02"))
        assertNull(prices.priceOf("snooze_usd_03"))
    }
}
