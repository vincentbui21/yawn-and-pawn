package com.yawnandpawn.app.data.recording

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.core.recording.Recording
import com.yawnandpawn.app.core.recording.RecordingRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException

/**
 * [RecordingRepository] over `recording` in `app.db` and the audio files of [files] (Story 7.1). A row is returned with
 * `missing = true` when its file is not there (a restored phone, or storage locked before the first unlock).
 *
 * [save] moves the take's temporary file over `{id}.m4a` (one atomic rename in the same folder) and then upserts the
 * row. An older take is kept aside until the row is written and put back if it cannot be, so a failed save changes
 * nothing; a new recording whose row cannot be written loses its new file again. [delete] deletes the
 * file, then the row. Every exception is a `StorageFailure` holding only the exception class, never its message (which
 * can hold a file path); a failed [save] deletes the take's file. Exceptions in [observeAll] reach its collector.
 */
class RoomRecordingRepository(
    private val dao: RecordingDao,
    private val files: RecordingFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RecordingRepository {
    override fun observeAll(): Flow<List<Recording>> = dao.observeAll().map { rows -> rows.map { it.withFile() } }.flowOn(io)

    override suspend fun get(id: String): Outcome<Recording?, DomainError> = storage { dao.get(id)?.withFile() }

    override suspend fun save(
        recording: Recording,
        take: RecordedTake,
    ): Outcome<Unit, DomainError> {
        val saved =
            storage {
                val target = files.file(recording.fileName) ?: throw IOException("not a file name")
                val temp = files.takeFile(take.takeId)
                if (!temp.isFile) throw IOException("take missing")
                replaceAudio(recording, temp, target)
            }
        if (saved is Outcome.Failure) discardTake(take)
        return saved
    }

    override suspend fun delete(id: String): Outcome<Unit, DomainError> {
        val deleted =
            storage {
                val row = dao.get(id) ?: return@storage false
                val file = files.file(row.fileName)
                if (file != null && file.exists() && !file.delete()) throw IOException("file not deleted")
                dao.delete(id)
                true
            }
        return when (deleted) {
            is Outcome.Failure -> deleted
            is Outcome.Success -> if (deleted.value) Outcome.Success(Unit) else Outcome.Failure(DomainError.NotFound(id))
        }
    }

    private fun RecordingEntity.withFile(): Recording = toRecording(missing = !files.exists(fileName))

    /**
     * Moves [temp] over [target] and writes the row. An older take of the same recording is first moved aside to
     * `{file}.bak`: put back if the move or the row write fails, deleted once both succeeded. A new recording whose row
     * cannot be written leaves no file behind.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun replaceAudio(
        recording: Recording,
        temp: File,
        target: File,
    ) {
        val backup = File(target.path + RecordingFiles.BACKUP_SUFFIX)
        val hadAudio = target.isFile
        if (hadAudio) move(target, backup)
        try {
            move(temp, target)
            dao.upsert(RecordingEntity.of(recording.copy(missing = false)))
        } catch (e: Exception) {
            restore(hadAudio, backup, target)
            throw e
        }
        if (hadAudio) backup.delete()
    }

    /** Puts the older take back over [target], or removes the new file when there was none; never throws. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun restore(
        hadAudio: Boolean,
        backup: File,
        target: File,
    ) {
        try {
            if (hadAudio) move(backup, target) else target.delete()
        } catch (e: Exception) {
            // The original failure is reported; the older take stays in its backup file.
        }
    }

    private fun move(
        from: File,
        to: File,
    ) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun discardTake(take: RecordedTake) {
        withContext(io) {
            try {
                files.takeFile(take.takeId).delete()
            } catch (e: Exception) {
                // An invalid take id names no file; a locked folder is cleaned by the recorder's next start.
            }
        }
    }

    // Every storage or file exception is a StorageFailure with its class only (a message can hold a path); cancellation
    // propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> storage(block: suspend () -> T): Outcome<T, DomainError> =
        try {
            Outcome.Success(withContext(io) { block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure("recording storage: ${e::class.simpleName}"))
        }
}
