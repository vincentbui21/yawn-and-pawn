package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The cached Play price of one snooze product (AD-8): [formattedPrice] is Play's own localized string, shown as is
 * before a purchase; [price] is the same amount as micros and currency, kept for intents and history; [fetchedAt] is
 * when Play returned it (wall clock).
 */
data class PriceEntry(
    val productId: String,
    val formattedPrice: String,
    val price: Money,
    val fetchedAt: Instant,
)

/** How old a cached price is (Story 4.3, [PriceCachePolicy]). */
enum class PriceFreshness {
    /** Fetched within [PriceCachePolicy.STALE_AFTER]. */
    Fresh,

    /** Older, but still shown: Play's own sheet shows the real price at purchase. */
    Stale,

    /** Older than [PriceCachePolicy.EXPIRES_AFTER]: treated as not loaded, so snooze says "Prices not loaded yet". */
    Expired,
}

/** The staleness rules of the price cache (Story 4.3; defaults taken in fast mode, the owner can change them). */
object PriceCachePolicy {
    /** A price fetched longer ago than this is [PriceFreshness.Stale]. Also the period of the daily refresh. */
    val STALE_AFTER: Duration = 24.hours

    /** A price fetched longer ago than this is [PriceFreshness.Expired]. */
    val EXPIRES_AFTER: Duration = 30.days
}

/** This entry's freshness at [now]. A fetch time after [now] (the clock was set back) counts as fresh. */
fun PriceEntry.freshnessAt(now: Instant): PriceFreshness {
    val age = now - fetchedAt
    return when {
        age <= PriceCachePolicy.STALE_AFTER -> PriceFreshness.Fresh
        age <= PriceCachePolicy.EXPIRES_AFTER -> PriceFreshness.Stale
        else -> PriceFreshness.Expired
    }
}

/** Every cached price, by product id. Immutable; a refresh produces a new snapshot. */
data class PriceCatalogSnapshot(
    val entries: Map<String, PriceEntry> = emptyMap(),
) {
    /** The cached entry of [productId], whatever its age, or null. */
    fun priceFor(productId: String): PriceEntry? = entries[productId]

    /** The entry of [productId] if it may be shown at [now] (not [PriceFreshness.Expired]), or null. */
    fun displayablePriceFor(
        productId: String,
        now: Instant,
    ): PriceEntry? = priceFor(productId)?.takeIf { it.freshnessAt(now) != PriceFreshness.Expired }

    /**
     * The snapshot after a successful fetch of [requested] returned [result] at [fetchedAt] (Story 4.3):
     * - a usable price (requested, not reported unfetched, non-blank string, more than zero) replaces its entry;
     * - a requested product without a usable price keeps its previous entry, unless that entry is in a currency the new
     *   prices do not use (the phone changed country): such entries are dropped;
     * - entries of products not [requested] are dropped.
     *
     * With no usable price at all, every requested entry is kept as it was.
     */
    fun mergedWith(
        result: ProductDetailsResult,
        requested: Collection<String>,
        fetchedAt: Instant,
    ): PriceCatalogSnapshot {
        val wanted = requested.toSet()
        val fresh =
            result.prices
                .filter { it.productId in wanted && it.productId !in result.unfetched && it.isUsable() }
                .associate { it.productId to PriceEntry(it.productId, it.formattedPrice, it.price, fetchedAt) }
        val currencies = fresh.values.mapTo(HashSet()) { it.price.currency }
        val kept =
            entries.filter { (id, entry) ->
                id in wanted && id !in fresh && (currencies.isEmpty() || entry.price.currency in currencies)
            }
        return PriceCatalogSnapshot(kept + fresh)
    }

    companion object {
        val EMPTY = PriceCatalogSnapshot()
    }
}

/** One product's price as Play returned it (`ProductDetails.oneTimePurchaseOfferDetails`, Story 4.12). */
data class ProductPrice(
    val productId: String,
    val formattedPrice: String,
    val price: Money,
)

/** What Play returned for one query: the [prices] it had, and the ids it reported as [unfetched]. */
data class ProductDetailsResult(
    val prices: List<ProductPrice>,
    val unfetched: Set<String> = emptySet(),
)

private fun ProductPrice.isUsable(): Boolean = formattedPrice.isNotBlank() && price.micros > 0

/**
 * Port for Play's product details (AD-7). The Play adapter arrives in Story 4.12; until then the app binds one that
 * always fails. Returns [DomainError.ProductDetailsFailed] (or another error) instead of throwing.
 */
fun interface ProductDetailsSource {
    suspend fun fetch(productIds: List<String>): Outcome<ProductDetailsResult, DomainError>
}

/**
 * Port for the stored snapshot (Story 4.3: a device-protected DataStore of its own, kept out of backup).
 * [observe] emits the stored snapshot and every change; an unreadable store reads as [PriceCatalogSnapshot.EMPTY].
 * [update] applies [transform] to the stored snapshot and stores the result in one atomic step; on failure nothing
 * changed.
 */
interface PriceCacheStore {
    fun observe(): Flow<PriceCatalogSnapshot>

    suspend fun update(transform: (PriceCatalogSnapshot) -> PriceCatalogSnapshot): Outcome<PriceCatalogSnapshot, DomainError>
}

/**
 * The cached Play prices (AD-8, Story 4.3). [observe] is the cache, so screens render at once, offline too; [refresh]
 * asks Play again and returns when the cache is updated (or the failure; the cache then stays as it was). Nothing on
 * the wake path awaits [refresh].
 */
interface PriceCatalog {
    fun observe(): Flow<PriceCatalogSnapshot>

    suspend fun refresh(): Outcome<Unit, DomainError>
}
