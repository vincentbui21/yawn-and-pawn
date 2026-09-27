package com.yawnandpawn.app.data.alarm

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Row of the `alarm` table in `app.db` (schema v1). Kept separate from the core `Alarm` so storage types
 * (bitmask, epoch millis, nanosecond of day) never leak into `:core`; see [toEntity] / [toAlarm].
 */
@Entity(
    tableName = "alarm",
    indices = [Index(value = ["request_code"], unique = true)],
)
data class AlarmEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    /** `LocalTime.toNanosecondOfDay()`: lossless and sorts by time of day. */
    @ColumnInfo(name = "time_nano_of_day")
    val timeNanoOfDay: Long,
    /** Bit `isoDayNumber - 1` set for each repeat day (Monday = bit 0); 0 = one-time. */
    @ColumnInfo(name = "repeat_days")
    val repeatDays: Int,
    @ColumnInfo(name = "label")
    val label: String?,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "sound_ref")
    val soundRef: String,
    @ColumnInfo(name = "volume_percent")
    val volumePercent: Int,
    @ColumnInfo(name = "gradual_volume")
    val gradualVolume: Boolean,
    @ColumnInfo(name = "ramp_start_percent")
    val rampStartPercent: Int,
    @ColumnInfo(name = "vibration")
    val vibration: Boolean,
    @ColumnInfo(name = "snooze_length_minutes")
    val snoozeLengthMinutes: Int,
    @ColumnInfo(name = "grace_seconds")
    val graceSeconds: Int,
    @ColumnInfo(name = "request_code")
    val requestCode: Int,
    /** Epoch milliseconds. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    /** Epoch milliseconds. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
