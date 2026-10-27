package com.yawnandpawn.app.data.recording

import android.content.Context
import android.os.UserManager
import java.io.File
import java.io.IOException

/**
 * Where the recorded audio lives (Story 7.1): the folder `recordings/` in credential-protected storage
 * (`context.filesDir`), never in the device-protected context, and left out of every backup. A take is recorded into
 * `take-{takeId}.m4a.tmp` in the same folder and renamed to `{id}.m4a` when it is saved, so the rename is atomic and a
 * crash never leaves a half-written recording. [resolveFolder] is called on each use: before the first unlock it may
 * throw, and every file then reads as missing. Only plain file names inside the folder are ever opened, so a restored
 * row cannot point anywhere else. [isUnlocked] says whether credential storage can be read (the user has unlocked since
 * boot); before that the folder looks empty, so [deleteAll] refuses instead of reporting it gone.
 */
class RecordingFiles(
    private val isUnlocked: () -> Boolean = { true },
    private val resolveFolder: () -> File,
) {
    /** The folder (not created here). */
    fun folder(): File = resolveFolder()

    /** The temporary file of the take [takeId] (letters, digits and `-` only). */
    fun takeFile(takeId: String): File {
        require(takeId.isNotEmpty() && takeId.all { it.isLetterOrDigit() || it == '-' }) { "invalid take id" }
        return File(folder(), "$TAKE_PREFIX$takeId$TAKE_SUFFIX")
    }

    /** The file [fileName] in the folder, or null when it is not a plain file name. */
    fun file(fileName: String): File? = if (isPlainName(fileName)) File(folder(), fileName) else null

    /** Whether the recording file [fileName] is there; false when it cannot be checked (storage locked). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun exists(fileName: String): Boolean =
        try {
            file(fileName)?.isFile == true
        } catch (e: Exception) {
            false
        }

    /** Deletes every temporary take left in the folder (a crash or a kill while recording); never throws. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun deleteStaleTakes() {
        try {
            folder().listFiles { file -> file.name.endsWith(TAKE_SUFFIX) }?.forEach { it.delete() }
        } catch (e: Exception) {
            // Nothing to clean when the folder cannot be read; the next start tries again.
        }
    }

    /**
     * Deletes the folder with every recording and take in it ("Delete all data"); nothing there is not an error. Throws
     * before the first unlock (the files are there but cannot be seen), so the deletion is retried later.
     */
    fun deleteAll() {
        if (!isUnlocked()) throw IOException("credential storage locked")
        val folder = folder()
        if (folder.exists() && !folder.deleteRecursively()) throw IOException("recordings not deleted")
    }

    private fun isPlainName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }

    companion object {
        /** The folder name under `filesDir`; also named in the backup rules. */
        const val FOLDER = "recordings"
        const val TAKE_PREFIX = "take-"
        const val TAKE_SUFFIX = ".m4a.tmp"

        /** An older take kept aside while a re-record is saved (`{id}.m4a.bak`). */
        const val BACKUP_SUFFIX = ".bak"

        /**
         * The production folder, `context.filesDir/recordings`. The one credential-protected read in `:data`, on purpose:
         * recordings are the user's media (Epic 7), never needed before the first unlock, and must stay out of the
         * device-protected storage the backup rules include.
         */
        @Suppress("CredentialStorageAccess")
        fun of(context: Context): RecordingFiles =
            RecordingFiles(isUnlocked = { context.getSystemService(UserManager::class.java).isUserUnlocked }) {
                File(context.filesDir, FOLDER)
            }
    }
}
