package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The production [PriceCatalog] (Story 4.3): Play's prices of [productIds] (the 50 snooze products) from [source],
 * kept in [store].
 *
 * [refresh] fetches every product, then merges the result into the stored snapshot in one atomic store update
 * ([PriceCatalogSnapshot.mergedWith], fetch time from [clock]). A failed fetch or a failed write leaves the stored
 * snapshot as it was; both are logged and returned. One refresh runs at a time; a second call waits for the first and
 * then fetches again.
 */
class CachedPriceCatalog(
    private val source: ProductDetailsSource,
    private val store: PriceCacheStore,
    private val clock: Clock,
    private val logger: Logger,
    private val productIds: List<String> = SnoozeProducts.all,
) : PriceCatalog {
    private val refreshing = Mutex()

    override fun observe(): Flow<PriceCatalogSnapshot> = store.observe()

    override suspend fun refresh(): Outcome<Unit, DomainError> =
        refreshing.withLock {
            when (val fetched = source.fetch(productIds)) {
                is Outcome.Failure -> {
                    logger.log(LogEvent.OperationFailed.of(FETCH_PRICES, fetched.error))
                    fetched
                }

                is Outcome.Success -> {
                    val fetchedAt = clock.now()
                    when (val stored = store.update { it.mergedWith(fetched.value, productIds, fetchedAt) }) {
                        is Outcome.Failure -> {
                            logger.log(LogEvent.OperationFailed.of(STORE_PRICES, stored.error))
                            stored
                        }

                        is Outcome.Success -> {
                            Outcome.Success(Unit)
                        }
                    }
                }
            }
        }

    companion object {
        /** The operation logged when Play's prices could not be fetched. */
        const val FETCH_PRICES = "fetch prices"

        /** The operation logged when fetched prices could not be stored. */
        const val STORE_PRICES = "store prices"
    }
}
