package com.yawnandpawn.app.android.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import com.yawnandpawn.app.android.AndroidLogger
import com.yawnandpawn.app.appModule
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.data.dataModule
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.appDatabaseFile
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.KoinApplicationAlreadyStartedException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * The backup agent (NFR-14, AD-6, Story 2.12). It is file-based only (`fullBackupOnly`). What is backed up is still
 * decided by the rule files (`data_extraction_rules.xml`, `backup_rules.xml`) through the default Auto Backup handling.
 * The agent adds two things to a restore:
 * - **The downgrade guard:** a restored `app.db` goes through [AppDatabaseRestoreGuard] first, so a newer schema never
 *   replaces the readable one.
 * - **Re-arming:** [onRestoreFinished] runs `rescheduleAll()`, so restored enabled alarms are armed without the user
 *   opening the app. `runtime.db` is never in a backup, so the app starts Idle.
 *
 * The manifest keeps the restore in restricted mode, so `YawnAndPawnApp` (Koin, Room, the session restore) does not run
 * while `app.db` is being replaced. The agent then starts the app's modules itself for the reschedule, and stops them.
 */
class PpsBackupAgent : BackupAgent() {
    private val logger: Logger by lazy { AndroidLogger() }

    // Key-value backup is unused: fullBackupOnly sends every backup and restore through the file-based path.
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onRestoreFile(
        data: ParcelFileDescriptor,
        size: Long,
        destination: File?,
        type: Int,
        mode: Long,
        mtime: Long,
    ) {
        // A null destination is a file the rules exclude: the default handling drains its bytes.
        if (destination == null || type != BackupAgent.TYPE_FILE || destination.canonicalFile != appDatabaseFile(this).canonicalFile) {
            super.onRestoreFile(data, size, destination, type, mode, mtime)
            return
        }
        // The incoming app.db lands in device-protected no_backup storage first (never backed up itself), then the guard
        // decides whether it replaces the current one.
        val incoming = File(createDeviceProtectedStorageContext().noBackupFilesDir, INCOMING_NAME)
        val guard = AppDatabaseRestoreGuard(AppDatabase.SCHEMA_VERSION, SkippedRestoreNotice(this), logger, ::releaseRunningData)
        // A read error from the pipe still propagates, but a partial copy never stays behind.
        try {
            if (copy(data, size, incoming)) guard.accept(incoming, destination)
        } finally {
            incoming.delete()
        }
    }

    /**
     * Re-arms the restored alarms. A failure here (Koin, Room, the scheduler) is logged and never ends the agent, and a
     * Koin graph the agent started is always stopped.
     */
    @Suppress("TooGenericExceptionCaught") // Any failure is logged: the restore itself has already succeeded.
    override fun onRestoreFinished() {
        super.onRestoreFinished()
        var started = false
        try {
            val koin =
                GlobalContext.getOrNull() ?: try {
                    startKoin {
                        androidContext(applicationContext)
                        modules(appModule, dataModule)
                    }.koin.also { started = true }
                } catch (_: KoinApplicationAlreadyStartedException) {
                    // The app started between the check and here: use its graph.
                    GlobalContext.get()
                }
            runBlocking { if (started) koin.get<AlarmScheduling>().rescheduleAll() else rescheduleRestoredFile(koin) }
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(RESCHEDULE_OPERATION, e::class.simpleName ?: "failed"))
        } finally {
            if (started) stopKoin()
        }
    }

    /**
     * Reschedules while the app is running. The running graph's `AlarmScheduling` may still hold a repository over the
     * `app.db` the restore replaced, so a new one reads the alarms through the graph's current `AlarmRepository`, which
     * [releaseRunningData] reloaded when the file was replaced.
     */
    private suspend fun rescheduleRestoredFile(koin: Koin) {
        AlarmScheduling(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get()).rescheduleAll()
    }

    /**
     * Just before a restored `app.db` replaces the current one: a running app's data graph is reloaded, which closes its
     * databases (a connection must never stay on the replaced file, and an open file cannot be replaced on every file
     * system), and the next lookup opens the restored file. Instances the running app already holds stay closed; the
     * system ends the app process after a restore (`killAfterRestore`).
     */
    private fun releaseRunningData() {
        GlobalContext.getOrNull()?.let { koin ->
            koin.unloadModules(listOf(dataModule))
            koin.loadModules(listOf(dataModule))
        }
    }

    /**
     * Copies exactly [size] bytes of the restore stream [data] to [target], as the default handling does. The stream
     * is not closed: it is the restore pipe, which the framework owns and keeps reading after this file. A write error
     * still drains the rest of this file's bytes, so the next file starts in the right place. Returns true when
     * [target] holds the whole file.
     */
    private fun copy(
        data: ParcelFileDescriptor,
        size: Long,
        target: File,
    ): Boolean {
        val input = FileInputStream(data.fileDescriptor)
        var output: FileOutputStream? = openOrNull(target)
        var complete = output != null
        val buffer = ByteArray(BUFFER_BYTES)
        var left = size
        try {
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (read <= 0) {
                    logger.log(LogEvent.OperationFailed(OPERATION, "restore data ended early"))
                    return false
                }
                left -= read
                try {
                    output?.write(buffer, 0, read)
                } catch (e: IOException) {
                    logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName ?: "write failed"))
                    output?.closeQuietly()
                    output = null
                    complete = false
                }
            }
        } finally {
            output?.closeQuietly()
        }
        return complete
    }

    private fun openOrNull(target: File): FileOutputStream? =
        try {
            target.parentFile?.mkdirs()
            FileOutputStream(target)
        } catch (e: IOException) {
            logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName ?: "open failed"))
            null
        }

    private fun FileOutputStream.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // Nothing to do: the copy is already reported as incomplete or finished.
        }
    }

    private companion object {
        const val OPERATION = "restore app.db"
        const val RESCHEDULE_OPERATION = "restore reschedule"
        const val INCOMING_NAME = "app.db.restoring"
        const val BUFFER_BYTES = 32 * 1024
    }
}
