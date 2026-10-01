package com.yawnandpawn.app.data.history

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SessionHistoryRepository] over `session_history` in `app.db`. The only user of [SessionHistoryDao]'s writes; core's
 * `SessionRecorder` is its only caller of [upsert] (AD-18). Storage exceptions become `StorageFailure`.
 */
class RoomSessionHistoryRepository(
    private val dao: SessionHistoryDao,
) : SessionHistoryRepository {
    override suspend fun upsert(row: SessionHistoryRow): Outcome<Unit, DomainError> = storage { dao.upsertRow(row.toEntity()) }

    // Row mapping runs inside storage { } too: an unreadable row (an unknown outcome) is a StorageFailure.
    override suspend fun find(sessionId: String): Outcome<SessionHistoryRow?, DomainError> = storage { dao.findById(sessionId)?.toRow() }

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
