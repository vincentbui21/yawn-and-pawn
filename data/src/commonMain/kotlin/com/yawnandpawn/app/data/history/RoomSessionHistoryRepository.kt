package com.yawnandpawn.app.data.history

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.reset.SessionHistoryEraser
import com.yawnandpawn.app.core.stats.FallbackHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SessionHistoryRepository] over `session_history` in `app.db`. The only user of [SessionHistoryDao]'s writes; core's
 * `SessionRecorder` is its only caller of [upsert] (AD-18). Storage exceptions become `StorageFailure`. Also Home's
 * [FallbackHistory] (Story 3.13), a read only, and the history part of "Delete all data" ([SessionHistoryEraser], Story
 * 5.9), so the history keeps one adapter that writes it.
 */
class RoomSessionHistoryRepository(
    private val dao: SessionHistoryDao,
) : SessionHistoryRepository,
    FallbackHistory,
    SessionHistoryEraser {
    override suspend fun upsert(row: SessionHistoryRow): Outcome<Unit, DomainError> = storage { dao.upsertRow(row.toEntity()) }

    // Row mapping runs inside storage { } too: an unreadable row (an unknown outcome) is a StorageFailure.
    override suspend fun find(sessionId: String): Outcome<SessionHistoryRow?, DomainError> = storage { dao.findById(sessionId)?.toRow() }

    // A read only. An unreadable row (or a failing database) throws into the flow; Home catches it.
    override fun observeLatestMissed(): Flow<SessionHistoryRow?> =
        dao.observeLatestWithOutcome(SessionOutcome.Missed.storedName()).map { it?.toRow() }

    // A read only, like observeLatestMissed: an unreadable row throws into the flow and Home catches it.
    override fun observeFallbacks(): Flow<List<SessionHistoryRow>> = dao.observeFallbacks().map { rows -> rows.map { it.toRow() } }

    // A read only, like observeFallbacks (Progress and Home, Epic 6): an unreadable row throws into the flow and the
    // collector catches it.
    override fun observeAll(): Flow<List<SessionHistoryRow>> = dao.observeAll().map { rows -> rows.map { it.toRow() } }

    // Story 2.9: insert or ignore, so a replayed merge leaves one row. SessionRecorder is the only caller.
    override suspend fun recordMerge(merge: SessionMergeRow): Outcome<Unit, DomainError> = storage { dao.insertMerge(merge.toEntity()) }

    // Story 5.9: every history row and merge, in one transaction; called only by core's DeleteAllData.
    override suspend fun deleteAllHistory(): Outcome<Unit, DomainError> = storage { dao.deleteRowsAndMerges() }

    override suspend fun merges(sessionId: String): Outcome<List<SessionMergeRow>, DomainError> =
        storage { dao.mergesOf(sessionId).map { it.toRow() } }

    // Story 6.4: a read only; an unreadable row is a StorageFailure, like find.
    override suspend fun all(): Outcome<List<SessionHistoryRow>, DomainError> = storage { dao.allRows().map { it.toRow() } }

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
