package com.yawnandpawn.app.data.config

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.yawnandpawn.app.data.alarm.AlarmEntity
import kotlinx.coroutines.flow.Flow

/**
 * Row of `pending_change` in `app.db` (schema v9, Story 4.4): a weakening change of an alarm's locked [field]
 * (`LockedField` name) that takes effect after the occurrence ([effectiveAfterAlarmId], [effectiveAfterScheduledAt]).
 * [valueJson] is `PendingChangeJson`. One row per alarm and field; deleting the alarm deletes its rows (cascade).
 */
@Entity(
    tableName = "pending_change",
    primaryKeys = ["alarm_id", "field"],
    foreignKeys = [
        ForeignKey(
            entity = AlarmEntity::class,
            parentColumns = ["id"],
            childColumns = ["alarm_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["alarm_id"])],
)
data class PendingChangeEntity(
    @ColumnInfo(name = "alarm_id")
    val alarmId: String,
    @ColumnInfo(name = "field")
    val field: String,
    @ColumnInfo(name = "value_json")
    val valueJson: String,
    @ColumnInfo(name = "effective_after_alarm_id")
    val effectiveAfterAlarmId: String,
    /** Epoch milliseconds. */
    @ColumnInfo(name = "effective_after_scheduled_at")
    val effectiveAfterScheduledAt: Long,
)

/** Access to `pending_change`. Only `RoomPendingChangeRepository` uses it. */
@Dao
abstract class PendingChangeDao {
    @Query("SELECT * FROM pending_change ORDER BY alarm_id ASC, field ASC")
    abstract fun observeAll(): Flow<List<PendingChangeEntity>>

    @Query("SELECT * FROM pending_change ORDER BY alarm_id ASC, field ASC")
    abstract suspend fun all(): List<PendingChangeEntity>

    @Query("SELECT * FROM pending_change WHERE alarm_id = :alarmId ORDER BY field ASC")
    abstract suspend fun forAlarm(alarmId: String): List<PendingChangeEntity>

    /** Replaces the row of the same alarm and field (no other table refers to it). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(change: PendingChangeEntity)

    @Query("DELETE FROM pending_change WHERE alarm_id = :alarmId AND field = :field")
    abstract suspend fun remove(
        alarmId: String,
        field: String,
    )
}

/**
 * Row of `commitment_event` in `app.db` (schema v9, Story 4.4): the user turned an alarm off ([action] `Disabled`) or
 * deleted it (`Deleted`) at [at] inside the lock window of its occurrence [occurrenceAt]. No foreign key: the event
 * outlives a deleted alarm (Day detail, Epic 6).
 */
@Entity(tableName = "commitment_event", primaryKeys = ["id"])
data class CommitmentEventEntity(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "alarm_id")
    val alarmId: String,
    /** Epoch milliseconds. */
    @ColumnInfo(name = "occurrence_at")
    val occurrenceAt: Long,
    @ColumnInfo(name = "action")
    val action: String,
    /** Epoch milliseconds. */
    @ColumnInfo(name = "at")
    val at: Long,
)

/** Access to `commitment_event`. Only `RoomCommitmentEventRepository` uses it. */
@Dao
abstract class CommitmentEventDao {
    /** Fails on a duplicate id (default conflict strategy ABORT): an event is never overwritten. */
    @Insert
    abstract suspend fun insert(event: CommitmentEventEntity)

    @Query("SELECT * FROM commitment_event ORDER BY at ASC, id ASC")
    abstract suspend fun all(): List<CommitmentEventEntity>
}
