package com.yawnandpawn.app.data.reset

import android.content.Context
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reset.DeletionMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * [DeletionMarker] as a small file in the device-protected `no_backup` directory (Story 5.9): Android never backs that
 * directory up or moves it to a new phone, and the deletion's settings clear does not touch it. It holds the start count
 * ([countStart], as text). [write] syncs the file to disk before it returns, so a deletion that crashes right after
 * still finds it on the next start.
 */
class FileDeletionMarker(
    context: Context,
) : DeletionMarker {
    private val file: File by lazy { markerFile(context) }

    // A marker that cannot be checked reads as none: the app opens as usual, and nothing is deleted without the user.
    @Suppress("SwallowedException")
    override fun exists(): Boolean =
        try {
            file.isFile
        } catch (e: SecurityException) {
            false
        }

    override suspend fun write(resetAttempts: Boolean): Outcome<Unit, DomainError> =
        io {
            if (resetAttempts || !file.isFile) writeCount(0)
        }

    override suspend fun remove(): Outcome<Unit, DomainError> =
        io {
            if (file.exists() && !file.delete()) throw IOException("marker not deleted")
        }

    // Called once per process on the main thread at app start: a few bytes. Never throws (see the port).
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun countStart(): Int {
        if (!exists()) return 0
        val count =
            try {
                file.readText().trim().toIntOrNull() ?: 0
            } catch (ignored: Exception) {
                0
            } + 1
        try {
            writeCount(count)
        } catch (ignored: Exception) {
            // Still returned: this start counts, even if the next one cannot see it.
        }
        return count
    }

    private fun writeCount(count: Int) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            out.write(count.toString().encodeToByteArray())
            out.fd.sync()
        }
    }

    // Every file error is a StorageFailure naming its type, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun io(block: () -> Unit): Outcome<Unit, DomainError> =
        withContext(Dispatchers.IO) {
            try {
                block()
                Outcome.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failure(DomainError.StorageFailure(e::class.simpleName ?: "file error"))
            }
        }

    companion object {
        const val FILE_NAME = "delete_all_data.marker"

        /** `no_backup/delete_all_data.marker` in device-protected storage, never credential-protected. */
        fun markerFile(context: Context): File = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, FILE_NAME)
    }
}
