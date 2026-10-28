package com.yawnandpawn.app.data.db

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.alarm.MotivationMode
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 7.5: `app.db` v14 adds `alarm.motivation_mode`, and a v13 file migrates keeping its alarms (with their message),
 * history and recordings, every alarm playing its message after the check.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMotivationModeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Host tests run in the :data project directory.
    private fun schema(version: Int) = File("schemas/com.yawnandpawn.app.data.db.AppDatabase/$version.json")

    @Test
    fun `the exported version 14 schema adds the alarm's motivation mode, after the check by default`() {
        assertTrue(schema(14).exists(), "exported schema missing: ${schema(14).absolutePath}")
        val json = schema(14).readText()

        assertTrue(json.contains("\"version\": 14"), "schema version 14")
        val alarm = json.substringAfter("\"tableName\": \"alarm\"").substringBefore("\"tableName\"")
        assertTrue(alarm.contains("`motivation_mode` TEXT NOT NULL DEFAULT 'after_up'"), "the same SQL as MIGRATION_13_14")
        assertTrue(alarm.contains("`motivation` TEXT NOT NULL DEFAULT 'none'"), "Story 7.2's column kept")
        assertTrue(alarm.contains("`offer_skip` INTEGER NOT NULL DEFAULT 0"), "Story 7.11's column kept")
        assertEquals(tableNames(schema(13).readText()), tableNames(json), "no new table")
    }

    @Test
    fun `migrating a v13 database keeps alarms, history and recordings, every alarm after the check, and Mix can be stored`() =
        runTest {
            createVersion13(
                "INSERT INTO alarm (id, time_nano_of_day, repeat_days, label, enabled, sound_ref, volume_percent, gradual_volume, " +
                    "ramp_start_percent, vibration, snooze_length_minutes, grace_seconds, vibrate_in_grace, request_code, created_at, " +
                    "updated_at, check_mode, motivation, offer_skip, skipped_date) VALUES ('a', 25200000000000, 0, NULL, 1, " +
                    "'builtin:default', 80, 1, 20, 1, 9, 20, 1, 1000, 1, 1, 'Random', 'recording:r1', 1, NULL)",
                "INSERT INTO session_history (session_id, alarm_id, scheduled_at, first_ring_at, ended_at, snooze_count, " +
                    "check_types, time_to_complete_ms, fallback_used, direct_boot, outcome, fallback_from) VALUES ('s1', 'a', 1, 1, 2, " +
                    "0, 'Math', 1000, 0, 0, 'Completed', NULL)",
                "INSERT INTO recording (id, name, duration_ms, file_name, created_at) VALUES ('r1', 'Message 1', 9000, 'r1.m4a', 1)",
            )

            val database = buildAppDatabase(context)
            try {
                val alarms = RoomAlarmRepository(database.alarmDao())
                val alarm = (alarms.get("a") as Outcome.Success).value
                assertEquals(MotivationMode.AfterImUp, alarm.motivationMode)
                assertEquals(Motivation.Recording("r1"), alarm.motivation, "the message is kept")
                assertTrue(alarm.offerSkip, "Story 7.11's value is kept")
                assertEquals(1, database.sessionHistoryDao().count())
                assertNotNull(database.sessionHistoryDao().findById("s1"))
                assertEquals(1, database.recordingDao().count())

                val mixed = alarm.copy(motivationMode = MotivationMode.MixIntoAlarm)
                assertEquals(Outcome.Success(Unit), alarms.upsert(mixed))
                assertEquals(Outcome.Success(mixed), alarms.get("a"))
            } finally {
                database.close()
            }
            assertEquals(AppDatabase.SCHEMA_VERSION, userVersion())
        }

    @Test
    fun `a mode this build does not know reads as after the check`() =
        runTest {
            createVersion13(
                "INSERT INTO alarm (id, time_nano_of_day, repeat_days, label, enabled, sound_ref, volume_percent, gradual_volume, " +
                    "ramp_start_percent, vibration, snooze_length_minutes, grace_seconds, vibrate_in_grace, request_code, created_at, " +
                    "updated_at, check_mode, motivation, offer_skip, skipped_date) VALUES ('a', 25200000000000, 0, NULL, 1, " +
                    "'builtin:default', 80, 1, 20, 1, 9, 20, 1, 1000, 1, 1, 'Random', 'recording:r1', 0, NULL)",
            )

            val migrating = buildAppDatabase(context)
            try {
                migrating.recordingDao().count() // opens the file: migrated to v14
            } finally {
                migrating.close()
            }
            val connection = AndroidSQLiteDriver().open(appDatabaseFile(context).absolutePath)
            try {
                connection.execSQL("UPDATE alarm SET motivation_mode = 'later' WHERE id = 'a'")
            } finally {
                connection.close()
            }

            val database = buildAppDatabase(context)
            try {
                val alarm = (RoomAlarmRepository(database.alarmDao()).get("a") as Outcome.Success).value
                assertEquals(MotivationMode.AfterImUp, alarm.motivationMode)
            } finally {
                database.close()
            }
        }

    /** A v13 file built from the exported `13.json`, with [rows] inserted. */
    private fun createVersion13(vararg rows: String) {
        val database = JSONObject(schema(13).readText()).getJSONObject("database")
        val file = appDatabaseFile(context).apply { parentFile?.mkdirs() }
        val connection = AndroidSQLiteDriver().open(file.absolutePath)
        try {
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                connection.execSQL(entity.getString("createSql").replace(TABLE_NAME, table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    connection.execSQL(indices.getJSONObject(j).getString("createSql").replace(TABLE_NAME, table))
                }
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) connection.execSQL(setup.getString(i))
            rows.forEach(connection::execSQL)
            connection.execSQL("PRAGMA user_version = 13")
        } finally {
            connection.close()
        }
    }

    /** The `user_version` of the file at [appDatabaseFile]: the schema version Room left it at. */
    private fun userVersion(): Int {
        val connection = AndroidSQLiteDriver().open(appDatabaseFile(context).absolutePath)
        try {
            return connection.prepare("PRAGMA user_version").use { statement ->
                statement.step()
                statement.getInt(0)
            }
        } finally {
            connection.close()
        }
    }

    private fun tableNames(json: String): List<String> {
        val entities = JSONObject(json).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
    }

    private companion object {
        const val TABLE_NAME = "\${TABLE_NAME}"
    }
}
