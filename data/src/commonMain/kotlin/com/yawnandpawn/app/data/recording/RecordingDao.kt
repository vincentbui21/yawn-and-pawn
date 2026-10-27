package com.yawnandpawn.app.data.recording

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Access to `recording` (Story 7.1). Only `RoomRecordingRepository` uses it; "Delete all data" goes through `AlarmDataDao`. */
@Dao
interface RecordingDao {
    @Query("SELECT * FROM recording WHERE id = :id")
    suspend fun get(id: String): RecordingEntity?

    /** Every recording, oldest first, again after every change of the table. */
    @Query("SELECT * FROM recording ORDER BY created_at, id")
    fun observeAll(): Flow<List<RecordingEntity>>

    /** Inserts [row], or replaces the row with the same id. */
    @Upsert
    suspend fun upsert(row: RecordingEntity)

    /** Deletes the row [id]; returns how many went (0 or 1). */
    @Query("DELETE FROM recording WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT COUNT(*) FROM recording")
    suspend fun count(): Int
}
