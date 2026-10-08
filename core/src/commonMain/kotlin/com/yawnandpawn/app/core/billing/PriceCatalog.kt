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

/**
 * The staleness rules of the price cache (Story 4.3; defaults taken in fast mode, the owner can change them).
 *
 * The cache is for display only: the price charged and recorded is Play's live one, queried again before a purchase
 * (Stories 4.8, 4.12, 4.13), never a cached entry.
 */
object PriceCachePolicy {
    /** A price fetched longer ago than this is [PriceFreshness.Stale]. Also the period of the daily refresh. */
    val STALE_AFTER: Duration = 24.hours

    /** A price fetched longer ago than this is [PriceFreshness.Expired]. */
    val EXPIRES_AFTER: Duration = 30.days

    /**
     * A fetch time up to this far after "now" is clock jitter and counts as fresh; further in the future (the clock was
     * set back, or was wrong at the fetch) the entry is [PriceFreshness.Stale], and beyond [EXPIRES_AFTER] in the future
     * [PriceFreshness.Expired], so a clock glitch never keeps a price fresh for ever.
     */
    val FUTURE_TOLERANCE: Duration = 1.hours
}

/**
 * This entry's freshness at [now]. A fetch time in the future counts as fresh only within
 * [PriceCachePolicy.FUTURE_TOLERANCE]; further ahead it is stale, and expired beyond [PriceCachePolicy.EXPIRES_AFTER].
 */
fun PriceEntry.freshnessAt(now: Instant): PriceFreshness {
    val age = now - fetchedAt
    val ahead = -age
    return when {
        ahead > PriceCachePolicy.EXPIRES_AFTER -> PriceFreshness.Expired
        ahead > PriceCachePolicy.FUTURE_TOLERANCE -> PriceFreshness.Stale
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
     * - a usable price (requested, not reported unfetched, non-blank string, more than zero) in the answer's currency
     *   ([answerCurrency]) replaces its entry; usable prices in another currency are dropped, so a snapshot never mixes
     *   currencies;
     * - a requested product without a new price keeps its previous entry, unless that entry is in another currency than
     *   the answer's (the phone changed country): such entries are dropped;
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
        val usable = result.usablePrices(wanted)
        val currency = answerCurrency(usable) ?: return PriceCatalogSnapshot(entries.filterKeys { it in wanted })
        val fresh =
            usable
                .filter { it.price.currency == currency }
                .associate { it.productId to PriceEntry(it.productId, it.formattedPrice, it.price, fetchedAt) }
        val kept = entries.filter { (id, entry) -> id in wanted && id !in fresh && entry.price.currency == currency }
        return PriceCatalogSnapshot(kept + fresh)
    }

    /**
     * The one currency a Play answer's [usable] prices are kept in (null when there are none): the currency of most of
     * them. Play answers in one currency; should one mix currencies, the majority wins, a tie keeps this snapshot's own
     * (most common) currency when it is among the tied ones, and otherwise the first tied currency in answer order.
     */
    fun answerCurrency(usable: List<ProductPrice>): String? {
        val counts = usable.groupingBy { it.price.currency }.eachCount()
        val top = counts.values.maxOrNull() ?: return null
        val tied = counts.filterValues { it == top }.keys
        val own =
            entries.values
                .groupingBy { it.price.currency }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
        return own?.takeIf { it in tied } ?: usable.first { it.price.currency in tied }.price.currency
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

/** The prices of this answer that may be cached: requested, not reported unfetched, a non-blank string, more than zero. */
fun ProductDetailsResult.usablePrices(requested: Set<String>): List<ProductPrice> =
    prices.filter { it.productId in requested && it.productId !in unfetched && it.isUsable() }

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
