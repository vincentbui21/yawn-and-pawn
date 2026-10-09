package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceCachePolicy
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.aPriceSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days

/** Story 4.13: the sheet's display prices follow Story 4.3's price cache, never showing an expired entry. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogDisplayPricesTest {
    @Test
    fun `a cached price shows until it expires, and a refreshed cache shows at once`() =
        runTest(UnconfinedTestDispatcher()) {
            val clock = FakeClock(DEFAULT_FAKE_INSTANT)
            val catalog = FakePriceCatalog()
            val scope = backgroundScope
            val prices = CatalogDisplayPrices(catalog, clock, scope)
            assertNull(prices.priceOf("snooze_usd_01"), "nothing cached yet")

            catalog.snapshot = aPriceSnapshot(tiers = 1..2, fetchedAt = DEFAULT_FAKE_INSTANT)
            assertEquals(Money.of(1, "USD"), prices.priceOf("snooze_usd_01"))
            assertEquals(Money.of(2, "USD"), prices.priceOf("snooze_usd_02"))
            assertNull(prices.priceOf("snooze_usd_03"), "not in the cache")

            clock.advanceBy(PriceCachePolicy.EXPIRES_AFTER + 1.days)
            assertNull(prices.priceOf("snooze_usd_01"), "expired reads as not loaded")
        }
}
