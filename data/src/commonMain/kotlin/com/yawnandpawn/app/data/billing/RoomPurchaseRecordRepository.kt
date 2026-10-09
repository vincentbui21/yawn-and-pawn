package com.yawnandpawn.app.data.billing

import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.billing.PurchaseRecordsRead
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/**
 * [PurchaseRecordRepository] over `purchase_record` in `app.db` (Story 4.10). A row that is not a record (only a damaged
 * or newer file could hold one) is a `StorageFailure` for [get]; [all] and [observeAll] leave it out, never in silence:
 * both log how many rows were left out (a count only, never a token hash), and [observeAll] reports the count so the
 * screen can say so. Storage exceptions become `StorageFailure`, except in [observeAll], whose collector gets them.
 */
class RoomPurchaseRecordRepository(
    private val dao: PurchaseRecordDao,
    private val logger: Logger,
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

    override suspend fun all(): Outcome<List<PurchaseRecord>, DomainError> = storage { readable(dao.all()).records }

    override fun observeAll(): Flow<PurchaseRecordsRead> = dao.observeAll().map(::readable)

    /** The records of [rows], with the count of rows that are not records, which is logged when there are any. */
    private fun readable(rows: List<PurchaseRecordEntity>): PurchaseRecordsRead {
        val records = rows.mapNotNull { it.toRecord() }
        val unreadable = rows.size - records.size
        if (unreadable > 0) logger.log(LogEvent.OperationFailed(UNREADABLE_OPERATION, "unreadable rows left out: $unreadable"))
        return PurchaseRecordsRead(records, unreadable)
    }

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

    private companion object {
        const val UNREADABLE_OPERATION = "read purchase records"
    }
}
