package com.yawnandpawn.app.android.sound

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.sound.CustomSound
import com.yawnandpawn.app.core.sound.CustomSoundRepository
import com.yawnandpawn.app.core.sound.ImportResult
import com.yawnandpawn.app.core.sound.SoundImporter
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.data.sound.SoundFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/** The picked document behind a URI: the platform half of [AndroidSoundImporter], a seam for tests. Calls may throw. */
interface PickedDocuments {
    /** The file's size in bytes as its provider reports it, or null when unknown. */
    fun size(uri: String): Long?

    /** The file's name as its provider shows it (`OpenableColumns.DISPLAY_NAME`), or null. */
    fun name(uri: String): String?

    /** The file's content; throws when it cannot be read. */
    fun open(uri: String): InputStream

    /** Takes the persistable read permission for the import; false when the provider does not offer one. */
    fun takeRead(uri: String): Boolean

    /** Gives back the permission [takeRead] took. */
    fun releaseRead(uri: String)
}

/** [PickedDocuments] over the content resolver. */
class ContentResolverDocuments(
    private val context: Context,
) : PickedDocuments {
    private val resolver: ContentResolver
        get() = context.contentResolver

    override fun size(uri: String): Long? = column(uri, OpenableColumns.SIZE) { cursor, index -> cursor.getLong(index) }

    override fun name(uri: String): String? {
        val parsed = Uri.parse(uri)
        return column(uri, OpenableColumns.DISPLAY_NAME) { cursor, index -> cursor.getString(index) }
            ?: parsed.takeIf { it.scheme == ContentResolver.SCHEME_FILE }?.lastPathSegment
    }

    override fun open(uri: String): InputStream = resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("no content")

    // A provider that does not grant a persistable permission throws SecurityException: the one-time grant then reads it.
    @Suppress("SwallowedException")
    override fun takeRead(uri: String): Boolean =
        try {
            resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            false
        }

    @Suppress("SwallowedException")
    override fun releaseRead(uri: String) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Nothing held any more: nothing to give back.
        }
    }

    private fun <T> column(
        uri: String,
        name: String,
        read: (android.database.Cursor, Int) -> T,
    ): T? =
        resolver.query(Uri.parse(uri), arrayOf(name), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(name)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) read(cursor, index) else null
        }
}

/** Reads how long a copied file plays: the decoder half of [AndroidSoundImporter], a seam for tests. */
fun interface AudioProbe {
    /** The length in ms of the audio in [file], or null when it has no audio the phone can read. Never throws. */
    fun durationMs(file: File): Long?
}

/** [AudioProbe] over `MediaMetadataRetriever`: audio (a track, or an audio MIME type) with a duration. */
class MetadataAudioProbe : AudioProbe {
    // MediaMetadataRetriever reports a file it cannot read with several runtime exception types; each means "not audio".
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun durationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val audioType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.startsWith("audio/") == true
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            duration.takeIf { hasAudio || audioType }
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Already released or never opened.
            }
        }
    }
}

/**
 * The Android [SoundImporter] (FR-SND-6, Story 7.4). For the picked [uri][import]:
 * - a provider size over 20 MB is [ImportResult.TooBig] before anything is read;
 * - the persistable read permission is taken for the import and given back once it is done;
 * - the file is copied into `sounds/import-{id}.tmp`, counting the bytes: over 20 MB the copy stops and it is TooBig;
 * - the copy must be audio of at least 1 s ([probe]), else [ImportResult.NotPlayable];
 * - it is renamed to `sounds/{id}.{ext}` (one atomic rename in the same folder) and its `custom_sound` row is stored.
 *
 * A refused file, a failure or a cancellation deletes the temporary copy (and the renamed one when its row cannot be
 * written), so nothing is left behind. A file that cannot be opened or read is NotPlayable; a storage error is a
 * `StorageFailure` holding the exception class only. Logs carry error kinds only, never the name, URI or a path.
 */
