package com.yawnandpawn.app.android.backup

import android.app.AlarmManager
import android.app.backup.BackupAgent
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.AndroidLogger
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.billing.GrantLedgerStore
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.RuntimeWrite
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.appDatabaseFile
import com.yawnandpawn.app.data.db.runtimeDatabaseFile
import com.yawnandpawn.app.data.settings.SettingsDataStore
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.stopApp
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.aGrant
import com.yawnandpawn.app.testing.aPurchaseRecord
import com.yawnandpawn.app.testing.aSessionHistoryRow
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 2.12: [PpsBackupAgent] restores `app.db` only when this build can open it (the downgrade policy,
 * `docs/decisions/db-downgrade.md`), and re-arms the restored alarms when the restore finishes, with or without a
 * running app. The session (`runtime.db`) is never part of a restore, so the app then starts Idle.
 */
@RunWith(RobolectricTestRunner::class)
class PpsBackupAgentTest {
    @get:Rule(order = 0)
    val stopAppRule = StopAppRule()

    @get:Rule(order = 1)
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = FakeAlarmScheduler()
    private val alarm = anAlarm(id = "alarm-a", repeatDays = DayOfWeek.entries.toSet(), requestCode = 1000)
    private val history = aSessionHistoryRow(alarmId = "alarm-a")

    // Story 4.10: purchase history is user data, backed up in app.db by token hash; the grant ledger (runtime.db) is not.
    private val purchase = aPurchaseRecord(status = RecordStatus.Consumed, sessionId = history.sessionId, alarmId = "alarm-a")

    @Before
    fun setUp() {
        GlobalContext.get().get<ApplicationScope>().awaitChildren()
        ShadowLog.clear()
    }

    private fun agent(): PpsBackupAgent = Robolectric.buildBackupAgent(PpsBackupAgent::class.java).create().get()

    /** Offers [bytes] to the agent as the restored `app.db`, through a file descriptor like the restore pipe. */
    private fun restoreAppDb(
        bytes: ByteArray,
        size: Long = bytes.size.toLong(),
    ) {
        val source = temp.newFile().apply { writeBytes(bytes) }
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { data ->
            agent().onRestoreFile(data, size, appDatabaseFile(context), BackupAgent.TYPE_FILE, MODE, 0L)
        }
    }

    /**
     * The bytes of a current-schema `app.db` holding [alarm], [history] and the [purchase] record, made by the app itself;
     * the app is stopped after.
     */
    private fun backedUpAppDb(): ByteArray {
        val koin = GlobalContext.get()
        runBlocking {
            assertEquals(Outcome.Success(Unit), koin.get<AlarmRepository>().upsert(alarm))
            assertEquals(Outcome.Success(Unit), koin.get<SessionHistoryRepository>().upsert(history))
            assertEquals(Outcome.Success(Unit), koin.get<PurchaseRecordRepository>().putRecord(purchase))
        }
        stopApp()
        return appDatabaseFile(context).readBytes()
    }

    /** A new phone: no `app.db`, no `runtime.db`, nothing running. */
    private fun freshInstall() {
        stopApp()
        val databases = appDatabaseFile(context).parentFile!!
        databases.listFiles()?.forEach { it.delete() }
    }

    private fun startApp(): Koin {
        restartKoin(context as android.app.Application, module { single<AlarmScheduler> { scheduler } })
        return GlobalContext.get()
    }

    private fun logs(): List<String> = ShadowLog.getLogsForTag(AndroidLogger.TAG).map { it.msg }

    private fun withUserVersion(
        bytes: ByteArray,
        version: Int,
    ): ByteArray =
        bytes.copyOf().apply {
            this[USER_VERSION_OFFSET] = (version ushr 24).toByte()
            this[USER_VERSION_OFFSET + 1] = (version ushr 16).toByte()
            this[USER_VERSION_OFFSET + 2] = (version ushr 8).toByte()
            this[USER_VERSION_OFFSET + 3] = version.toByte()
        }

