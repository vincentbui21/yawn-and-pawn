package com.yawnandpawn.app.data.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File

/**
 * `runtime.db` in device-protected storage, next to `app.db`, so a session survives a reboot and is readable before
 * the first unlock (NFR-4). Built from [Context.createDeviceProtectedStorageContext], never the credential-protected
 * context.
 */
fun runtimeDatabaseFile(context: Context): File = context.createDeviceProtectedStorageContext().getDatabasePath(RuntimeDatabase.FILE_NAME)

/**
 * Opens `runtime.db` at [runtimeDatabaseFile]. Rollback journal (TRUNCATE) as for `app.db`: each commit is durable in
 * the one file once the transaction returns, which is what the write-ahead rule needs. Every migration in
 * [RUNTIME_DATABASE_MIGRATIONS] is registered; there is no destructive migration fallback, so a missing migration fails
 * loudly instead of dropping an active session.
 */
fun buildRuntimeDatabase(context: Context): RuntimeDatabase {
    val deviceContext = context.createDeviceProtectedStorageContext()
    return Room
        .databaseBuilder<RuntimeDatabase>(
            context = deviceContext,
            name = deviceContext.getDatabasePath(RuntimeDatabase.FILE_NAME).absolutePath,
            factory = { RuntimeDatabaseConstructor.initialize() },
        ).setDriver(AndroidSQLiteDriver())
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .addMigrations(*RUNTIME_DATABASE_MIGRATIONS)
        .build()
}
