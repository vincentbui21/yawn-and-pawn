package com.yawnandpawn.app.android.backup

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The `app.db` downgrade policy (`docs/decisions/db-downgrade.md`, Story 2.12). A restored `app.db` arrives as
 * [accept]'s `incoming` copy. If its schema (`PRAGMA user_version`, read from the SQLite header) is at or below
 * [installedVersion], it replaces `destination`, and Room's migrations bring an older file up to date on the next open.
 * A newer schema cannot be opened (there is no down-migration and no destructive fallback). So that file is dropped,
 * the current `app.db` is kept, the skip is logged once, and [notice] records it for the user. A file that is not a
 * SQLite database, or one with no Room schema (`user_version` 0 or below), is dropped and logged too. A restore that
 * replaces the file clears an earlier skip from [notice], so a stale notice never shows. The incoming copy never
 * survives [accept].
 */
class AppDatabaseRestoreGuard(
    private val installedVersion: Int,
    private val notice: SkippedRestoreNotice,
    private val logger: Logger,
    /** Runs just before the move that replaces `destination` (the agent closes a running app's connections there). */
    private val beforeReplace: () -> Unit = {},
) {
    /** What happened to a restored `app.db`. */
    sealed interface Result {
        /** It replaced the current file. */
        data object Restored : Result

        /** Its schema [restoredVersion] is newer than this build's; the current file is kept. */
        data class SkippedNewer(
            val restoredVersion: Int,
        ) : Result

        /** It is not a readable SQLite file, or it could not be moved into place; the current file is kept. */
        data object SkippedUnreadable : Result
    }

    fun accept(
        incoming: File,
        destination: File,
    ): Result {
        try {
            val restoredVersion = userVersion(incoming)
            return when {
                restoredVersion == null || restoredVersion <= 0 -> {
                    logger.log(LogEvent.OperationFailed(OPERATION, "not a database"))
                    Result.SkippedUnreadable
                }

                restoredVersion > installedVersion -> {
                    logger.log(LogEvent.OperationFailed(OPERATION, "schema $restoredVersion is newer than $installedVersion"))
                    if (!notice.record(restoredVersion)) logger.log(LogEvent.OperationFailed(NOTICE_OPERATION, COMMIT_FAILED))
                    Result.SkippedNewer(restoredVersion)
                }

                else -> {
                    replace(incoming, destination).also { result ->
                        if (result == Result.Restored && !notice.clear()) {
                            logger.log(LogEvent.OperationFailed(NOTICE_OPERATION, COMMIT_FAILED))
                        }
                    }
                }
            }
        } finally {
            incoming.delete()
        }
    }

    private fun replace(
        incoming: File,
        destination: File,
    ): Result =
        try {
            destination.parentFile?.mkdirs()
            beforeReplace()
            Files.move(incoming.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            // A journal left beside the old file must never be rolled back onto the restored one. It is deleted only
            // after the move: when the move fails, the kept file still needs its hot journal.
            File(destination.path + JOURNAL_SUFFIX).delete()
            Result.Restored
        } catch (e: IOException) {
            logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName ?: "move failed"))
            Result.SkippedUnreadable
        }

    companion object {
        private const val OPERATION = "restore app.db"
        private const val NOTICE_OPERATION = "restore app.db notice"
        private const val COMMIT_FAILED = "commit failed"
        private const val JOURNAL_SUFFIX = "-journal"

        /** "SQLite format 3" and a NUL: the first 16 bytes of every SQLite 3 database file. */
        private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        /** The header field `PRAGMA user_version` reads: a big-endian 32-bit integer at byte 60. */
        private const val USER_VERSION_OFFSET = 60L

        /** The `user_version` of the SQLite file [file]; null when it is not one (too short, wrong magic, unreadable). */
        fun userVersion(file: File): Int? =
            try {
                RandomAccessFile(file, "r").use { raf ->
                    if (raf.length() < USER_VERSION_OFFSET + Int.SIZE_BYTES) return null
                    val magic = ByteArray(MAGIC.size)
                    raf.readFully(magic)
                    if (!magic.contentEquals(MAGIC)) return null
                    raf.seek(USER_VERSION_OFFSET)
                    raf.readInt()
                }
            } catch (_: IOException) {
                null
            }
    }
}