    @Test
    fun `a backup of the same schema is restored, its alarms are armed when the restore finishes and the app starts Idle`() {
        // A payment still in the grant ledger when the backup was made: runtime.db (and the raw token) never comes along.
        val pending = aGrant(token = "raw-token-on-the-old-phone")
        runBlocking { GlobalContext.get().get<ActiveSessionStore>().commit(SessionState.Idle, listOf(RuntimeWrite.PutGrant(pending))) }
        val backup = backedUpAppDb()
        freshInstall()

        restoreAppDb(backup)
        val koin = startApp()
        agent().onRestoreFinished()

        assertContentEquals(backup, appDatabaseFile(context).readBytes(), "app.db is the restored file")
        assertTrue(scheduler.armed.containsKey(alarm.requestCode), "the restored alarm is armed without opening the app")
        assertFalse(runtimeDatabaseFile(context).exists(), "no session comes with a restore")
        runBlocking {
            assertEquals(Outcome.Success(listOf(alarm)), koin.get<AlarmRepository>().listAll())
            assertEquals(Outcome.Success(history), koin.get<SessionHistoryRepository>().find(history.sessionId), "history unchanged")
            assertEquals(Outcome.Success(listOf(purchase)), koin.get<PurchaseRecordRepository>().all(), "every charge is restored")
            assertEquals(Outcome.Success(emptyList()), koin.get<GrantLedgerStore>().all(), "no grant ledger, no raw token comes with it")
            val engine = koin.get<SessionEngine>()
            engine.restore()
            assertEquals(SessionState.Idle, engine.state.value)
        }
        assertNull(SkippedRestoreNotice(context).skippedSchema)
        assertFalse(incoming().exists(), "the incoming copy is gone")
    }

    @Test
    fun `a backup of an older schema is restored and migrates when the app opens it`() {
        freshInstall()

        restoreAppDb(version2AppDb())

        assertEquals(2, AppDatabaseRestoreGuard.userVersion(appDatabaseFile(context)))
        val koin = startApp()
        runBlocking { assertEquals(listOf("alarm-old"), (koin.get<AlarmRepository>().listAll() as Outcome.Success).value.map { it.id }) }
        stopApp()
        assertEquals(AppDatabase.SCHEMA_VERSION, AppDatabaseRestoreGuard.userVersion(appDatabaseFile(context)))
        assertNull(SkippedRestoreNotice(context).skippedSchema)
    }

    @Test
    fun `a backup of a newer schema is skipped, the current app db stays, it is logged once and the notice is set`() {
        val current = backedUpAppDb()
        val newer = withUserVersion(current, AppDatabase.SCHEMA_VERSION + 1)

        restoreAppDb(newer)

        assertContentEquals(current, appDatabaseFile(context).readBytes(), "the current app.db is unchanged")
        assertEquals(
            listOf(
                "OperationFailed operation=restore app.db cause=" +
                    "schema ${AppDatabase.SCHEMA_VERSION + 1} is newer than ${AppDatabase.SCHEMA_VERSION}",
            ),
            logs().filter { it.startsWith("OperationFailed") },
        )
        assertEquals(AppDatabase.SCHEMA_VERSION + 1, SkippedRestoreNotice(context).skippedSchema)
        assertFalse(incoming().exists(), "the newer file is dropped")
        val koin = startApp()
        runBlocking { assertEquals(Outcome.Success(listOf(alarm)), koin.get<AlarmRepository>().listAll()) }
    }

    @Test
    fun `a restored app db that is not a database is skipped without a notice`() {
        val current = backedUpAppDb()

        restoreAppDb("not a database at all, but long enough to have a header of sixty-four bytes or more".toByteArray())

        assertContentEquals(current, appDatabaseFile(context).readBytes())
        assertEquals(
            listOf("OperationFailed operation=restore app.db cause=not a database"),
            logs().filter { it.startsWith("OperationFailed") },
        )
        assertNull(SkippedRestoreNotice(context).skippedSchema)
        assertFalse(incoming().exists())
    }

    @Test
    fun `restore data that ends early keeps the current app db`() {
        val current = backedUpAppDb()

        restoreAppDb(current.copyOf(100), size = current.size.toLong())

        assertContentEquals(current, appDatabaseFile(context).readBytes())
        assertEquals(
            listOf("OperationFailed operation=restore app.db cause=restore data ended early"),
            logs().filter { it.startsWith("OperationFailed") },
        )
        assertFalse(incoming().exists())
    }

    @Test
    fun `with no app running (restricted mode) the agent starts the app modules, arms the restored alarms and stops them`() {
        val backup = backedUpAppDb()
        freshInstall()
        restoreAppDb(backup)

        agent().onRestoreFinished()

        assertNull(GlobalContext.getOrNull(), "the agent stopped what it started")
        val armed = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertTrue(armed.any { shadowOf(it.operation).requestCode == alarm.requestCode }, "the restored alarm is armed")
        assertFalse(runtimeDatabaseFile(context).exists(), "no session comes with a restore")
    }

