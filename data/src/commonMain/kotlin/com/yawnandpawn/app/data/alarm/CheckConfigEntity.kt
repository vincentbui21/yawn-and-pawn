package com.yawnandpawn.app.data.alarm

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Relation

/** An `alarm` row with its `check_config` rows, read in one query (Home's cards, Story 3.5 review). */
data class AlarmWithCheckRows(
    @Embedded
    val alarm: AlarmEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["alarm_id"])
    val checks: List<CheckConfigEntity>,
)

/**
 * Row of the `check_config` table in `app.db` (schema v6, Story 3.5; the code columns v8, Story 3.10): one check of an
 * alarm. Deleting the alarm deletes
 * its rows (foreign key, cascade). [type] is the stable core `CheckType.id`, [difficulty] the `Difficulty` name.
 */
@Entity(
    tableName = "check_config",
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
data class CheckConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "alarm_id")
    val alarmId: String,
    @ColumnInfo(name = "position")
    val position: Int,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "difficulty")
    val difficulty: String,
    @ColumnInfo(name = "count")
    val count: Int,
    /** Epoch milliseconds. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    /** Epoch milliseconds. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    /** A QR/Barcode entry's code format (`CodeFormat.storedName`, schema v8, Story 3.10); null for every other entry. */
    @ColumnInfo(name = "code_format")
    val codeFormat: String? = null,
    /** The SHA-256 fingerprint of the trimmed code value, never the raw value (`CheckCodeColumns`); null without a code. */
    @ColumnInfo(name = "code_value")
    val codeValue: String? = null,
    /** When the code was last registered, epoch milliseconds (Story 3.13's re-register banner); null without a code. */
    @ColumnInfo(name = "code_registered_at")
    val codeRegisteredAt: Long? = null,
)
