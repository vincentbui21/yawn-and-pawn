package com.yawnandpawn.app.data.db

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.alarm.Alarm
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

/**
 * 0.1.7 (owner decision 2026-10-30): the snooze lengths are 5 / 10 / 15 / 30 min. `app.db` v16 changes no table, and a v15
 * file migrates every alarm with another length (9, the old default) to 10, keeping the allowed ones.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseSnoozeLengthTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Host tests run in the :data project directory.
    private fun schema(version: Int) = File("schemas/com.yawnandpawn.app.data.db.AppDatabase/$version.json")

    @Test
    fun `the exported version 16 schema has the same tables as version 15`() {
        assertTrue(schema(16).exists(), "exported schema missing: ${schema(16).absolutePath}")
        val json = schema(16).readText()

        assertTrue(json.contains("\"version\": 16"), "schema version 16")
        assertEquals(createSqls(schema(15).readText()), createSqls(json), "no table or column changes")
    }

    @Test
    fun `migrating a v15 database moves snooze lengths that are no longer offered to 10`() =
        runTest {
            createVersion15(alarmRow("nine", 9, 1000), alarmRow("five", 5, 1001), alarmRow("fifteen", 15, 1002), alarmRow("seven", 7, 1003))

            val database = buildAppDatabase(context)
            try {
                val alarms = RoomAlarmRepository(database.alarmDao())
                val lengths =
                    listOf("nine", "five", "fifteen", "seven").map { (alarms.get(it) as Outcome.Success).value.snoozeLengthMinutes }
                assertEquals(listOf(10, 5, 15, 10), lengths)
                assertEquals(Alarm.DEFAULT_SNOOZE_LENGTH_MINUTES, lengths.first(), "the old default becomes the new one")
            } finally {
                database.close()
            }
            assertEquals(AppDatabase.SCHEMA_VERSION, userVersion())
        }

    /** An alarm row of v15 with [id], [snooze] minutes and [requestCode]. */
    private fun alarmRow(
        id: String,
        snooze: Int,
        requestCode: Int,
    ): String =
        "INSERT INTO alarm (id, time_nano_of_day, repeat_days, label, enabled, sound_ref, volume_percent, gradual_volume, " +
            "ramp_start_percent, vibration, snooze_length_minutes, grace_seconds, vibrate_in_grace, request_code, created_at, " +
            "updated_at, check_mode, motivation, offer_skip, skipped_date, motivation_mode) VALUES ('$id', 25200000000000, 0, NULL, " +
            "1, 'builtin:default', 80, 1, 20, 1, $snooze, 20, 1, $requestCode, 1, 1, 'Random', 'none', 0, NULL, 'after_up')"

    /** A v15 file built from the exported `15.json`, with [rows] inserted. */
    private fun createVersion15(vararg rows: String) {
        val database = JSONObject(schema(15).readText()).getJSONObject("database")
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
            connection.execSQL("PRAGMA user_version = 15")
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

    /** Each table's name and create SQL, in order. */
    private fun createSqls(json: String): List<Pair<String, String>> {
        val entities = JSONObject(json).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { i ->
            val entity = entities.getJSONObject(i)
            entity.getString("tableName") to entity.getString("createSql")
        }
    }

    private companion object {
        const val TABLE_NAME = "\${TABLE_NAME}"
    }
}
