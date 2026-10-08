package com.yawnandpawn.app.data.db

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.RuntimeWrite
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.data.session.RoomActiveSessionStore
import com.yawnandpawn.app.data.session.RoomPurchaseIntentStore
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.aPurchaseIntent
import com.yawnandpawn.app.testing.aSession
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
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
    private val schema = schema(1)

    private fun schema(version: Int) = File("schemas/com.yawnandpawn.app.data.db.RuntimeDatabase/$version.json")

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

    @Test
    fun `the exported version 2 schema adds purchase_intent keyed by intent id with the price as micros and currency`() {
        val json = schema(2).readText()

        assertTrue(json.contains("\"version\": 2"), "schema version 2")
        assertEquals(listOf("active_session", "purchase_intent"), tableNames(json))
        assertTrue(json.contains("PRIMARY KEY(`intent_id`)"), "intent_id is the primary key")
        listOf("session_id", "product_id", "snooze_number", "price_micros", "currency", "formatted_price", "created_at").forEach {
            assertTrue(json.contains("`$it`"), it)
        }
        assertEquals(2, RuntimeDatabase.SCHEMA_VERSION)
    }

    @Test
    fun `the migrations cover every version step and nothing is destructive`() {
        assertEquals(listOf(1 to 2), RUNTIME_DATABASE_MIGRATIONS.map { it.startVersion to it.endVersion })
        assertEquals(RuntimeDatabase.SCHEMA_VERSION, RUNTIME_DATABASE_MIGRATIONS.last().endVersion)
    }

    @Test
    fun `migrating a v1 database keeps the active session and adds an empty, working intent table`() =
        runTest {
            // A session ringing through the app update (Story 4.8): v1 as Story 1.12 wrote it.
            val ringing = SessionState.Ringing(aSession(sessionId = "s"))
            createVersion1(ringing)

            val database = buildRuntimeDatabase(context)
            try {
                val sessions = RoomActiveSessionStore(database.activeSessionDao(), FakeClock())
                assertEquals(Outcome.Success(StoredSession.Found(ringing)), sessions.load(), "the session survives the migration")
                assertEquals(0, database.purchaseIntentDao().count())

                val intent = aPurchaseIntent(sessionId = "s")
                assertEquals(Outcome.Success(Unit), sessions.commit(ringing, listOf(RuntimeWrite.PutPurchaseIntent(intent))))
                assertEquals(Outcome.Success(intent), RoomPurchaseIntentStore(database.purchaseIntentDao()).get(intent.intentId))
            } finally {
                database.close()
            }
            assertEquals(2, userVersion())
        }

    private fun tableNames(json: String): List<String> =
        Regex("\"tableName\": \"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()

    /** Writes a v1 `runtime.db` from the exported `1.json`, holding [state] as its one `active_session` row. */
    private fun createVersion1(state: SessionState.Active) {
        val database = JSONObject(schema(1).readText()).getJSONObject("database")
        val file = runtimeDatabaseFile(context).apply { parentFile?.mkdirs() }
        val connection = AndroidSQLiteDriver().open(file.absolutePath)
        try {
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                connection.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) connection.execSQL(setup.getString(i))
            val json = SessionJson.encode(state).replace("'", "''")
            connection.execSQL(
                "INSERT INTO active_session (session_id, state_json, updated_at) VALUES ('${state.session.sessionId}', '$json', 1)",
            )
            connection.execSQL("PRAGMA user_version = 1")
        } finally {
            connection.close()
        }
    }

    private fun userVersion(): Int {
        val connection = AndroidSQLiteDriver().open(runtimeDatabaseFile(context).absolutePath)
        try {
            return connection.prepare("PRAGMA user_version").use { statement ->
                statement.step()
                statement.getInt(0)
            }
        } finally {
            connection.close()
        }
    }
}
