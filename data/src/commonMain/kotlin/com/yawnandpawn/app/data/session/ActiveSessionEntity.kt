package com.yawnandpawn.app.data.session

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * The one row of `active_session` in `runtime.db`: the last committed session state as `SessionJson` text.
 * [updatedAt] is the wall time of the commit in epoch millis, for diagnostics only.
 */
@Entity(tableName = "active_session")
data class ActiveSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "state_json")
    val stateJson: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
