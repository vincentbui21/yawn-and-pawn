package com.yawnandpawn.app.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.yawnandpawn.app.data.alarm.AlarmDao
import com.yawnandpawn.app.data.alarm.AlarmEntity
import com.yawnandpawn.app.data.alarm.RequestCodeSequenceDao
import com.yawnandpawn.app.data.alarm.RequestCodeSequenceEntity

/**
 * `app.db` (AD-6): user data that is backed up. Version 1 held only `alarm`; version 2 (Story 1.10) adds
 * `request_code_sequence`, the request-code high-water mark ([MIGRATION_1_2]). `session_history` arrives in v3
 * (Story 1.13) with a migration. There is no destructive migration fallback.
 */
@Database(entities = [AlarmEntity::class, RequestCodeSequenceEntity::class], version = 2, exportSchema = true)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao

    abstract fun requestCodeSequenceDao(): RequestCodeSequenceDao

    companion object {
        /** File name in the device-protected database directory; also named in the backup rules. */
        const val FILE_NAME = "app.db"
    }
}

/** Implemented by the Room compiler for each target. */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
