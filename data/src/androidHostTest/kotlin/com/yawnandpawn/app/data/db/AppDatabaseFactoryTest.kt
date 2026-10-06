package com.yawnandpawn.app.data.db

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.orderedEntries
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import com.yawnandpawn.app.data.alarm.RoomCheckConfigRepository
import com.yawnandpawn.app.data.alarm.RoomRequestCodeSequence
import com.yawnandpawn.app.data.history.RoomSessionHistoryRepository
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.aSessionHistoryRow
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class AppDatabaseFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Host tests run in the :data project directory.
    private fun schema(version: Int) = File("schemas/com.yawnandpawn.app.data.db.AppDatabase/$version.json")

    private suspend fun <T> withDatabase(
        context: Context = this.context,
        block: suspend (AppDatabase) -> T,
    ): T {
        val database = buildAppDatabase(context)
        try {
            return block(database)
        } finally {
            database.close()
        }
    }

    private fun fixture(database: AppDatabase) =
        AlarmUseCasesFixture(
            repository = RoomAlarmRepository(database.alarmDao()),
            requestCodes = RoomRequestCodeSequence(database.requestCodeSequenceDao()),
            checkConfigs = RoomCheckConfigRepository(database.checkConfigDao()),
        )

    @Test
    fun `app db lives in device-protected storage, not in credential-protected storage`() {
        val file = appDatabaseFile(context)
        val deviceProtected = context.createDeviceProtectedStorageContext().getDatabasePath("app.db")

        assertEquals(deviceProtected.absolutePath, file.absolutePath)
        assertNotEquals(context.getDatabasePath("app.db").absolutePath, file.absolutePath)
    }

    @Test
    fun `the built database writes to the device-protected file in rollback-journal mode`() =
        runTest {
            val file = appDatabaseFile(context)
            withDatabase { database ->
                RoomAlarmRepository(database.alarmDao()).upsert(anAlarm())

                assertTrue(file.exists(), "expected $file")
                assertFalse(context.getDatabasePath("app.db").exists(), "nothing in credential-protected storage")
                assertFalse(File(file.path + "-wal").exists(), "TRUNCATE journal mode keeps all data in app.db for backup")
            }
        }

    @Test
    fun `the exported version 1 schema is kept with only the alarm table and a unique request code`() {
        val json = schema(1).readText()

        assertTrue(json.contains("\"version\": 1"), "schema version 1")
        assertEquals(listOf("alarm"), tableNames(json))
        assertTrue(json.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_alarm_request_code`"), "unique index on request_code")
    }

    @Test
    fun `the exported version 2 schema is committed with the alarm and request code sequence tables`() {
        assertTrue(schema(2).exists(), "exported schema missing: ${schema(2).absolutePath}")
        val json = schema(2).readText()

        assertTrue(json.contains("\"version\": 2"), "schema version 2")
        assertEquals(listOf("alarm", "request_code_sequence"), tableNames(json))
        assertTrue(json.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_alarm_request_code`"), "unique index on request_code")
    }

    @Test
    fun `the exported version 3 schema is committed with the alarm, request code sequence and session history tables`() {
        assertTrue(schema(3).exists(), "exported schema missing: ${schema(3).absolutePath}")
        val json = schema(3).readText()

        assertTrue(json.contains("\"version\": 3"), "schema version 3")
        assertEquals(listOf("alarm", "request_code_sequence", "session_history"), tableNames(json))
        assertTrue(json.contains("PRIMARY KEY(`session_id`)"), "one row per session")
        assertTrue(json.contains("CREATE INDEX IF NOT EXISTS `index_session_history_scheduled_at`"), "indexed by date for Progress")
        assertFalse(json.contains("price") || json.contains("micros"), "no paid amounts in history")
    }

    @Test
    fun `migrating a v2 database keeps every alarm and the request code mark and adds an empty session history`() =
        runTest {
            val first = anAlarm(id = "a", requestCode = 1000)
            val second = anAlarm(id = "b", time = LocalTime(8, 0), requestCode = 1003)
            // Codes up to 1005 were handed out; the alarms holding 1004 and 1005 were deleted.
            createDatabase(version = 2, alarms = listOf(first, second), requestCodeMark = 1005)

            withDatabase { database ->
                assertEquals(Outcome.Success(listOf(first, second)), RoomAlarmRepository(database.alarmDao()).listAll())
                assertEquals(0, database.sessionHistoryDao().count())
                assertEquals(Outcome.Success(1006), RoomRequestCodeSequence(database.requestCodeSequenceDao()).next())
                val history = RoomSessionHistoryRepository(database.sessionHistoryDao())
                val row = aSessionHistoryRow()
                assertEquals(Outcome.Success(Unit), history.upsert(row))
                assertEquals(Outcome.Success(row), history.find(row.sessionId))
            }
        }

    @Test
    fun `the exported version 4 schema adds the session merge log keyed by session, alarm and occurrence`() {
        assertTrue(schema(4).exists(), "exported schema missing: ${schema(4).absolutePath}")
        val json = schema(4).readText()

        assertTrue(json.contains("\"version\": 4"), "schema version 4")
        assertEquals(listOf("alarm", "request_code_sequence", "session_history", "session_merge"), tableNames(json))
        assertTrue(json.contains("PRIMARY KEY(`session_id`, `alarm_id`, `scheduled_at`)"), "one row per merged occurrence")
    }

    @Test
    fun `migrating a v3 database keeps alarms, the code mark and history, and adds an empty merge log`() =
        runTest {
            val alarm = anAlarm(id = "a", requestCode = 1000)
            val row = aSessionHistoryRow()
            createDatabase(version = 3, alarms = listOf(alarm), requestCodeMark = 1002, history = listOf(row))

            withDatabase { database ->
                assertEquals(Outcome.Success(listOf(alarm)), RoomAlarmRepository(database.alarmDao()).listAll())
                assertEquals(Outcome.Success(1003), RoomRequestCodeSequence(database.requestCodeSequenceDao()).next())
                val history = RoomSessionHistoryRepository(database.sessionHistoryDao())
                assertEquals(Outcome.Success(row), history.find(row.sessionId))
                assertEquals(Outcome.Success(emptyList()), history.merges(row.sessionId))
                val merge = SessionMergeRow(row.sessionId, "b", Instant.fromEpochMilliseconds(5_000), Instant.fromEpochMilliseconds(6_000))
                assertEquals(Outcome.Success(Unit), history.recordMerge(merge))
                assertEquals(Outcome.Success(listOf(merge)), history.merges(row.sessionId))
            }
            assertEquals(AppDatabase.SCHEMA_VERSION, userVersion())
        }

    @Test
    fun `the exported version 5 schema adds the alarm's quiet-time vibration, on by default`() {
        assertTrue(schema(5).exists(), "exported schema missing: ${schema(5).absolutePath}")
        val json = schema(5).readText()

        assertTrue(json.contains("\"version\": 5"), "schema version 5")
        assertEquals(listOf("alarm", "request_code_sequence", "session_history", "session_merge"), tableNames(json))
        assertTrue(json.contains("`vibrate_in_grace` INTEGER NOT NULL DEFAULT 1"), "the new alarm column, on by default")
    }

    @Test
    fun `migrating a v4 database keeps alarms, the code mark, history and merges, and quiet-time vibration follows vibration`() =
        runTest {
            val alarm = anAlarm(id = "a", requestCode = 1000)
            val silent = anAlarm(id = "b", requestCode = 1001, time = LocalTime(8, 0)).copy(vibration = false)
            val row = aSessionHistoryRow()
            val merge = SessionMergeRow(row.sessionId, "b", Instant.fromEpochMilliseconds(5_000), Instant.fromEpochMilliseconds(6_000))
            createDatabase(
                version = 4,
                // The v4 rows have no quiet-time column: the Kotlin value below is not written.
                alarms = listOf(alarm.copy(vibrateInGrace = false), silent),
                requestCodeMark = 1002,
                history = listOf(row),
                merges = listOf(merge),
            )

            withDatabase { database ->
                val repository = RoomAlarmRepository(database.alarmDao())
                assertEquals(
                    Outcome.Success(listOf(alarm.copy(vibrateInGrace = true), silent.copy(vibrateInGrace = false))),
                    repository.listAll(),
                    "on for a vibrating alarm, off for one that never vibrates",
                )
                assertEquals(Outcome.Success(1003), RoomRequestCodeSequence(database.requestCodeSequenceDao()).next())
                val history = RoomSessionHistoryRepository(database.sessionHistoryDao())
                assertEquals(Outcome.Success(row), history.find(row.sessionId))
                assertEquals(Outcome.Success(listOf(merge)), history.merges(row.sessionId), "the merge log is kept")
                assertEquals(Outcome.Success(Unit), repository.delete(silent.id))
                // The new column is stored and read back per alarm.
                assertEquals(Outcome.Success(Unit), repository.upsert(alarm.copy(vibrateInGrace = false)))
                assertEquals(Outcome.Success(listOf(alarm.copy(vibrateInGrace = false))), repository.listAll())
            }
            assertEquals(AppDatabase.SCHEMA_VERSION, userVersion())
        }

    @Test
    fun `the exported version 6 schema adds check config with a cascading alarm key and the alarm check mode`() {
        assertTrue(schema(6).exists(), "exported schema missing: ${schema(6).absolutePath}")
        val json = schema(6).readText()

        assertTrue(json.contains("\"version\": 6"), "schema version 6")
        assertEquals(listOf("alarm", "request_code_sequence", "session_history", "session_merge", "check_config"), tableNames(json))
        assertTrue(json.contains("REFERENCES `alarm`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE"), "rows go with their alarm")
        assertTrue(json.contains("`check_mode` TEXT NOT NULL DEFAULT 'Random'"), "check mode, Random by default")
        assertTrue(json.contains("`vibrate_in_grace` INTEGER NOT NULL DEFAULT 1"), "Story 3.4's column is kept")
    }

    @Test
    fun `migrating a v5 database gives every alarm Random and one Math Medium 3 check, keeping everything else (Story 3-5)`() =
        runTest {
            val first = anAlarm(id = "a", requestCode = 1000)
            val second = anAlarm(id = "b", requestCode = 1001, time = LocalTime(8, 0))
            val row = aSessionHistoryRow()
            createDatabase(version = 5, alarms = listOf(first, second), requestCodeMark = 1001, history = listOf(row))

            withDatabase { database ->
                assertEquals(Outcome.Success(listOf(first, second)), RoomAlarmRepository(database.alarmDao()).listAll())
                val checks = RoomCheckConfigRepository(database.checkConfigDao())
                listOf(first, second).forEach { alarm ->
                    val configs = assertIs<Outcome.Success<List<CheckConfig>>>(checks.forAlarm(alarm.id)).value
                    assertEquals(listOf(CheckEntry(CheckType.Math, Difficulty.Medium, 3)), configs.orderedEntries(), alarm.id)
                    assertEquals(CheckConfig.idFor(alarm.id, CheckType.Math), configs.single().id)
                    assertEquals(0, configs.single().position)
                }
                assertEquals(
                    CheckMode.Random,
                    assertIs<Outcome.Success<Alarm>>(RoomAlarmRepository(database.alarmDao()).get("a")).value.checkMode,
                )
                assertEquals(Outcome.Success(row), RoomSessionHistoryRepository(database.sessionHistoryDao()).find(row.sessionId))
                assertEquals(Outcome.Success(1002), RoomRequestCodeSequence(database.requestCodeSequenceDao()).next())
            }
            assertEquals(AppDatabase.SCHEMA_VERSION, userVersion())
        }

    @Test
    fun `a v1 database migrates through every version to the current one`() =
        runTest {
            val alarm = anAlarm(id = "a", requestCode = 1005)
            createDatabase(version = 1, alarms = listOf(alarm))

            withDatabase { database ->
                assertEquals(Outcome.Success(listOf(alarm)), RoomAlarmRepository(database.alarmDao()).listAll())
                assertEquals(0, database.sessionHistoryDao().count())
                assertEquals(Outcome.Success(1006), RoomRequestCodeSequence(database.requestCodeSequenceDao()).next())
            }
            assertEquals(AppDatabase.SCHEMA_VERSION, userVersion())
        }

    @Test
    fun `a new database is created at the current version with an empty session history and merge log`() =
        runTest {
            withDatabase { database -> assertEquals(0, database.sessionHistoryDao().count()) }

            assertEquals(AppDatabase.SCHEMA_VERSION, userVersion())
        }

    @Test
    fun `the migrations cover every version step and nothing is destructive`() {
        assertEquals(
            listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6),
            APP_DATABASE_MIGRATIONS.map { it.startVersion to it.endVersion },
        )
        assertEquals(6, AppDatabase.SCHEMA_VERSION)
    }

    @Test
    fun `migrating a v1 database keeps every alarm and the next code is above the highest in use`() =
        runTest {
            val first = anAlarm(id = "a", requestCode = 1000)
            val second = anAlarm(id = "b", time = LocalTime(8, 0), requestCode = 1005)
            createDatabase(version = 1, alarms = listOf(first, second))

            withDatabase { database ->
                assertEquals(Outcome.Success(listOf(first, second)), RoomAlarmRepository(database.alarmDao()).listAll())
                assertEquals(
                    1006,
                    assertIs<Outcome.Success<Alarm>>(fixture(database).save(AlarmDraft(time = LocalTime(9, 0)))).value.requestCode,
                )
            }
        }

    @Test
    fun `migrating an empty v1 database starts the codes at 1000`() =
        runTest {
            createDatabase(version = 1)

            withDatabase { database ->
                assertEquals(Outcome.Success(1000), RoomRequestCodeSequence(database.requestCodeSequenceDao()).next())
            }
        }

    @Test
    fun `a deleted alarm's code is never reused, also after the database is rebuilt`() =
        runTest {
            val draft = AlarmDraft(time = LocalTime(7, 0))
            withDatabase { database ->
                val alarms = fixture(database)
                assertEquals(1000, assertIs<Outcome.Success<Alarm>>(alarms.save(draft)).value.requestCode)
                // Other times: a new alarm identical to a stored one would switch that one on instead (2026-10-05).
                val second = assertIs<Outcome.Success<Alarm>>(alarms.save(draft.copy(time = LocalTime(8, 0)))).value
                assertEquals(1001, second.requestCode)
                alarms.delete(second.id)
                val third = assertIs<Outcome.Success<Alarm>>(alarms.save(draft.copy(time = LocalTime(9, 0)))).value
                assertEquals(1002, third.requestCode)
                alarms.delete(third.id)
            }

            // The app restarts: a new database instance over the same file.
            withDatabase { database ->
                val fourth = fixture(database).save(draft.copy(time = LocalTime(10, 0)))
                assertEquals(1003, assertIs<Outcome.Success<Alarm>>(fourth).value.requestCode)
            }
        }

    @Test
    fun `app db opens, reads and writes while credential-encrypted storage is locked`() =
        runTest {
            val locked = LockedCredentialStorageContext(context)

            withDatabase(locked) { database ->
                val repository = RoomAlarmRepository(database.alarmDao())
                val alarm = anAlarm()

                assertEquals(Outcome.Success(Unit), repository.upsert(alarm))
                assertEquals(Outcome.Success(listOf(alarm)), repository.listAll())
                assertEquals(Outcome.Success(1001), RoomRequestCodeSequence(database.requestCodeSequenceDao()).next())
            }
            assertTrue(appDatabaseFile(context).exists(), "written to device-protected storage")
            assertFalse(context.getDatabasePath("app.db").exists(), "nothing in credential-protected storage")
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
            connection.execSQL("PRAGMA user_version = $version")
        } finally {
            connection.close()
        }
    }

    /** A v3 `session_history` row with the stored names Story 1.13 uses ("OnTime", comma-separated check types). */
    private fun SessionHistoryRow.toVersion3Insert(): String =
        "INSERT INTO session_history (session_id, alarm_id, scheduled_at, first_ring_at, ended_at, snooze_count, check_types, " +
            "time_to_complete_ms, fallback_used, direct_boot, outcome) VALUES ('$sessionId', '$alarmId', " +
            "${scheduledAt.toEpochMilliseconds()}, ${firstRingAt.toEpochMilliseconds()}, ${endedAt?.toEpochMilliseconds()}, " +
            "$snoozeCount, '${checkTypes.joinToString(",")}', $timeToCompleteMs, ${if (fallbackUsed) 1 else 0}, " +
            "${if (directBoot) 1 else 0}, ${outcome?.let { "'${it.name}'" } ?: "NULL"})"

    /** A v1 `alarm` row for a one-time, enabled, labelless alarm with gradual volume and vibration on (the builder defaults). */
    private fun Alarm.toVersion1Insert(): String =
        "INSERT INTO alarm (id, time_nano_of_day, repeat_days, label, enabled, sound_ref, volume_percent, gradual_volume, " +
            "ramp_start_percent, vibration, snooze_length_minutes, grace_seconds, request_code, created_at, updated_at) VALUES (" +
            "'$id', ${time.toNanosecondOfDay()}, 0, NULL, 1, '$soundRef', $volumePercent, 1, $rampStartPercent, " +
            "${if (vibration) 1 else 0}, " +
            "$snoozeLengthMinutes, $graceSeconds, $requestCode, ${createdAt.toEpochMilliseconds()}, ${updatedAt.toEpochMilliseconds()})"

    /**
     * Before the first unlock: every credential-encrypted storage access throws. Only the device-protected context it
     * creates works, and that context's application context is this locked one again, as on a real device.
     */
    private class LockedCredentialStorageContext(
        base: Context,
    ) : ContextWrapper(base) {
        private fun locked(): Nothing = throw IllegalStateException("credential-encrypted storage is locked")

        override fun getApplicationContext(): Context = this

        override fun createDeviceProtectedStorageContext(): Context =
            object : ContextWrapper(baseContext.createDeviceProtectedStorageContext()) {
                override fun getApplicationContext(): Context = this@LockedCredentialStorageContext
            }

        override fun getDatabasePath(name: String?): File = locked()

        override fun getFilesDir(): File = locked()

        override fun getNoBackupFilesDir(): File = locked()

        override fun getCacheDir(): File = locked()

        override fun getDataDir(): File = locked()

        override fun getDir(
            name: String?,
            mode: Int,
        ): File = locked()

        override fun databaseList(): Array<String> = locked()

        override fun deleteDatabase(name: String?): Boolean = locked()

        override fun getSharedPreferences(
            name: String?,
            mode: Int,
        ): SharedPreferences = locked()

        override fun openOrCreateDatabase(
            name: String?,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = locked()

        override fun openOrCreateDatabase(
            name: String?,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = locked()
    }

    private companion object {
        const val TABLE_NAME = "\${TABLE_NAME}"
    }
}
