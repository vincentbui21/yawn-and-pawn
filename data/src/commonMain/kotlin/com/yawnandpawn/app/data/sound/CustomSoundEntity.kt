package com.yawnandpawn.app.data.sound

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.yawnandpawn.app.core.sound.CustomSound
import kotlin.time.Instant

/**
 * One row of `custom_sound` in `app.db` (Story 7.4): a sound file the user imported, backed up with the alarms. The
 * audio itself is not here and never backed up: it is the credential-protected copy `sounds/[fileName]`. [createdAt]
 * is wall time in epoch millis. Whether the copy is there is not stored; the repository checks it.
 */
@Entity(tableName = "custom_sound")
data class CustomSoundEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    /** The stored sound, with [missing] from its file. */
    fun toCustomSound(missing: Boolean): CustomSound =
        CustomSound(
            id = id,
            displayName = displayName,
            fileName = fileName,
            durationMs = durationMs,
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            missing = missing,
        )

    companion object {
        fun of(sound: CustomSound): CustomSoundEntity =
            CustomSoundEntity(
                id = sound.id,
                displayName = sound.displayName,
                fileName = sound.fileName,
                durationMs = sound.durationMs,
                createdAt = sound.createdAt.toEpochMilliseconds(),
            )
    }
}
