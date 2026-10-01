package com.yawnandpawn.app.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.yawnandpawn.app.data.session.ActiveSessionDao
import com.yawnandpawn.app.data.session.ActiveSessionEntity

/**
 * `runtime.db` (AD-6): the write-ahead copy of the active wake session (AD-2 rule 2). Never backed up: a restored
 * half-finished session would ring on another phone. Version 1 (Story 1.12) holds only `active_session`; the purchase
 * intents and the grant ledger arrive in Epic 4 with a migration. There is no destructive migration fallback.
 */
@Database(entities = [ActiveSessionEntity::class], version = 1, exportSchema = true)
@ConstructedBy(RuntimeDatabaseConstructor::class)
abstract class RuntimeDatabase : RoomDatabase() {
    abstract fun activeSessionDao(): ActiveSessionDao

    companion object {
        /** File name in the device-protected database directory; the backup rules exclude it by this name. */
        const val FILE_NAME = "runtime.db"
    }
}

/** Implemented by the Room compiler for each target. */
@Suppress("KotlinNoActualForExpect")
expect object RuntimeDatabaseConstructor : RoomDatabaseConstructor<RuntimeDatabase> {
    override fun initialize(): RuntimeDatabase
}
