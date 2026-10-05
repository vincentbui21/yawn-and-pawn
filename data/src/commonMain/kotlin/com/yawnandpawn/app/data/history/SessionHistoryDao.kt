package com.yawnandpawn.app.data.history

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Access to `session_history` and `session_merge`. Only `RoomSessionHistoryRepository` uses it (a writer scan test
 * enforces it).
 */
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

    /** Records a merge (Story 2.9); the same (session, alarm, scheduled time) again is ignored, keeping the first. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMerge(merge: SessionMergeEntity)

    @Query("SELECT * FROM session_merge WHERE session_id = :sessionId ORDER BY merged_at, scheduled_at")
    abstract suspend fun mergesOf(sessionId: String): List<SessionMergeEntity>
}
