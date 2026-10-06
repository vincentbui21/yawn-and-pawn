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
    /** Every alarm with its checks, in one transaction; emits again after a change of either table. */
    @Transaction
    @Query("SELECT * FROM alarm")
    abstract fun observeAlarmsWithChecks(): Flow<List<AlarmWithCheckRows>>

    @Query("SELECT * FROM check_config WHERE alarm_id = :alarmId ORDER BY position ASC")
    abstract suspend fun forAlarm(alarmId: String): List<CheckConfigEntity>

    @Query("DELETE FROM check_config WHERE alarm_id = :alarmId")
    abstract suspend fun deleteForAlarm(alarmId: String)

    /** Rows deleted: 1, or 0 when there is no such alarm. */
    @Query("DELETE FROM alarm WHERE id = :alarmId")
    abstract suspend fun deleteAlarm(alarmId: String): Int

    /**
     * Deletes [alarmId]'s checks and then the alarm in one transaction (the foreign key would cascade anyway); returns
     * the alarm rows deleted, 0 when there is no such alarm.
     */
    @Transaction
    open suspend fun deleteAlarmWithChecks(alarmId: String): Int {
        deleteForAlarm(alarmId)
        return deleteAlarm(alarmId)
    }

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
