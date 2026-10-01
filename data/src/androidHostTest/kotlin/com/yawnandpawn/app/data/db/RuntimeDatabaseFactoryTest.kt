package com.yawnandpawn.app.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.data.session.RoomActiveSessionStore
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.aSession
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
class RuntimeDatabaseFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Host tests run in the :data project directory.
    private val schema = File("schemas/com.yawnandpawn.app.data.db.RuntimeDatabase/1.json")

    @Test
    fun `runtime db lives in device-protected storage next to app db, not in credential-protected storage`() {
        val file = runtimeDatabaseFile(context)

        assertEquals(context.createDeviceProtectedStorageContext().getDatabasePath("runtime.db").absolutePath, file.absolutePath)
        assertNotEquals(context.getDatabasePath("runtime.db").absolutePath, file.absolutePath)
        assertEquals(appDatabaseFile(context).parentFile, file.parentFile)
    }

    @Test
    fun `a committed session survives closing and reopening the database file`() =
        runTest {
            val ringing = SessionState.Ringing(aSession())
            val first = buildRuntimeDatabase(context)
            try {
                assertEquals(Outcome.Success(Unit), RoomActiveSessionStore(first.activeSessionDao(), FakeClock()).commit(ringing))
                assertTrue(runtimeDatabaseFile(context).exists())
                assertFalse(context.getDatabasePath("runtime.db").exists(), "nothing in credential-protected storage")
                assertFalse(File(runtimeDatabaseFile(context).path + "-wal").exists(), "rollback journal, no WAL")
            } finally {
                first.close()
            }

            val reopened = buildRuntimeDatabase(context)
            try {
                assertEquals(
                    Outcome.Success(StoredSession.Found(ringing)),
                    RoomActiveSessionStore(reopened.activeSessionDao(), FakeClock()).load(),
                )
            } finally {
                reopened.close()
            }
        }

    @Test
    fun `the exported version 1 schema is committed with only the active session table`() {
        assertTrue(schema.exists(), "exported schema missing: ${schema.absolutePath}")
        val json = schema.readText()

        assertTrue(json.contains("\"version\": 1"), "schema version 1")
        assertEquals(listOf("active_session"), Regex("\"tableName\": \"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList())
        assertTrue(json.contains("PRIMARY KEY(`session_id`)"), "session_id is the primary key")
        listOf("state_json", "updated_at").forEach { assertTrue(json.contains("`$it`"), it) }
    }
}
