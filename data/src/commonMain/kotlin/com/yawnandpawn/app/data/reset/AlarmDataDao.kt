package com.yawnandpawn.app.data.reset

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction

/**
 * "Delete all data" (Story 5.9) in `app.db`: the alarms with their check configs, pending changes and commitment events,
 * the recordings (Story 7.1) and the imported sounds (Story 7.4; the files of both are deleted by `AndroidLocalDataEraser`
 * before this transaction).
 * Never `request_code_sequence` (a request code is never handed out twice), `purchase_record` (only `PurchaseLedger`
 * deletes records) or the history tables (only `RoomSessionHistoryRepository` writes them). Only `AndroidLocalDataEraser`
 * uses it.
 */
@Dao
abstract class AlarmDataDao {
    @Query("DELETE FROM check_config")
    abstract suspend fun deleteCheckConfigs()

    @Query("DELETE FROM pending_change")
    abstract suspend fun deletePendingChanges()

    @Query("DELETE FROM commitment_event")
    abstract suspend fun deleteCommitmentEvents()

    @Query("DELETE FROM alarm")
    abstract suspend fun deleteAlarms()

    @Query("DELETE FROM recording")
    abstract suspend fun deleteRecordings()

    @Query("DELETE FROM custom_sound")
    abstract suspend fun deleteCustomSounds()

    /** Every row of the six tables, in one transaction (children first, though the foreign keys would cascade). */
    @Transaction
    open suspend fun deleteAllAlarmData() {
        deleteCheckConfigs()
        deletePendingChanges()
        deleteCommitmentEvents()
        deleteAlarms()
        deleteRecordings()
        deleteCustomSounds()
    }
}
