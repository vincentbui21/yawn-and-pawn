package com.yawnandpawn.app.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.yawnandpawn.app.data.alarm.AlarmDao
import com.yawnandpawn.app.data.alarm.AlarmEntity

/**
 * `app.db` (AD-6): user data that is backed up. Version 1 holds only `alarm`; `session_history` arrives in v2
 * (Story 1.13) with a migration. There is no destructive migration fallback.
 */
@Database(entities = [AlarmEntity::class], version = 1, exportSchema = true)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao

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
