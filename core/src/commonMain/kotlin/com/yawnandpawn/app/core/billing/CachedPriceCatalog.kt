package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The production [PriceCatalog] (Story 4.3): Play's prices of [productIds] (the 50 snooze products) from [source],
 * kept in [store].
 *
 * [refresh] fetches every product, then merges the result into the stored snapshot in one atomic store update
 * ([PriceCatalogSnapshot.mergedWith], fetch time from [clock]). A failed fetch or a failed write leaves the stored
 * snapshot as it was; both are logged and returned. A fetch that takes longer than [fetchTimeout] (Play's connection
 * never answering) is abandoned as a transient [DomainError.ProductDetailsFailed], so it never holds later refreshes
 * up. One refresh runs at a time; a second call waits for the first and then fetches again. An answer that mixes
 * currencies is logged; only one currency is kept.
 */
class CachedPriceCatalog(
    private val source: ProductDetailsSource,
    private val store: PriceCacheStore,
    private val clock: Clock,
    private val logger: Logger,
    private val productIds: List<String> = SnoozeProducts.all,
    private val fetchTimeout: Duration = FETCH_TIMEOUT,
) : PriceCatalog {
    private val refreshing = Mutex()

    override fun observe(): Flow<PriceCatalogSnapshot> = store.observe()

    override suspend fun refresh(): Outcome<Unit, DomainError> =
        refreshing.withLock {
            when (val fetched = fetch()) {
                is Outcome.Failure -> {
                    logger.log(LogEvent.OperationFailed.of(FETCH_PRICES, fetched.error))
                    fetched
                }

                is Outcome.Success -> {
                    store(fetched.value)
                }
            }
        }

    private suspend fun fetch(): Outcome<ProductDetailsResult, DomainError> =
        withTimeoutOrNull(fetchTimeout) { source.fetch(productIds) }
            ?: Outcome.Failure(DomainError.ProductDetailsFailed(transient = true, cause = "no answer within $fetchTimeout"))

    private suspend fun store(result: ProductDetailsResult): Outcome<Unit, DomainError> {
        val currencies =
            result
                .usablePrices(productIds.toSet())
                .map { it.price.currency }
                .distinct()
                .sorted()
        if (currencies.size > 1) logger.log(LogEvent.OperationFailed(FETCH_PRICES, "mixed currencies ${currencies.joinToString()}"))
        val fetchedAt = clock.now()
        return when (val stored = store.update { it.mergedWith(result, productIds, fetchedAt) }) {
            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of(STORE_PRICES, stored.error))
                stored
            }

            is Outcome.Success -> {
                Outcome.Success(Unit)
            }
        }
    }

    companion object {
        /** The operation logged when Play's prices could not be fetched. */
        const val FETCH_PRICES = "fetch prices"

        /** The operation logged when fetched prices could not be stored. */
        const val STORE_PRICES = "store prices"

        /** How long one fetch may take before it is abandoned. */
        val FETCH_TIMEOUT: Duration = 30.seconds
    }
}
