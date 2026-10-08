package com.yawnandpawn.app.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * `runtime.db` v1 to v2 (Story 4.8): adds the empty `purchase_intent` table (AD-7). The active session is untouched, so
 * a session that rings through the update keeps ringing. The SQL matches the exported `2.json`; Room checks it after
 * migrating.
 */
val RUNTIME_MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `purchase_intent` (`intent_id` TEXT NOT NULL, `session_id` TEXT NOT NULL, " +
                    "`product_id` TEXT NOT NULL, `snooze_number` INTEGER NOT NULL, `price_micros` INTEGER NOT NULL, " +
                    "`currency` TEXT NOT NULL, `formatted_price` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, PRIMARY KEY(`intent_id`))",
            )
        }
    }

/** Every `runtime.db` migration, in order; `buildRuntimeDatabase` registers them all. */
val RUNTIME_DATABASE_MIGRATIONS: Array<Migration> = arrayOf(RUNTIME_MIGRATION_1_2)
