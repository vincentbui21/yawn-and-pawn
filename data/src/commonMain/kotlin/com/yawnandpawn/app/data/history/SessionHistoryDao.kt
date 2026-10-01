package com.yawnandpawn.app.data.history

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Access to `session_history`. Only `RoomSessionHistoryRepository` uses it (a writer scan test enforces it). */
@Dao
abstract class SessionHistoryDao {
    /**
     * Inserts [row] or replaces the row with its session id. Room's `@Upsert` is safe here, unlike for `alarm`: the
     * primary key is the table's only uniqueness constraint, so no other clash can be swallowed.
     */
    @Upsert
    abstract suspend fun upsertRow(row: SessionHistoryEntity)

    @Query("SELECT * FROM session_history WHERE session_id = :sessionId")
    abstract suspend fun findById(sessionId: String): SessionHistoryEntity?

    @Query("SELECT COUNT(*) FROM session_history")
    abstract suspend fun count(): Int

    /** The row with [outcome] that ended last (Home's missed note, Story 1.16); emits again after every change. */
    @Query("SELECT * FROM session_history WHERE outcome = :outcome ORDER BY ended_at DESC LIMIT 1")
    abstract fun observeLatestWithOutcome(outcome: String): Flow<SessionHistoryEntity?>
}
