package com.yawnandpawn.app.data.alarm

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

/** Access to the `alarm` table. Only `RoomAlarmRepository` uses it. */
@Dao
abstract class AlarmDao {
    /** Same order as the core `AlarmListOrder`: time of day, creation time, id. */
    @Query("SELECT * FROM alarm ORDER BY time_nano_of_day ASC, created_at ASC, id ASC")
    abstract fun observeAll(): Flow<List<AlarmEntity>>

    /** One-shot read of [observeAll]'s query, same order. */
    @Query("SELECT * FROM alarm ORDER BY time_nano_of_day ASC, created_at ASC, id ASC")
    abstract suspend fun listAll(): List<AlarmEntity>

    @Query("SELECT * FROM alarm WHERE id = :id")
    abstract suspend fun get(id: String): AlarmEntity?

    /** Fails with a constraint error on a duplicate id or request code (default conflict strategy ABORT). */
    @Insert
    abstract suspend fun insert(alarm: AlarmEntity)

    /** Rows changed (0 when the id is new); fails with a constraint error on a request code used by another row. */
    @Update
    abstract suspend fun update(alarm: AlarmEntity): Int

    /**
     * Insert or replace by id. Not Room's `@Upsert`: that one catches every uniqueness error and falls back to an
     * update by primary key, which would silently drop a new alarm whose request code clashes.
     */
    @Transaction
    open suspend fun upsert(alarm: AlarmEntity) {
        if (update(alarm) == 0) insert(alarm)
    }

    /** Rows deleted (0 when the id is unknown). */
    @Query("DELETE FROM alarm WHERE id = :id")
    abstract suspend fun delete(id: String): Int
}
