package com.yawnandpawn.app.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.yawnandpawn.app.data.alarm.AlarmDao
import com.yawnandpawn.app.data.alarm.AlarmEntity
import com.yawnandpawn.app.data.alarm.CheckConfigDao
import com.yawnandpawn.app.data.alarm.CheckConfigEntity
import com.yawnandpawn.app.data.alarm.RequestCodeSequenceDao
import com.yawnandpawn.app.data.alarm.RequestCodeSequenceEntity
import com.yawnandpawn.app.data.history.SessionHistoryDao
import com.yawnandpawn.app.data.history.SessionHistoryEntity
import com.yawnandpawn.app.data.history.SessionMergeEntity

/**
 * `app.db` (AD-6): user data that is backed up. Version 1 held only `alarm`; version 2 (Story 1.10) adds
 * `request_code_sequence`, the request-code high-water mark ([MIGRATION_1_2]); version 3 (Story 1.13) adds
 * `session_history` ([MIGRATION_2_3]); version 4 (Story 2.9) adds `session_merge` ([MIGRATION_3_4]); version 5 (Story
 * 3.4) adds `alarm.vibrate_in_grace` ([MIGRATION_4_5]); version 6 (Story 3.5) adds `check_config` and `alarm.check_mode`
 * ([MIGRATION_5_6]); version 7 (Story 3.9) adds `session_history.fallback_from` ([MIGRATION_6_7]); version 8 (Story 3.10) adds
 * the registered code to `check_config` ([MIGRATION_7_8]). There is no
 * destructive migration fallback; restoring a newer file onto an older install is
 * covered by `docs/decisions/db-downgrade.md`.
 */
@Database(
    entities = [
        AlarmEntity::class,
        RequestCodeSequenceEntity::class,
        SessionHistoryEntity::class,
        SessionMergeEntity::class,
        CheckConfigEntity::class,
    ],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao

    abstract fun checkConfigDao(): CheckConfigDao

    abstract fun requestCodeSequenceDao(): RequestCodeSequenceDao

    abstract fun sessionHistoryDao(): SessionHistoryDao

    companion object {
        /** File name in the device-protected database directory; also named in the backup rules. */
        const val FILE_NAME = "app.db"

        /**
         * The `app.db` schema this build knows: the `@Database` version, and the limit `PpsBackupAgent` checks a restored
         * file against (Room cannot open a newer one, `docs/decisions/db-downgrade.md`).
         */
        const val SCHEMA_VERSION = 8
    }
}

/** Implemented by the Room compiler for each target. */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
