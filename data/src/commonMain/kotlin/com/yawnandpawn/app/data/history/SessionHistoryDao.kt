package com.yawnandpawn.app.data.history

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Access to `session_history` and `session_merge`. Only `RoomSessionHistoryRepository` uses it (a writer scan test
 * enforces it).
 */
@Suppress("TooManyFunctions") // One query per read or write of the two tables; each is a single SQL statement.
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

    /** Every history row (Story 6.4's one read for the celebration snapshot). */
    @Query("SELECT * FROM session_history")
    abstract suspend fun allRows(): List<SessionHistoryEntity>

    @Query("SELECT COUNT(*) FROM session_history")
    abstract suspend fun count(): Int

    /** The row with [outcome] that ended last (Home's missed note, Story 1.16); emits again after every change. */
    @Query("SELECT * FROM session_history WHERE outcome = :outcome ORDER BY ended_at DESC LIMIT 1")
    abstract fun observeLatestWithOutcome(outcome: String): Flow<SessionHistoryEntity?>

    /**
     * The rows whose session used the fallback check for a known check (Story 3.13's re-register banner), newest first;
     * emits again after every change. Fallbacks are rare (at most a few a week), so the set stays small.
     */
    @Query("SELECT * FROM session_history WHERE fallback_used = 1 AND fallback_from IS NOT NULL ORDER BY first_ring_at DESC")
    abstract fun observeFallbacks(): Flow<List<SessionHistoryEntity>>

    /**
     * Every row, oldest scheduled time first, then by session id (Progress and Home, Epic 6); emits again after every
     * change. About one row a day, so three years stay near 1,100 rows.
     */
    @Query("SELECT * FROM session_history ORDER BY scheduled_at, session_id")
    abstract fun observeAll(): Flow<List<SessionHistoryEntity>>

    /** Records a merge (Story 2.9); the same (session, alarm, scheduled time) again is ignored, keeping the first. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMerge(merge: SessionMergeEntity)

    @Query("SELECT * FROM session_merge WHERE session_id = :sessionId ORDER BY merged_at, scheduled_at, alarm_id")
    abstract suspend fun mergesOf(sessionId: String): List<SessionMergeEntity>

    /** "Don't skip" (Story 7.11): the Skipped row of [alarmId]'s occurrence at [scheduledAt], if any. */
    @Query("DELETE FROM session_history WHERE alarm_id = :alarmId AND scheduled_at = :scheduledAt AND outcome = 'Skipped'")
    abstract suspend fun deleteSkippedRow(
        alarmId: String,
        scheduledAt: Long,
    )

    @Query("DELETE FROM session_history")
    abstract suspend fun deleteAllRows()

    @Query("DELETE FROM session_merge")
    abstract suspend fun deleteAllMerges()

    /** "Delete all data" (Story 5.9): every history row and every merge, in one transaction. */
    @Transaction
    open suspend fun deleteRowsAndMerges() {
        deleteAllRows()
        deleteAllMerges()
    }
}
