package com.yawnandpawn.app.data.sound

import android.content.Context
import android.os.UserManager
import java.io.File
import java.io.IOException

/**
 * Where the user's imported sounds live (Story 7.4, the `RecordingFiles` pattern): the folder `sounds/` in
 * credential-protected storage (`context.filesDir`), never in the device-protected context, and left out of every
 * backup. An import is copied into `import-{token}.tmp` in the same folder and renamed to `{id}.{ext}` once it is
 * checked, so the rename is atomic and a crash never leaves a half-written sound. [resolveFolder] is called on each
 * use: before the first unlock it may throw, and every file then reads as missing. Only plain file names inside the
 * folder are ever opened, so a restored row cannot point anywhere else. [isUnlocked] says whether credential storage
 * can be read; before that the folder looks empty, so [deleteAll] refuses instead of reporting it gone.
 */
class SoundFiles(
    private val isUnlocked: () -> Boolean = { true },
    private val resolveFolder: () -> File,
) {
    /** The folder (not created here). */
    fun folder(): File = resolveFolder()

    /** The temporary file of the import [token] (letters, digits and `-` only). */
    fun importFile(token: String): File {
        require(token.isNotEmpty() && token.all { it.isLetterOrDigit() || it == '-' }) { "invalid import token" }
        return File(folder(), "$IMPORT_PREFIX$token$IMPORT_SUFFIX")
    }

    /** The file [fileName] in the folder, or null when it is not a plain file name. */
    fun file(fileName: String): File? = if (isPlainName(fileName)) File(folder(), fileName) else null

    /** Whether the sound file [fileName] is there; false when it cannot be checked (storage locked). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun exists(fileName: String): Boolean =
        try {
            file(fileName)?.isFile == true
        } catch (e: Exception) {
            false
        }

    /**
     * The copy of the sound [id] (`{id}.{any extension}`), or null when there is none or it cannot be read (storage
     * locked, an id that is not a plain name). The ring finds its sound this way, without the database. Never throws.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun find(id: String): File? =
        try {
            if (!isPlainName(id) || id.startsWith(IMPORT_PREFIX)) {
                null
            } else {
                folder()
                    .listFiles { file -> file.isFile && file.name.startsWith("$id.") && !file.name.endsWith(IMPORT_SUFFIX) }
                    ?.minByOrNull { it.name }
            }
        } catch (e: Exception) {
            null
        }

    /** Deletes every temporary import left in the folder (a crash or a kill while copying); never throws. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun deleteStaleImports() {
        try {
            folder().listFiles { file -> file.name.endsWith(IMPORT_SUFFIX) }?.forEach { it.delete() }
        } catch (e: Exception) {
            // Nothing to clean when the folder cannot be read; the next import tries again.
        }
    }

    /**
     * Deletes the folder with every sound in it ("Delete all data"); nothing there is not an error. Throws before the
     * first unlock (the files are there but cannot be seen), so the deletion is retried later.
     */
    fun deleteAll() {
        if (!isUnlocked()) throw IOException("credential storage locked")
        val folder = folder()
        if (folder.exists() && !folder.deleteRecursively()) throw IOException("sounds not deleted")
    }

    private fun isPlainName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }

    companion object {
        /** The folder name under `filesDir`; also named in the backup rules. */
        const val FOLDER = "sounds"
        const val IMPORT_PREFIX = "import-"
        const val IMPORT_SUFFIX = ".tmp"

        /**
         * The production folder, `context.filesDir/sounds`. A credential-protected read in `:data`, on purpose (like
         * `RecordingFiles`): the imported sounds are the user's media (Epic 7), never needed before the first unlock (a
         * ring then plays the default sound), and must stay out of the device-protected storage the backup rules include.
         */
        @Suppress("CredentialStorageAccess")
        fun of(context: Context): SoundFiles =
            SoundFiles(isUnlocked = { context.getSystemService(UserManager::class.java).isUserUnlocked }) {
                File(context.filesDir, FOLDER)
            }
    }
}
