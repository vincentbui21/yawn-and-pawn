package com.yawnandpawn.app.data.history

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One row of `session_history` in `app.db` (schema v3, Story 1.13): one wake session, keyed by its id (AD-18). Instants
 * are epoch millis. [checkTypes] is the comma-separated list of stable check type names (empty for none), [outcome]
 * the stable outcome name (null while the session runs). No paid amounts: those come from purchase records keyed by
 * the session id (AD-7, AD-8). No foreign key to `alarm`: history outlives a deleted alarm. Indexed by [scheduledAt],
 * which Progress (Epic 6) queries by date.
 */
@Entity(tableName = "session_history", indices = [Index(value = ["scheduled_at"])])
data class SessionHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "alarm_id")
    val alarmId: String,
    @ColumnInfo(name = "scheduled_at")
    val scheduledAt: Long,
    @ColumnInfo(name = "first_ring_at")
    val firstRingAt: Long,
    @ColumnInfo(name = "ended_at")
    val endedAt: Long?,
    @ColumnInfo(name = "snooze_count")
    val snoozeCount: Int,
    @ColumnInfo(name = "check_types")
    val checkTypes: String,
    @ColumnInfo(name = "time_to_complete_ms")
    val timeToCompleteMs: Long?,
    @ColumnInfo(name = "fallback_used")
    val fallbackUsed: Boolean,
    @ColumnInfo(name = "direct_boot")
    val directBoot: Boolean,
    @ColumnInfo(name = "outcome")
    val outcome: String?,
    /** The `CheckType.id` the fallback check replaced (schema v6, Story 3.9); null without a fallback. */
    @ColumnInfo(name = "fallback_from")
    val fallbackFrom: String?,
)
