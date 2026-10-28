package com.yawnandpawn.app.core.sound

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * A sound file the user imported as an alarm sound (FR-SND-6, Story 7.4). The row (`custom_sound` in `app.db`) is
 * backed up; the audio is not: it is a copy in the credential-protected `sounds/[fileName]` on this phone, so the alarm
 * never depends on the original file. [displayName] is the picked file's name without its extension (never logged).
 * [missing] is true when the copy is not there (a phone restored from a backup, or storage still locked before the
 * first unlock): the sound is listed with "File missing. Default sound will play.", cannot be previewed, and rings as
 * the default sound.
 */
data class CustomSound(
    val id: String,
    val displayName: String,
    val fileName: String,
    val durationMs: Long,
    val createdAt: Instant,
    val missing: Boolean = false,
) {
    val ref: SoundRef.File
        get() = SoundRef.File(id)

    companion object {
        /** A shorter file "can't be played" (FR-SND-6). */
        const val MIN_DURATION_MS = 1_000L

        /** A larger file is "too big": 20 MB. */
        const val MAX_SIZE_BYTES = 20L * 1024 * 1024

        /** The copy's extension when the picked name has no usable one. */
        const val FALLBACK_EXTENSION = "audio"

        private const val MAX_EXTENSION_LENGTH = 5

        /**
         * The copy's extension for the picked file [name]: its own extension in lower case when that is 1 to 5 letters
         * or digits, else [FALLBACK_EXTENSION].
         */
        fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            val extension = if (dot <= 0) "" else name.substring(dot + 1).lowercase()
            val usable =
                extension.length in 1..MAX_EXTENSION_LENGTH && extension.all { it in 'a'..'z' || it in '0'..'9' }
            return if (usable) extension else FALLBACK_EXTENSION
        }

        /** The name the sound is listed under: the picked file [name] without its extension. */
        fun displayNameOf(name: String): String {
            val dot = name.lastIndexOf('.')
            return (if (dot <= 0) name else name.substring(0, dot)).trim()
        }

        /** The copy of the sound [id] with [extension]: `{id}.{extension}`. */
        fun fileNameFor(
            id: String,
            extension: String,
        ): String = "$id.$extension"
    }
}

/** Port for the imported sounds (`app.db` and `sounds/` in production, `FakeCustomSoundRepository` in tests). */
interface CustomSoundRepository {
    /** Every imported sound, oldest first, each with [CustomSound.missing] set from its file; emits after each change. */
    fun observeAll(): Flow<List<CustomSound>>

    /** The sound [id] (with [CustomSound.missing] set), or null when there is none; a storage error is a `StorageFailure`. */
    suspend fun get(id: String): Outcome<CustomSound?, DomainError>

    /** Stores the row of [sound], whose copy is already in place (only the importer adds sounds). */
    suspend fun add(sound: CustomSound): Outcome<Unit, DomainError>
}

/** What became of a picked file ([SoundImporter.import]). */
sealed interface ImportResult {
    /** Copied in and stored: the new [sound], ready to select. */
    data class Imported(
        val sound: CustomSound,
    ) : ImportResult

    /** Not audio, corrupt, protected, or shorter than 1 s: "This file can't be played. Pick another." */
    data object NotPlayable : ImportResult

    /** Over 20 MB: "This file is too big. Pick one under 20 MB." */
    data object TooBig : ImportResult
}

/**
 * Port for importing a picked audio file (Story 7.4): checks it is decodable audio of at least 1 s and at most 20 MB,
 * copies it into credential-protected storage (a temporary file, then a rename) and stores its row. A refused file
 * leaves nothing behind and changes nothing. Only durations and error kinds are logged, never names, URIs or paths.
 */
interface SoundImporter {
    /** Imports the file behind [uri] (the document picker's answer); a storage error is a `StorageFailure`. */
    suspend fun import(uri: String): Outcome<ImportResult, DomainError>
}