class AndroidSoundImporter(
    private val documents: PickedDocuments,
    private val probe: AudioProbe,
    private val files: SoundFiles,
    private val repository: CustomSoundRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val logger: Logger,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SoundImporter {
    // ContentResolver, the providers behind it and the file system report failures with many exception types; a
    // failure to read the picked file means "can't be played", one in the app's own storage is a StorageFailure.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun import(uri: String): Outcome<ImportResult, DomainError> =
        withContext(io) {
            val reported =
                try {
                    documents.size(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            if (reported != null && reported > CustomSound.MAX_SIZE_BYTES) return@withContext refused(ImportResult.TooBig)
            val taken =
                try {
                    documents.takeRead(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            try {
                importTaken(uri)
            } finally {
                if (taken) release(uri)
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun importTaken(uri: String): Outcome<ImportResult, DomainError> {
        val id = ids.newId()
        var copy: File? = null
        return try {
            val folder = files.folder()
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("sounds folder not created")
            files.deleteStaleImports()
            val temp = files.importFile(id).also { copy = it }
            when (val checked = copyAndCheck(uri, temp)) {
                is Checked.Refused -> refused(checked.result)
                is Checked.Playable -> store(id, nameOf(uri), temp, checked.durationMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(OPERATION, "storage: ${e::class.simpleName}"))
            Outcome.Failure(DomainError.StorageFailure("import sound: ${e::class.simpleName}"))
        } finally {
            // Gone already once renamed; a refused, failed or cancelled import leaves nothing behind.
            copy?.let(::deleteQuietly)
        }
    }

    /** Copies the picked file into [temp] and checks it is audio of 1 s to 20 MB. */
    private fun copyAndCheck(
        uri: String,
        temp: File,
    ): Checked =
        when (copyIn(uri, temp)) {
            Copied.Unreadable -> {
                Checked.Refused(ImportResult.NotPlayable)
            }

            Copied.TooBig -> {
                Checked.Refused(ImportResult.TooBig)
            }

            Copied.Done -> {
                val duration = probe.durationMs(temp)
                if (duration == null || duration < CustomSound.MIN_DURATION_MS) {
                    Checked.Refused(ImportResult.NotPlayable)
                } else {
                    Checked.Playable(duration)
                }
            }
        }

    /**
     * Renames [temp] to `{id}.{ext}` and stores its row; the renamed file is deleted again when the row is not stored,
     * also when storing it throws or is cancelled (nothing would ever sweep a copy without a row; the throwable is
     * rethrown as it is).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun store(
        id: String,
        name: String,
        temp: File,
        durationMs: Long,
    ): Outcome<ImportResult, DomainError> {
        val fileName = CustomSound.fileNameFor(id, CustomSound.extensionOf(name))
        val target = files.file(fileName) ?: throw IOException("not a file name")
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val sound =
            CustomSound(
                id = id,
                displayName = CustomSound.displayNameOf(name),
                fileName = fileName,
                durationMs = durationMs,
                createdAt = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()),
            )
        val stored =
            try {
                repository.add(sound)
            } catch (e: Throwable) {
                deleteQuietly(target)
                throw e
            }
        if (stored is Outcome.Failure) {
            deleteQuietly(target)
            logger.log(LogEvent.OperationFailed.of(OPERATION, stored.error))
        }
        return stored.map { ImportResult.Imported(sound) }
    }

    /** The picked file's name, or empty when its provider does not tell (never logged). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun nameOf(uri: String): String =
        try {
            documents.name(uri).orEmpty()
        } catch (e: Exception) {
            ""
        }

    /**
     * Copies the picked file into [copy], stopping once it is over 20 MB. A file that cannot be opened or read is
     * [Copied.Unreadable]; an error writing the copy is thrown (a storage failure).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun copyIn(
        uri: String,
        copy: File,
    ): Copied {
        val input =
            try {
                documents.open(uri)
            } catch (e: Exception) {
                return Copied.Unreadable
            }
        return input.use { source ->
            copy.outputStream().use { sink ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                var result: Copied? = null
                while (result == null) {
                    val read =
                        try {
                            source.read(buffer)
                        } catch (e: Exception) {
                            UNREADABLE
                        }
                    when {
                        read == UNREADABLE -> {
                            result = Copied.Unreadable
                        }

                        read < 0 -> {
                            result = Copied.Done
                        }

                        total + read > CustomSound.MAX_SIZE_BYTES -> {
                            result = Copied.TooBig
                        }

                        else -> {
                            total += read
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                result
            }
        }
    }

    private fun refused(result: ImportResult): Outcome<ImportResult, DomainError> {
        val kind = if (result == ImportResult.TooBig) "too big" else "not playable"
        logger.log(LogEvent.OperationFailed(OPERATION, kind))
        return Outcome.Success(result)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun release(uri: String) {
        try {
            documents.releaseRead(uri)
        } catch (e: Exception) {
            // The permission goes with the app's next start anyway; nothing else depends on it.
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun deleteQuietly(file: File) {
        try {
            file.delete()
        } catch (e: Exception) {
            // A leftover temporary copy is cleaned by the next import.
        }
    }

    private enum class Copied { Done, Unreadable, TooBig }

    private sealed interface Checked {
        data class Refused(
            val result: ImportResult,
        ) : Checked

        data class Playable(
            val durationMs: Long,
        ) : Checked
    }

    private companion object {
        const val OPERATION = "import sound"
        const val BUFFER_SIZE = 64 * 1024

        /** A read error while copying (`InputStream.read` returns -1 at the end, never -2). */
        const val UNREADABLE = -2
    }
}
