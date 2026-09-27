package com.yawnandpawn.app.data.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File

/**
 * `app.db` in device-protected storage, so alarms are readable before the first unlock after a reboot (NFR-4).
 * Built from [Context.createDeviceProtectedStorageContext], never the credential-protected context. Do not call
 * `applicationContext` on the device-protected context: it returns the credential-protected one.
 */
fun appDatabaseFile(context: Context): File = context.createDeviceProtectedStorageContext().getDatabasePath(AppDatabase.FILE_NAME)

/**
 * Opens `app.db` at [appDatabaseFile].
 *
 * Journal mode TRUNCATE instead of Room's default WAL: Auto Backup copies only `app.db` (backup rules), and with WAL
 * recent commits could still sit in `app.db-wal` and be missing from the backup. Alarm writes are rare, so WAL's
 * concurrency is not needed. No destructive migration fallback: a missing migration must fail loudly.
 */
fun buildAppDatabase(context: Context): AppDatabase {
    val deviceContext = context.createDeviceProtectedStorageContext()
    return Room
        .databaseBuilder<AppDatabase>(
            context = deviceContext,
            name = deviceContext.getDatabasePath(AppDatabase.FILE_NAME).absolutePath,
            factory = { AppDatabaseConstructor.initialize() },
        ).setDriver(AndroidSQLiteDriver())
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .build()
}
