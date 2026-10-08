package com.yawnandpawn.app.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.checks.CheckMode

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
 * v4 to v5 (Story 3.4): adds `alarm.vibrate_in_grace` ("Vibrate during quiet time", per alarm). A stored alarm takes its
 * own `vibration` (on by default, owner-approved 2026-09-26; an alarm that never vibrates keeps not vibrating). Every
 * other column and table is untouched. The SQL matches the exported `5.json`; Room checks it after migrating.
 */
val MIGRATION_4_5: Migration =
    object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `alarm` ADD COLUMN `vibrate_in_grace` INTEGER NOT NULL DEFAULT 1")
            connection.execSQL("UPDATE `alarm` SET `vibrate_in_grace` = `vibration`")
        }
    }

/**
 * v5 to v6 (Story 3.5): adds `alarm.check_mode` (Random for every alarm) and the `check_config` table, and gives every
 * existing alarm the checks it rang until then (Math · Medium · 3, `CheckConfig.LEGACY_DEFAULT_ENTRY`: frozen, so the
 * Easy default of 2026-10-08 does not change what an upgrade stores) at position 0, with the row id
 * the use cases use (`CheckConfig.idFor`) and the migration time as its timestamps. The SQL matches the exported
 * `6.json`; Room checks it after migrating.
 */
val MIGRATION_5_6: Migration =
    object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val default = CheckConfig.LEGACY_DEFAULT_ENTRY
            connection.execSQL("ALTER TABLE `alarm` ADD COLUMN `check_mode` TEXT NOT NULL DEFAULT '${CheckMode.Random.name}'")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `check_config` (`id` TEXT NOT NULL, `alarm_id` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, `type` TEXT NOT NULL, `difficulty` TEXT NOT NULL, `count` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`alarm_id`) REFERENCES `alarm`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_check_config_alarm_id` ON `check_config` (`alarm_id`)")
            connection.execSQL(
                "INSERT INTO `check_config` (`id`, `alarm_id`, `position`, `type`, `difficulty`, `count`, `created_at`, " +
                    "`updated_at`) SELECT `id` || ':${default.type.id}', `id`, 0, '${default.type.id}', '${default.difficulty.name}', " +
                    "${default.count}, CAST(strftime('%s', 'now') AS INTEGER) * 1000, " +
                    "CAST(strftime('%s', 'now') AS INTEGER) * 1000 FROM `alarm`",
            )
        }
    }

/**
 * v6 to v7 (Story 3.9): adds the nullable `session_history.fallback_from`, the check type id the fallback check replaced.
 * Stored rows keep null (no fallback recorded). The SQL matches the exported `7.json`; Room checks it after migrating.
 */
val MIGRATION_6_7: Migration =
    object : Migration(6, 7) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `session_history` ADD COLUMN `fallback_from` TEXT")
        }
    }

/**
 * v7 to v8 (Story 3.10): adds the registered code of a QR/Barcode check to `check_config`, as three nullable columns:
 * `code_format` (`CodeFormat.storedName`), `code_value` (the SHA-256 fingerprint of the trimmed value, never the raw
 * value; see `CheckCodeColumns`) and `code_registered_at` (epoch milliseconds, Story 3.13's re-register banner). Every
 * stored entry keeps nulls (no QR/Barcode entry existed before). The SQL matches the exported `8.json`; Room checks it
 * after migrating.
 */
val MIGRATION_7_8: Migration =
    object : Migration(7, 8) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `check_config` ADD COLUMN `code_format` TEXT")
            connection.execSQL("ALTER TABLE `check_config` ADD COLUMN `code_value` TEXT")
            connection.execSQL("ALTER TABLE `check_config` ADD COLUMN `code_registered_at` INTEGER")
        }
    }

/** Every migration of `app.db`, oldest first; `buildAppDatabase` registers them all. */
val APP_DATABASE_MIGRATIONS: Array<Migration> =
    arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
