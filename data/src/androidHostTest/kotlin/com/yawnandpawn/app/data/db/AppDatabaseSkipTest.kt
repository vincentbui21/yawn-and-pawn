package com.yawnandpawn.app.data.db

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import com.yawnandpawn.app.data.history.RoomSessionHistoryRepository
import com.yawnandpawn.app.testing.aSessionHistoryRow
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 7.11: `app.db` v13 adds `alarm.offer_skip` and `alarm.skipped_date` and makes `session_history.first_ring_at`
 * nullable; a v12 file migrates keeping every row. Split from [AppDatabaseFactoryTest], whose v12 file helpers it copies.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseSkipTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Host tests run in the :data project directory.
    private fun schema(version: Int) = File("schemas/com.yawnandpawn.app.data.db.AppDatabase/$version.json")

    private suspend fun <T> withDatabase(block: suspend (AppDatabase) -> T): T {
        val database = buildAppDatabase(context)
        try {
            return block(database)
        } finally {
            database.close()
        }
    }

    @Test
    fun `the exported version 13 schema adds the skip columns to alarm and makes the first ring nullable`() {
        assertTrue(schema(13).exists(), "exported schema missing: ${schema(13).absolutePath}")
        val json = schema(13).readText()

        assertTrue(json.contains("\"version\": 13"), "schema version 13")
        assertEquals(tableNames(schema(12).readText()), tableNames(json), "no table added or removed, Story 7.1's recording kept")
        val alarm = json.substringAfter("\"tableName\": \"alarm\"").substringBefore("\"tableName\"")
        assertTrue(alarm.contains("`offer_skip` INTEGER NOT NULL DEFAULT 0"), "off for every stored alarm")
        assertTrue(alarm.contains("`skipped_date` TEXT"), "nullable: no skip")
        assertTrue(alarm.contains("`motivation` TEXT NOT NULL DEFAULT 'none'"), "Story 7.2's column kept")
        val history = json.substringAfter("\"tableName\": \"session_history\"").substringBefore("\"tableName\"")
        assertTrue(history.contains("`first_ring_at` INTEGER, "), "nullable: a skipped occurrence never rang")
        assertTrue(history.contains("index_session_history_scheduled_at"), "the scheduled_at index is kept")
    }

    @Test
    fun `a v12 database migrates keeping every alarm, history row, merge, purchase and recording, then stores a skip`() =
        runTest {
            val alarm = anAlarm(id = "a", requestCode = 1000)
            val second = anAlarm(id = "b", requestCode = 1001, time = LocalTime(8, 0))
            val row = aSessionHistoryRow(sessionId = "session-1", alarmId = "a")
            val merge = SessionMergeRow("session-1", "b", Instant.parse("2027-03-03T06:01:00Z"), Instant.parse("2027-03-03T06:02:00Z"))
            createDatabase(
                version = 12,
                alarms = listOf(alarm, second),
                requestCodeMark = 1001,
                history = listOf(row, row.copy(sessionId = "session-2", snoozeCount = 2, outcome = SessionOutcome.Snoozed)),
                merges = listOf(merge),
                sql =
                    listOf(
                        "INSERT INTO purchase_record (token_hash, order_id, product_id, session_id, alarm_id, snooze_number, " +
                            "price_micros, currency, price_source, purchased_at, status, consumed_at, updated_at) VALUES " +
                            "('hash', 'GPA.1', 'snooze_tier_01', 'session-1', 'a', 1, 990000, 'USD', 'intent', 1, 'consumed', 2, 2)",
                        "INSERT INTO recording (id, name, duration_ms, file_name, created_at) VALUES " +
                            "('r1', 'Message 1', 5000, 'r1.m4a', 7)",
                    ),
            )

            withDatabase { database ->
                val alarms = RoomAlarmRepository(database.alarmDao())
                assertEquals(Outcome.Success(listOf(alarm, second)), alarms.listAll(), "off, nothing skipped")
                val history = RoomSessionHistoryRepository(database.sessionHistoryDao())
                assertEquals(Outcome.Success(row), history.find("session-1"))
                assertEquals(
                    Outcome.Success(row.copy(sessionId = "session-2", snoozeCount = 2, outcome = SessionOutcome.Snoozed)),
                    history.find("session-2"),
                )
                assertEquals(Outcome.Success(listOf(merge)), history.merges("session-1"))
                assertEquals(1, database.purchaseRecordDao().count())
                assertEquals(1, database.recordingDao().count())

                val skipped = alarm.copy(skippedDate = LocalDate(2027, 3, 4))
                assertEquals(Outcome.Success(Unit), alarms.upsert(skipped))
                assertEquals(Outcome.Success(skipped), alarms.get("a"))
                val skip =
                    row.copy(
                        sessionId = "session-3",
                        firstRingAt = null,
                        endedAt = Instant.parse("2027-03-03T20:00:00Z"),
                        checkTypes = emptyList(),
                        timeToCompleteMs = null,
                        outcome = SessionOutcome.Skipped,
                    )
                assertEquals(Outcome.Success(Unit), history.upsert(skip))
                assertEquals(Outcome.Success(skip), history.find("session-3"))
                assertEquals(Outcome.Success(Unit), history.deleteSkipped("a", skip.scheduledAt))
                assertEquals(Outcome.Success(null), history.find("session-3"))
                assertEquals(Outcome.Success(row), history.find("session-1"), "only the Skipped row goes")
            }
            assertEquals(AppDatabase.SCHEMA_VERSION, userVersion())
        }

    private fun tableNames(json: String): List<String> =
        Regex("\"tableName\": \"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()

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

    /**
     * Writes an `app.db` of schema [version] at [appDatabaseFile] as Room created it, from the exported `<version>.json`,
     * holding [alarms] and, from version 2, the request-code [requestCodeMark] (none: the table stays empty).
     */
    private fun createDatabase(
        version: Int,
        alarms: List<Alarm> = emptyList(),
        requestCodeMark: Int? = null,
        history: List<SessionHistoryRow> = emptyList(),
        merges: List<SessionMergeRow> = emptyList(),
        sql: List<String> = emptyList(),
    ) {
        val database = JSONObject(schema(version).readText()).getJSONObject("database")
        val file = appDatabaseFile(context).apply { parentFile?.mkdirs() }
        val connection = AndroidSQLiteDriver().open(file.absolutePath)
        try {
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                connection.execSQL(entity.getString("createSql").replace(TABLE_NAME, table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) {
                    for (j in 0 until indices.length()) {
                        connection.execSQL(indices.getJSONObject(j).getString("createSql").replace(TABLE_NAME, table))
                    }
                }
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) connection.execSQL(setup.getString(i))
            // The alarm table is the same in v1 and v2.
            alarms.forEach { connection.execSQL(it.toVersion1Insert()) }
            requestCodeMark?.let { connection.execSQL("INSERT INTO request_code_sequence (id, last_used) VALUES (0, $it)") }
            // A v3 session_history row as Story 1.13 writes it.
            history.forEach { connection.execSQL(it.toVersion3Insert()) }
            // A v4 session_merge row as Story 2.9 writes it.
            merges.forEach {
                connection.execSQL(
                    "INSERT INTO session_merge (session_id, alarm_id, scheduled_at, merged_at) VALUES (" +
                        "'${it.sessionId}', '${it.alarmId}', " +
                        "${it.scheduledAt.toEpochMilliseconds()}, ${it.mergedAt.toEpochMilliseconds()})",
                )
            }
            // Rows of later tables, in the SQL of [version] (for example a v7 `check_config` row).
            sql.forEach(connection::execSQL)
            connection.execSQL("PRAGMA user_version = $version")
        } finally {
            connection.close()
        }
    }

    /** A v3 `session_history` row with the stored names Story 1.13 uses ("OnTime", comma-separated check types). */
    private fun SessionHistoryRow.toVersion3Insert(): String =
        "INSERT INTO session_history (session_id, alarm_id, scheduled_at, first_ring_at, ended_at, snooze_count, check_types, " +
            "time_to_complete_ms, fallback_used, direct_boot, outcome) VALUES ('$sessionId', '$alarmId', " +
            "${scheduledAt.toEpochMilliseconds()}, ${firstRingAt?.toEpochMilliseconds()}, ${endedAt?.toEpochMilliseconds()}, " +
            "$snoozeCount, '${checkTypes.joinToString(",")}', $timeToCompleteMs, ${if (fallbackUsed) 1 else 0}, " +
            "${if (directBoot) 1 else 0}, ${outcome?.let { "'${it.name}'" } ?: "NULL"})"

    /** A v1 `alarm` row for a one-time, enabled, labelless alarm with gradual volume and vibration on (the builder defaults). */
    private fun Alarm.toVersion1Insert(): String =
        "INSERT INTO alarm (id, time_nano_of_day, repeat_days, label, enabled, sound_ref, volume_percent, gradual_volume, " +
            "ramp_start_percent, vibration, snooze_length_minutes, grace_seconds, request_code, created_at, updated_at) VALUES (" +
            "'$id', ${time.toNanosecondOfDay()}, 0, NULL, 1, '$soundRef', $volumePercent, 1, $rampStartPercent, " +
            "${if (vibration) 1 else 0}, " +
            "$snoozeLengthMinutes, $graceSeconds, $requestCode, ${createdAt.toEpochMilliseconds()}, ${updatedAt.toEpochMilliseconds()})"

    private companion object {
        const val TABLE_NAME = "\${TABLE_NAME}"
    }
}