    @Test
    fun `a restored app db replaces the current one and only then drops the old journal`() {
        backedUpAppDb()
        val journal = File(appDatabaseFile(context).path + "-journal").apply { writeText("old hot journal") }
        val restored = version2AppDb()

        restoreAppDb(restored)

        assertContentEquals(restored, appDatabaseFile(context).readBytes(), "the restored bytes are intact")
        assertFalse(journal.exists(), "the old journal never pairs with the restored file")
        assertEquals(emptyList(), logs().filter { it.startsWith("OperationFailed") })
    }

    @Test
    fun `when the move fails the current app db and its journal are kept`() {
        val folder = temp.newFolder()
        // A non-empty directory where app.db goes: no move can replace it.
        val destination = File(folder, "app.db").apply { mkdirs() }
        val content = File(destination, "page").apply { writeText("current") }
        val journal = File(folder, "app.db-journal").apply { writeText("hot journal") }
        val incoming = File(folder, "app.db.restoring").apply { writeBytes(sqliteHeader(AppDatabase.SCHEMA_VERSION)) }
        val logger = FakeLogger()

        val guard = AppDatabaseRestoreGuard(AppDatabase.SCHEMA_VERSION, SkippedRestoreNotice(context), logger)

        val result = guard.accept(incoming, destination)

        assertEquals(AppDatabaseRestoreGuard.Result.SkippedUnreadable, result)
        assertEquals("current", content.readText(), "the current app.db is kept")
        assertEquals("hot journal", journal.readText(), "its journal is kept")
        assertEquals(1, logger.events.filterIsInstance<LogEvent.OperationFailed>().size)
        assertFalse(incoming.exists())
    }

    @Test
    fun `a sqlite file with no Room schema version is skipped as not a database`() {
        val current = backedUpAppDb()

        restoreAppDb(withUserVersion(current, 0))
        restoreAppDb(withUserVersion(current, -1))

        assertContentEquals(current, appDatabaseFile(context).readBytes())
        assertEquals(
            List(2) { "OperationFailed operation=restore app.db cause=not a database" },
            logs().filter { it.startsWith("OperationFailed") },
        )
        assertNull(SkippedRestoreNotice(context).skippedSchema)
    }

    @Test
    fun `a restore that replaces app db clears an earlier skipped-restore notice`() {
        val backup = backedUpAppDb()
        assertTrue(SkippedRestoreNotice(context).record(AppDatabase.SCHEMA_VERSION + 1))

        restoreAppDb(backup)

        assertNull(SkippedRestoreNotice(context).skippedSchema, "no stale notice")
    }

    @Test
    fun `a read error from the restore data leaves no partial incoming copy`() {
        val current = backedUpAppDb()
        val source = temp.newFile().apply { writeBytes(current) }
        val data = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        data.close() // every read now fails

        assertFailsWith<IOException> {
            agent().onRestoreFile(data, current.size.toLong(), appDatabaseFile(context), BackupAgent.TYPE_FILE, MODE, 0L)
        }

        assertFalse(incoming().exists(), "the partial copy is gone")
        assertContentEquals(current, appDatabaseFile(context).readBytes())
    }

    @Test
    fun `files other than app db go to the default handling`() {
        val current = backedUpAppDb()
        val settings = SettingsDataStore.settingsFile(context)
        val offered = "restored settings".toByteArray()

        offer(offered, settings, BackupAgent.TYPE_FILE)
        offer(ByteArray(0), appDatabaseFile(context), BackupAgent.TYPE_DIRECTORY)

        assertContentEquals(offered, settings.readBytes(), "the settings file holds the offered bytes")
        assertContentEquals(current, appDatabaseFile(context).readBytes(), "app.db untouched")
        assertEquals(emptyList(), logs().filter { it.startsWith("OperationFailed") })
        assertFalse(incoming().exists())
    }

