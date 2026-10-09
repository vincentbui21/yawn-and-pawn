package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceCacheStore
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.billing.PriceEntry
import com.yawnandpawn.app.core.billing.ProductDetailsResult
import com.yawnandpawn.app.core.billing.ProductDetailsSource
import com.yawnandpawn.app.core.billing.ProductPrice
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundWork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

/**
 * Play's price of USD tier [tier] in [currency]: [tier] whole units, shown as "{currency} {tier}.00" (a fixed,
 * locale-free form, like [FakeMoneyFormatter]).
 */
fun aProductPrice(
    tier: Int,
    currency: String = "USD",
): ProductPrice = ProductPrice(SnoozeProducts.idOf(tier), "$currency $tier.00", Money.of(tier, currency))

/** [aProductPrice] of each tier in [tiers]. */
fun productPrices(
    tiers: IntRange = 1..SnoozeProducts.all.size,
    currency: String = "USD",
): List<ProductPrice> = tiers.map { aProductPrice(it, currency) }

/** The cached entry of [aProductPrice] fetched at [fetchedAt]. */
fun aPriceEntry(
    tier: Int,
    fetchedAt: Instant = DEFAULT_FAKE_INSTANT,
    currency: String = "USD",
): PriceEntry = aProductPrice(tier, currency).let { PriceEntry(it.productId, it.formattedPrice, it.price, fetchedAt) }

/** A snapshot with [aPriceEntry] of each tier in [tiers]. */
fun aPriceSnapshot(
    tiers: IntRange = 1..SnoozeProducts.all.size,
    fetchedAt: Instant = DEFAULT_FAKE_INSTANT,
    currency: String = "USD",
): PriceCatalogSnapshot = PriceCatalogSnapshot(tiers.map { aPriceEntry(it, fetchedAt, currency) }.associateBy { it.productId })

/**
 * [ProductDetailsSource] under test control. [result] is what [fetch] returns: every product at its USD tier by
 * default; a test sets a partial result or a failure. While [gate] is set, [fetch] waits for it to complete (a slow
 * Play). Every request is kept in [requests]; [completed] counts the fetches that returned.
 */
class FakeProductDetailsSource(
    var result: Outcome<ProductDetailsResult, DomainError> = Outcome.Success(ProductDetailsResult(productPrices())),
) : ProductDetailsSource {
    private val recorded = mutableListOf<List<String>>()

    /** When set, [fetch] suspends until it completes. */
    var gate: CompletableDeferred<Unit>? = null

    var completed = 0
        private set

    val requests: List<List<String>>
        get() = recorded.toList()

    /** Returns [prices] next, with [unfetched] ids reported as unfetched (a partial Play answer). */
    fun succeedWith(
        prices: List<ProductPrice>,
        unfetched: Set<String> = emptySet(),
    ) {
        result = Outcome.Success(ProductDetailsResult(prices, unfetched))
    }

    /** Fails next with [error] (offline by default). */
    fun failWith(error: DomainError = DomainError.ProductDetailsFailed(transient = true, cause = "offline")) {
        result = Outcome.Failure(error)
    }

    override suspend fun fetch(productIds: List<String>): Outcome<ProductDetailsResult, DomainError> {
        recorded += productIds
        gate?.await()
        completed++
        return result
    }
}

/** [PriceCacheStore] in memory. Set [failure] to make [update] fail without changing anything. */
class FakePriceCacheStore(
    initial: PriceCatalogSnapshot = PriceCatalogSnapshot.EMPTY,
) : PriceCacheStore {
    private val state = MutableStateFlow(initial)

    var failure: DomainError? = null

    val snapshot: PriceCatalogSnapshot
        get() = state.value

    override fun observe(): Flow<PriceCatalogSnapshot> = state

    override suspend fun update(transform: (PriceCatalogSnapshot) -> PriceCatalogSnapshot): Outcome<PriceCatalogSnapshot, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        state.value = transform(state.value)
        return Outcome.Success(state.value)
    }
}

/**
 * [PriceCatalog] under test control: [observe] emits [snapshot] and its changes; [refresh] counts the calls in
 * [refreshes] and returns [refreshResult], applying [afterRefresh] to the snapshot on success.
 */
class FakePriceCatalog(
    initial: PriceCatalogSnapshot = PriceCatalogSnapshot.EMPTY,
) : PriceCatalog {
    private val state = MutableStateFlow(initial)

    var refreshResult: Outcome<Unit, DomainError> = Outcome.Success(Unit)

    /** The snapshot a successful [refresh] leaves; null keeps the current one. */
    var afterRefresh: PriceCatalogSnapshot? = null

    var refreshes = 0
        private set

    var snapshot: PriceCatalogSnapshot
        get() = state.value
        set(value) {
            state.value = value
        }

    override fun observe(): Flow<PriceCatalogSnapshot> = state

    override suspend fun refresh(): Outcome<Unit, DomainError> {
        refreshes++
        if (refreshResult is Outcome.Success) afterRefresh?.let { state.value = it }
        return refreshResult
    }
}

/** [BackgroundWork] that keeps every enqueued job in [enqueued]; set [failure] to refuse them. */
class FakeBackgroundWork : BackgroundWork {
    private val recorded = mutableListOf<BackgroundJob>()

    var failure: DomainError? = null

    val enqueued: List<BackgroundJob>
        get() = recorded.toList()

    override fun enqueue(job: BackgroundJob): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        recorded += job
        return Outcome.Success(Unit)
    }
}
