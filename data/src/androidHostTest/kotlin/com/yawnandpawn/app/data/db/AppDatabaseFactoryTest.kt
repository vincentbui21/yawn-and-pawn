package com.yawnandpawn.app.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AppDatabaseFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

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
            val database = buildAppDatabase(context)
            try {
                RoomAlarmRepository(database.alarmDao()).upsert(anAlarm())

                assertTrue(file.exists(), "expected $file")
                assertFalse(context.getDatabasePath("app.db").exists(), "nothing in credential-protected storage")
                assertFalse(File(file.path + "-wal").exists(), "TRUNCATE journal mode keeps all data in app.db for backup")
            } finally {
                database.close()
            }
        }

    @Test
    fun `the exported version 1 schema is committed with only the alarm table and a unique request code`() {
        // Host tests run in the :data project directory.
        val schema = File("schemas/com.yawnandpawn.app.data.db.AppDatabase/1.json")
        assertTrue(schema.exists(), "exported schema missing: ${schema.absolutePath}")
        val json = schema.readText()

        assertTrue(json.contains("\"version\": 1"), "schema version 1")
        assertEquals(listOf("alarm"), Regex("\"tableName\": \"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList())
        assertTrue(json.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_alarm_request_code`"), "unique index on request_code")
    }
}
