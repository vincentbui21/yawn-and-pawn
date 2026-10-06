package com.yawnandpawn.app.data.alarm

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

/** Access to the `check_config` table, and the one transaction that stores an alarm with its checks. */
@Dao
abstract class CheckConfigDao {
    @Query("SELECT * FROM check_config ORDER BY alarm_id ASC, position ASC")
    abstract fun observeAll(): Flow<List<CheckConfigEntity>>

    @Query("SELECT * FROM check_config WHERE alarm_id = :alarmId ORDER BY position ASC")
    abstract suspend fun forAlarm(alarmId: String): List<CheckConfigEntity>

    @Query("DELETE FROM check_config WHERE alarm_id = :alarmId")
    abstract suspend fun deleteForAlarm(alarmId: String)

    @Insert
    abstract suspend fun insert(configs: List<CheckConfigEntity>)

    /** Rows changed (0 when the id is new); see `AlarmDao.upsert`. */
    @Update
    abstract suspend fun updateAlarm(alarm: AlarmEntity): Int

    @Insert
    abstract suspend fun insertAlarm(alarm: AlarmEntity)

    /**
     * Inserts or updates [alarm] like `AlarmDao.upsert` (never `REPLACE`, which would delete the alarm's rows through
     * the cascade), then replaces its checks with [configs], in one transaction.
     */
    @Transaction
    open suspend fun saveAlarmWithChecks(
        alarm: AlarmEntity,
        configs: List<CheckConfigEntity>,
    ) {
        if (updateAlarm(alarm) == 0) insertAlarm(alarm)
        deleteForAlarm(alarm.id)
        insert(configs)
    }
}
