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
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import com.yawnandpawn.app.data.alarm.RoomRequestCodeSequence
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
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
    fun `migrating a v1 database keeps every alarm and the next code is above the highest in use`() =
        runTest {
            val first = anAlarm(id = "a", requestCode = 1000)
            val second = anAlarm(id = "b", time = LocalTime(8, 0), requestCode = 1005)
            createVersion1Database(first, second)

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
            createVersion1Database()

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
                val second = assertIs<Outcome.Success<Alarm>>(alarms.save(draft)).value
                assertEquals(1001, second.requestCode)
                alarms.delete(second.id)
                val third = assertIs<Outcome.Success<Alarm>>(alarms.save(draft)).value
                assertEquals(1002, third.requestCode)
                alarms.delete(third.id)
            }

            // The app restarts: a new database instance over the same file.
            withDatabase { database ->
                assertEquals(1003, assertIs<Outcome.Success<Alarm>>(fixture(database).save(draft)).value.requestCode)
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

    /** Writes a v1 `app.db` at [appDatabaseFile] as Room created it, from the exported `1.json`, holding [alarms]. */
    private fun createVersion1Database(vararg alarms: Alarm) {
        val database = JSONObject(schema(1).readText()).getJSONObject("database")
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
            alarms.forEach { connection.execSQL(it.toVersion1Insert()) }
            connection.execSQL("PRAGMA user_version = 1")
        } finally {
            connection.close()
        }
    }

    /** A v1 `alarm` row for a one-time, enabled, labelless alarm with gradual volume and vibration on (the builder defaults). */
    private fun Alarm.toVersion1Insert(): String =
        "INSERT INTO alarm (id, time_nano_of_day, repeat_days, label, enabled, sound_ref, volume_percent, gradual_volume, " +
            "ramp_start_percent, vibration, snooze_length_minutes, grace_seconds, request_code, created_at, updated_at) VALUES (" +
            "'$id', ${time.toNanosecondOfDay()}, 0, NULL, 1, '$soundRef', $volumePercent, 1, $rampStartPercent, 1, " +
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
