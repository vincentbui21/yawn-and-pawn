package com.yawnandpawn.app.data.session

import com.yawnandpawn.app.core.billing.GrantLedgerEntry
import com.yawnandpawn.app.core.billing.GrantLedgerStore
import com.yawnandpawn.app.core.billing.LedgerStatus
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.PurchaseToken
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * [GrantLedgerStore] over `grant_ledger` in `runtime.db` (Story 4.10): [all] lists the pending rows, [get] also finds a
 * settled marker. A row with a status this build does not know is a `StorageFailure` for [get] and left out of [all].
 * Storage exceptions become `StorageFailure`; the message never holds a token.
 */
class RoomGrantLedgerStore(
    private val dao: GrantLedgerDao,
) : GrantLedgerStore {
    override suspend fun all(): Outcome<List<GrantLedgerEntry>, DomainError> = storage { dao.pending().mapNotNull { it.toEntry() } }

    override suspend fun get(token: PurchaseToken): Outcome<GrantLedgerEntry?, DomainError> =
        when (val row = storage { dao.get(token.value) }) {
            is Outcome.Failure -> {
                row
            }

            is Outcome.Success -> {
                val entity = row.value
                val entry = entity?.toEntry()
                if (entity != null && entry == null) {
                    Outcome.Failure(DomainError.StorageFailure("unreadable grant ledger row"))
                } else {
                    Outcome.Success(entry)
                }
            }
        }

    override suspend fun markConsumed(token: PurchaseToken): Outcome<Unit, DomainError> =
        storage { dao.setConsumed(token.value, GrantLedgerEntity.STATUSES.getValue(LedgerStatus.Consumed)) }

    override suspend fun markSettled(
        token: PurchaseToken,
        at: Instant,
    ): Outcome<Unit, DomainError> = storage { dao.setSettled(token.value, at.toEpochMilliseconds()) }

    override suspend fun purgeSettledBefore(instant: Instant): Outcome<Int, DomainError> =
        storage { dao.deleteSettledBefore(instant.toEpochMilliseconds()) }

    // Same boundary as RoomActiveSessionStore, but the cause is the exception type only: SQLite messages can quote values.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> storage(block: suspend () -> T): Outcome<T, DomainError> =
        try {
            Outcome.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e::class.simpleName ?: "storage error"))
        }
}
