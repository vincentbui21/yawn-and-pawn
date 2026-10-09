package com.yawnandpawn.app.android.billing

import com.yawnandpawn.app.core.billing.LivePrice
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceCacheStore
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.billing.ProductDetailsResult
import com.yawnandpawn.app.core.billing.ProductDetailsSource
import com.yawnandpawn.app.core.billing.ProductPrice
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The production [ProductDetailsSource] (Story 4.12, replacing `UnavailableProductDetailsSource`): Play's prices of the
 * snooze products through [AndroidBilling.productDetails] (`queryProductDetailsAsync`, INAPP; each product's one-time
 * offer as `formattedPrice`, `priceAmountMicros` and `priceCurrencyCode`). Unfetched products are reported, never a
 * failure of the whole refresh.
 */
class AndroidProductDetailsSource(
    private val billing: AndroidBilling,
) : ProductDetailsSource {
    override suspend fun fetch(productIds: List<String>): Outcome<ProductDetailsResult, DomainError> = billing.productDetails(productIds)
}

/**
 * Play's LIVE price of a snooze product (Story 4.12 for Story 4.13's `LivePriceSource`; 4.3 and 4.8 handoffs): asked
 * when the confirm sheet opens and at the Pay tap, from the product's `ProductDetails` now, never the cache. Null when
 * Play gives none within [timeout] (offline, not loaded): billing could not launch then anyway. Each answer is also
 * stored in the price cache, so the next display shows it (the 4.3 deferral). A failed cache write is logged only.
 */
class PlayLivePrices(
    private val billing: AndroidBilling,
    private val cache: PriceCacheStore,
    private val clock: Clock,
    private val logger: Logger,
    private val timeout: Duration = LIVE_PRICE_TIMEOUT,
) {
    suspend fun livePrice(productId: String): LivePrice? {
        val live = withTimeoutOrNull(timeout) { billing.livePrice(productId) } ?: return null
        val answer = ProductDetailsResult(listOf(ProductPrice(live.productId, live.formattedPrice, live.price)))
        val fetchedAt = clock.now()
        val stored = cache.update { it.mergedWith(answer, SnoozeProducts.all, fetchedAt) }
        if (stored is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(STORE_LIVE_PRICE, stored.error))
        return live
    }

    companion object {
        /** How long the sheet's live price may take (default taken, owner can change). */
        val LIVE_PRICE_TIMEOUT: Duration = 8.seconds
        private const val STORE_LIVE_PRICE = "store live price"
    }
}

/**
 * The Play billing country (Story 4.12 for Story 4.13's `BillingCountry` and its tax note): `getBillingConfigAsync`,
 * read by [AndroidBilling] after each connection. Null while unknown (before the first connection, or Play gave none).
 */
class PlayBillingCountry(
    private val billing: AndroidBilling,
) {
    fun countryCode(): String? = billing.billingCountry.value
}

/**
 * The cached display prices (Story 4.3's cache, for Story 4.13's `DisplayPrices`): the snooze button and the sheet
 * before the live price arrives. Display only, and never a price that has expired ([PriceCatalogSnapshot.displayablePriceFor]).
 * Follows the cache on [scope] from the first use, so a read never waits.
 */
class CachedDisplayPrices(
    catalog: PriceCatalog,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    private val snapshot: StateFlow<PriceCatalogSnapshot> =
        catalog.observe().stateIn(
            scope,
            SharingStarted.Eagerly,
            PriceCatalogSnapshot.EMPTY,
        )

    fun priceOf(productId: String): Money? = snapshot.value.displayablePriceFor(productId, clock.now())?.price
}
