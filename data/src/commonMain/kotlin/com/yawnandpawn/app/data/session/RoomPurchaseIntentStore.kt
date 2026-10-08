package com.yawnandpawn.app.data.session

import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.billing.PurchaseIntentStore
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.PurchaseIntentId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * [PurchaseIntentStore] over `purchase_intent` in `runtime.db` (Story 4.8). A row that is not an intent (only a damaged
 * file could hold one) is a `StorageFailure` for [get] and left out of the lists. Storage exceptions become
 * `StorageFailure`.
 */
class RoomPurchaseIntentStore(
    private val dao: PurchaseIntentDao,
) : PurchaseIntentStore {
    override suspend fun get(intentId: PurchaseIntentId): Outcome<PurchaseIntent, DomainError> =
        when (val row = storage { dao.get(intentId.value) }) {
            is Outcome.Failure -> {
                row
            }

            is Outcome.Success -> {
                val entity = row.value
                val intent = entity?.toIntent()
                when {
                    entity == null -> Outcome.Failure(DomainError.NotFound(intentId.value))
                    intent == null -> Outcome.Failure(DomainError.StorageFailure("unreadable purchase intent"))
                    else -> Outcome.Success(intent)
                }
            }
        }

    override suspend fun forSession(sessionId: String): Outcome<List<PurchaseIntent>, DomainError> =
        storage { dao.forSession(sessionId).mapNotNull { it.toIntent() } }

    override suspend fun forProduct(
        sessionId: String,
        productId: String,
    ): Outcome<List<PurchaseIntent>, DomainError> = storage { dao.forProduct(sessionId, productId).mapNotNull { it.toIntent() } }

    override suspend fun purgeOlderThan(instant: Instant): Outcome<Int, DomainError> =
        storage { dao.deleteOlderThan(instant.toEpochMilliseconds()) }

    // Same boundary as RoomActiveSessionStore: every storage exception is a StorageFailure, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> storage(block: suspend () -> T): Outcome<T, DomainError> =
        try {
            Outcome.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
        }
}
