package com.yawnandpawn.app.data.db

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 7.2: `app.db` v12 adds `alarm.motivation`, and a v11 file migrates keeping its alarms and recordings. */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMotivationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Host tests run in the :data project directory.
    private fun schema(version: Int) = File("schemas/com.yawnandpawn.app.data.db.AppDatabase/$version.json")

    @Test
    fun `the exported version 12 schema adds the alarm's motivation, none by default`() {
        val json = schema(12).readText()

        assertTrue(json.contains("\"version\": 12"), "schema version 12")
        val alarm = json.substringAfter("\"tableName\": \"alarm\"").substringBefore("\"tableName\"")
        assertTrue(alarm.contains("`motivation` TEXT NOT NULL DEFAULT 'none'"), "the same SQL as MIGRATION_11_12")
        assertEquals(tableNames(schema(11).readText()), tableNames(json), "no new table")
    }

    @Test
    fun `migrating a v11 database keeps alarms and recordings, every alarm with no message, and a message can be stored`() =
        runTest {
            createVersion11(
                "INSERT INTO alarm (id, time_nano_of_day, repeat_days, label, enabled, sound_ref, volume_percent, gradual_volume, " +
                    "ramp_start_percent, vibration, snooze_length_minutes, grace_seconds, vibrate_in_grace, request_code, created_at, " +
                    "updated_at, check_mode) VALUES ('a', 25200000000000, 0, NULL, 1, 'builtin:default', 80, 1, 20, 1, 9, 20, 1, 1000, " +
                    "1, 1, 'Random')",
                "INSERT INTO recording (id, name, duration_ms, file_name, created_at) VALUES ('r1', 'Message 1', 9000, 'r1.m4a', 1)",
            )

            val database = buildAppDatabase(context)
            try {
                val alarms = RoomAlarmRepository(database.alarmDao())
                val alarm = (alarms.get("a") as Outcome.Success).value
                assertEquals(Motivation.None, alarm.motivation)
                assertEquals(1, database.recordingDao().count())

                val chosen = alarm.copy(motivation = Motivation.Recording("r1"))
                assertEquals(Outcome.Success(Unit), alarms.upsert(chosen))
                assertEquals(Outcome.Success(chosen), alarms.get("a"))
            } finally {
                database.close()
            }
        }

    /** A v11 file built from the exported `11.json`, with [rows] inserted. */
    private fun createVersion11(vararg rows: String) {
        val database = JSONObject(schema(11).readText()).getJSONObject("database")
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
            connection.execSQL("PRAGMA user_version = 11")
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
