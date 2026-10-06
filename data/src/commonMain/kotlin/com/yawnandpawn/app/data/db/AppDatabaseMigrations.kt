package com.yawnandpawn.app.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.yawnandpawn.app.core.alarm.RequestCodes

/**
 * v1 to v2 (Story 1.10): adds `request_code_sequence` and seeds its one row with the highest request code in use, at
 * least [RequestCodes.INITIAL_HIGH_WATER_MARK], so the next alarm gets a code above every existing one. Every alarm is
 * kept. The table SQL matches the exported `2.json`; Room checks it after migrating.
 */
val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `request_code_sequence` " +
                    "(`id` INTEGER NOT NULL, `last_used` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            connection.execSQL(
                "INSERT INTO `request_code_sequence` (`id`, `last_used`) " +
                    "SELECT 0, MAX(IFNULL(MAX(`request_code`), ${RequestCodes.INITIAL_HIGH_WATER_MARK}), " +
                    "${RequestCodes.INITIAL_HIGH_WATER_MARK}) FROM `alarm`",
            )
        }
    }

/**
 * v2 to v3 (Story 1.13): adds the empty `session_history` table (AD-18) and its `scheduled_at` index. Alarms and the
 * request-code mark are untouched. The SQL matches the exported `3.json`; Room checks it after migrating.
 */
val MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `session_history` (`session_id` TEXT NOT NULL, `alarm_id` TEXT NOT NULL, " +
                    "`scheduled_at` INTEGER NOT NULL, `first_ring_at` INTEGER NOT NULL, `ended_at` INTEGER, " +
                    "`snooze_count` INTEGER NOT NULL, `check_types` TEXT NOT NULL, `time_to_complete_ms` INTEGER, " +
                    "`fallback_used` INTEGER NOT NULL, `direct_boot` INTEGER NOT NULL, `outcome` TEXT, PRIMARY KEY(`session_id`))",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_session_history_scheduled_at` ON `session_history` (`scheduled_at`)",
            )
        }
    }

/**
 * v3 to v4 (Story 2.9): adds the empty `session_merge` table, one row per alarm occurrence merged into a session
 * (FR-SES-7). Alarms, the request-code mark and history are untouched. The SQL matches the exported `4.json`; Room
 * checks it after migrating.
 */
val MIGRATION_3_4: Migration =
    object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `session_merge` (`session_id` TEXT NOT NULL, `alarm_id` TEXT NOT NULL, " +
                    "`scheduled_at` INTEGER NOT NULL, `merged_at` INTEGER NOT NULL, PRIMARY KEY(`session_id`, `alarm_id`, `scheduled_at`))",
            )
        }
    }

/**
 * v4 to v5 (Story 3.4): adds `alarm.vibrate_in_grace` ("Vibrate during quiet time", per alarm), on for every stored alarm
 * (owner-approved default 2026-09-26). Every other column and table is untouched. The SQL matches the exported `5.json`;
 * Room checks it after migrating.
 */
val MIGRATION_4_5: Migration =
    object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `alarm` ADD COLUMN `vibrate_in_grace` INTEGER NOT NULL DEFAULT 1")
        }
    }

/** Every migration of `app.db`, oldest first; `buildAppDatabase` registers them all. */
val APP_DATABASE_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
