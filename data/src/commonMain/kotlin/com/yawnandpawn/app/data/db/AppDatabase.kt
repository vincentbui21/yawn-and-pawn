package com.yawnandpawn.app.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.yawnandpawn.app.data.alarm.AlarmDao
import com.yawnandpawn.app.data.alarm.AlarmEntity
import com.yawnandpawn.app.data.alarm.RequestCodeSequenceDao
import com.yawnandpawn.app.data.alarm.RequestCodeSequenceEntity
import com.yawnandpawn.app.data.history.SessionHistoryDao
import com.yawnandpawn.app.data.history.SessionHistoryEntity

/**
 * `app.db` (AD-6): user data that is backed up. Version 1 held only `alarm`; version 2 (Story 1.10) adds
 * `request_code_sequence`, the request-code high-water mark ([MIGRATION_1_2]); version 3 (Story 1.13) adds
 * `session_history` ([MIGRATION_2_3]). There is no destructive migration fallback; restoring a newer file onto an
 * older install is covered by `docs/decisions/db-downgrade.md`.
 */
@Database(
    entities = [AlarmEntity::class, RequestCodeSequenceEntity::class, SessionHistoryEntity::class],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao

    abstract fun requestCodeSequenceDao(): RequestCodeSequenceDao

    abstract fun sessionHistoryDao(): SessionHistoryDao

    companion object {
        /** File name in the device-protected database directory; also named in the backup rules. */
        const val FILE_NAME = "app.db"

        /**
         * The `app.db` schema this build knows: the `@Database` version, and the limit `PpsBackupAgent` checks a restored
         * file against (Room cannot open a newer one, `docs/decisions/db-downgrade.md`).
         */
        const val SCHEMA_VERSION = 3
    }
}

/** Implemented by the Room compiler for each target. */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