    @Test
    fun `a failed write of the incoming copy still drains exactly this file from the restore data`() {
        val current = backedUpAppDb()
        incoming().apply { mkdirs() } // a directory where the incoming copy goes: it cannot be opened for writing
        val restored = version2AppDb()
        val marker = "NEXT FILE".toByteArray()
        val source = temp.newFile().apply { writeBytes(restored + marker) }

        val rest =
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { data ->
                agent().onRestoreFile(data, restored.size.toLong(), appDatabaseFile(context), BackupAgent.TYPE_FILE, MODE, 0L)
                FileInputStream(data.fileDescriptor).readBytes()
            }

        assertContentEquals(marker, rest, "the stream is positioned exactly at the next file")
        assertContentEquals(current, appDatabaseFile(context).readBytes(), "the current app.db is unchanged")
        assertEquals(1, logs().count { it.startsWith("OperationFailed") })
    }

    @Test
    fun `a failing reschedule is logged and never ends the agent`() {
        backedUpAppDb()
        restartKoin(context as android.app.Application, module { single<AlarmScheduler> { error("no scheduler") } })

        agent().onRestoreFinished()

        assertEquals(
            listOf("OperationFailed operation=restore reschedule cause=InstanceCreationException"),
            logs().filter { it.startsWith("OperationFailed") },
        )
        assertTrue(GlobalContext.getOrNull() != null, "the running app's graph is left running")
    }

    @Test
    fun `with the app running the reschedule reads the restored app db, not the one it replaced`() {
        val alarmB = anAlarm(id = "alarm-b", repeatDays = DayOfWeek.entries.toSet(), requestCode = 1001)
        val backup =
            GlobalContext.get().let { koin ->
                runBlocking { assertEquals(Outcome.Success(Unit), koin.get<AlarmRepository>().upsert(alarmB)) }
                stopApp()
                appDatabaseFile(context).readBytes()
            }
        freshInstall()
        val koin = startApp()
        runBlocking {
            assertEquals(Outcome.Success(Unit), koin.get<AlarmRepository>().upsert(alarm))
            assertEquals(Outcome.Success(listOf(alarm)), koin.get<AlarmRepository>().listAll(), "the running app's app.db is open")
        }

        restoreAppDb(backup)
        agent().onRestoreFinished()

        assertTrue(scheduler.armed.containsKey(alarmB.requestCode), "the restored alarm is armed (logs ${logs()})")
        assertEquals(emptyList(), logs().filter { it.startsWith("OperationFailed") })
        assertFalse(scheduler.armed.containsKey(alarm.requestCode), "the replaced file's alarm is not")
    }

    /** Offers [bytes] to the agent as a restored file of [type] at [destination]. */
    private fun offer(
        bytes: ByteArray,
        destination: File,
        type: Int,
    ) {
        val source = temp.newFile().apply { writeBytes(bytes) }
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { data ->
            agent().onRestoreFile(data, bytes.size.toLong(), destination, type, MODE, 0L)
        }
    }

    /** The first 100 bytes of a SQLite file whose `user_version` is [version]: all the guard reads. */
    private fun sqliteHeader(version: Int): ByteArray =
        withUserVersion("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyOf(SQLITE_HEADER_BYTES), version)

    private fun incoming() = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "app.db.restoring")

    /** A v2 `app.db` as Room created it (from the exported `2.json`), holding one alarm. */
    private fun version2AppDb(): ByteArray {
        val schema = JSONObject(File("../data/schemas/com.yawnandpawn.app.data.db.AppDatabase/2.json").readText()).getJSONObject("database")
        val file = File(temp.newFolder(), "v2.db")
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                database.execSQL(entity.getString("createSql").replace(TABLE_NAME, table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) {
                    database.execSQL(indices.getJSONObject(j).getString("createSql").replace(TABLE_NAME, table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) database.execSQL(setup.getString(i))
            database.execSQL(
                "INSERT INTO alarm (id, time_nano_of_day, repeat_days, label, enabled, sound_ref, volume_percent, gradual_volume, " +
                    "ramp_start_percent, vibration, snooze_length_minutes, grace_seconds, request_code, created_at, updated_at) " +
                    "VALUES ('alarm-old', 25200000000000, 0, NULL, 1, '${alarm.soundRef}', 80, 1, 20, 1, 9, 60, 1000, 0, 0)",
            )
            database.execSQL("INSERT INTO request_code_sequence (id, last_used) VALUES (0, 1000)")
            database.version = 2
        } finally {
            database.close()
        }
        return file.readBytes()
    }

    private companion object {
        const val MODE = 384L // 0600
        const val USER_VERSION_OFFSET = 60
        const val SQLITE_HEADER_BYTES = 100
        const val TABLE_NAME = "\${TABLE_NAME}"
    }
}
