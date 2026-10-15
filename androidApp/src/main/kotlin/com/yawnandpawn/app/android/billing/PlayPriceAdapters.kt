package com.yawnandpawn.app.android.billing

import com.yawnandpawn.app.core.billing.BillingCountry
import com.yawnandpawn.app.core.billing.LivePrice
import com.yawnandpawn.app.core.billing.LivePriceSource
import com.yawnandpawn.app.core.billing.PriceCacheStore
import com.yawnandpawn.app.core.billing.ProductDetailsResult
import com.yawnandpawn.app.core.billing.ProductDetailsSource
import com.yawnandpawn.app.core.billing.ProductPrice
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.Clock
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
) : LivePriceSource {
    override suspend fun livePrice(productId: String): LivePrice? {
        val live = withTimeoutOrNull(timeout) { billing.livePrice(productId) } ?: return null
        val answer = ProductDetailsResult(listOf(ProductPrice(live.productId, live.formattedPrice, live.price)))
        val fetchedAt = clock.now()
        val stored = cache.update { it.mergedWith(answer, SnoozeProducts.all, fetchedAt) }
        if (stored is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(STORE_LIVE_PRICE, stored.error))
        return live
    }

    companion object {
        /**
         * How long the sheet's live price may take: Play reconnecting (`BillingTimeouts.connect`, 10 s), then the query
         * (`BillingTimeouts.query`, 10 s), as the sheet allows the price it asks at open (Story 4.18 device run; was 8 s).
         */
        val LIVE_PRICE_TIMEOUT: Duration = 20.seconds
        private const val STORE_LIVE_PRICE = "store live price"
    }
}

/**
 * The Play billing country (Story 4.12 for Story 4.13's `BillingCountry` and its tax note): `getBillingConfigAsync`,
 * read by [AndroidBilling] after each connection. Null while unknown (before the first connection, or Play gave none).
 */
class PlayBillingCountry(
    private val billing: AndroidBilling,
) : BillingCountry {
    override fun countryCode(): String? = billing.billingCountry.value
}
