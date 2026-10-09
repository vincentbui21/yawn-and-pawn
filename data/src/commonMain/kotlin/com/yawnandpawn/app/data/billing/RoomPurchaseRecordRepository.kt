package com.yawnandpawn.app.data.billing

import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/**
 * [PurchaseRecordRepository] over `purchase_record` in `app.db` (Story 4.10). A row that is not a record (only a damaged
 * or newer file could hold one) is a `StorageFailure` for [get] and left out of [all] and [observeAll]. Storage
 * exceptions become `StorageFailure`, except in [observeAll], whose collector gets them.
 */
class RoomPurchaseRecordRepository(
    private val dao: PurchaseRecordDao,
) : PurchaseRecordRepository {
    override suspend fun get(tokenHash: String): Outcome<PurchaseRecord?, DomainError> =
        when (val row = storage { dao.get(tokenHash) }) {
            is Outcome.Failure -> {
                row
            }

            is Outcome.Success -> {
                val entity = row.value
                val record = entity?.toRecord()
                if (entity != null && record == null) {
                    Outcome.Failure(DomainError.StorageFailure("unreadable purchase record"))
                } else {
                    Outcome.Success(record)
                }
            }
        }

    override suspend fun putRecord(record: PurchaseRecord): Outcome<Unit, DomainError> =
        storage { dao.upsertRecord(PurchaseRecordEntity.of(record)) }

    override suspend fun all(): Outcome<List<PurchaseRecord>, DomainError> = storage { dao.all().mapNotNull { it.toRecord() } }

    override fun observeAll(): Flow<List<PurchaseRecord>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toRecord() } }

    // Same boundary as RoomAlarmRepository: every storage exception is a StorageFailure, cancellation propagates.
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
