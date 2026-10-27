package com.yawnandpawn.app.data.recording

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.yawnandpawn.app.core.recording.Recording
import kotlin.time.Instant

/**
 * One row of `recording` in `app.db` (Story 7.1): a motivation message the user recorded, backed up with the alarms.
 * The audio itself is not here and never backed up: it is the credential-protected file `recordings/[fileName]`.
 * [createdAt] is wall time in epoch millis. Whether the file is there is not stored; the repository checks it.
 */
@Entity(tableName = "recording")
data class RecordingEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    /** The stored recording, with [missing] from its file. */
    fun toRecording(missing: Boolean): Recording =
        Recording(
            id = id,
            name = name,
            durationMs = durationMs,
            fileName = fileName,
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            missing = missing,
        )

    companion object {
        fun of(recording: Recording): RecordingEntity =
            RecordingEntity(
                id = recording.id,
                name = recording.name,
                durationMs = recording.durationMs,
                fileName = recording.fileName,
                createdAt = recording.createdAt.toEpochMilliseconds(),
            )
    }
}
