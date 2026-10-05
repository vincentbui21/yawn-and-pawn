package com.yawnandpawn.app.data.history

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import com.yawnandpawn.app.core.history.SessionMergeRow
import kotlin.time.Instant

/**
 * One row of `session_merge` in `app.db` (schema v4, Story 2.9): an alarm occurrence that joined a running session
 * (FR-SES-7). Keyed by the session, the alarm and the occurrence's scheduled time, so one merge is one row however
 * often it is recorded. Instants are epoch millis. No foreign keys: history outlives a deleted alarm, and the session
 * row may be written after its merges.
 */
@Entity(tableName = "session_merge", primaryKeys = ["session_id", "alarm_id", "scheduled_at"])
data class SessionMergeEntity(
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "alarm_id")
    val alarmId: String,
    @ColumnInfo(name = "scheduled_at")
    val scheduledAt: Long,
    @ColumnInfo(name = "merged_at")
    val mergedAt: Long,
)

internal fun SessionMergeRow.toEntity(): SessionMergeEntity =
    SessionMergeEntity(sessionId, alarmId, scheduledAt.toEpochMilliseconds(), mergedAt.toEpochMilliseconds())

internal fun SessionMergeEntity.toRow(): SessionMergeRow =
    SessionMergeRow(sessionId, alarmId, Instant.fromEpochMilliseconds(scheduledAt), Instant.fromEpochMilliseconds(mergedAt))
