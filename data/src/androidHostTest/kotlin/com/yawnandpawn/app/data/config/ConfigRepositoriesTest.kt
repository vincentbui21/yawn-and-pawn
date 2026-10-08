package com.yawnandpawn.app.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.config.CommitmentAction
import com.yawnandpawn.app.core.config.CommitmentEvent
import com.yawnandpawn.app.core.config.LockedField
import com.yawnandpawn.app.core.config.Occurrence
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.config.SettingsSnapshot
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import com.yawnandpawn.app.data.alarm.RoomCheckConfigRepository
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.AppDatabaseConstructor
import com.yawnandpawn.app.data.settings.SettingsDataStore
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeSettingsSnapshotCache
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Story 4.4: the pending changes (app.db and the settings DataStore), the global settings and the commitment events. */
@RunWith(RobolectricTestRunner::class)
class ConfigRepositoriesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database =
        Room
            .inMemoryDatabaseBuilder<AppDatabase>(context, factory = { AppDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val logger = FakeLogger()
    private val cache = FakeSettingsSnapshotCache()
    private var settingsStore = SettingsDataStore(context)
    private val alarms = RoomAlarmRepository(database.alarmDao())
    private val checks = RoomCheckConfigRepository(database.checkConfigDao())
    private val roomPending = RoomPendingChangeRepository(database.pendingChangeDao())
    private val global = DataStoreGlobalSettings(settingsStore.store, logger, cache)
    private val pending = CompositePendingChangeRepository(roomPending, global)
    private val events = RoomCommitmentEventRepository(database.commitmentEventDao())

    private val sevenThirty = Occurrence("a", Instant.parse("2027-03-09T07:30:00Z"))
    private val plan = CheckPlan(CheckMode.Random, listOf(CheckEntry(CheckType.Math, Difficulty.Easy, 1)))

    @After
    fun tearDown() {
        database.close()
        settingsStore.close()
    }

    @Test
    fun `alarm and global pending changes go to their own store and read back together, one per field`() =
        runTest(timeout = TIMEOUT) {
            alarms.upsert(anAlarm(id = "a"))
            val grace = PendingChange("a", SettingValue.GraceSeconds(30), sevenThirty)
            val checksChange = PendingChange("a", SettingValue.Checks(plan), sevenThirty)
            val fee = PendingChange(null, SettingValue.BaseFeeTier(1), sevenThirty)

            listOf(grace, checksChange, fee).forEach { assertEquals(Outcome.Success(Unit), pending.put(it)) }
            val replaced = grace.copy(value = SettingValue.GraceSeconds(25))
            assertEquals(Outcome.Success(Unit), pending.put(replaced))
            assertEquals(Outcome.Success(Unit), pending.put(fee.copy(value = SettingValue.BaseFeeTier(2))))

            val expected = listOf(fee.copy(value = SettingValue.BaseFeeTier(2)), checksChange, replaced)
            assertEquals(Outcome.Success(expected), pending.all())
            assertEquals(expected, pending.observe().first())
            assertEquals(Outcome.Success(listOf(checksChange, replaced)), roomPending.all())

            assertEquals(Outcome.Success(Unit), pending.remove("a", LockedField.GraceSeconds))
            assertEquals(Outcome.Success(Unit), pending.remove(null, LockedField.BaseFee))
            assertEquals(Outcome.Success(Unit), pending.remove(null, LockedField.MaxSnoozes))
            assertEquals(Outcome.Success(listOf(checksChange)), pending.all())
        }

    @Test
    fun `deleting an alarm deletes its pending changes but keeps its commitment events`() =
        runTest(timeout = TIMEOUT) {
            alarms.upsert(anAlarm(id = "a"))
            pending.put(PendingChange("a", SettingValue.GraceSeconds(30), sevenThirty))
            val event = CommitmentEvent("e1", "a", sevenThirty.scheduledAt, CommitmentAction.Deleted, sevenThirty.scheduledAt - 60.minutes)
            assertEquals(Outcome.Success(Unit), events.insert(event))

            assertEquals(Outcome.Success(Unit), checks.deleteWithAlarm("a"))

            assertEquals(Outcome.Success(emptyList()), pending.all())
            assertEquals(Outcome.Success(listOf(event)), events.all())
        }

    @Test
    fun `a pending change for a missing alarm and a duplicate event are storage failures`() =
        runTest(timeout = TIMEOUT) {
            assertIs<DomainError.StorageFailure>(
                assertIs<Outcome.Failure<DomainError>>(
                    pending.put(PendingChange("missing", SettingValue.GraceSeconds(30), sevenThirty)),
                ).error,
            )
            val event = CommitmentEvent("e1", "a", sevenThirty.scheduledAt, CommitmentAction.Disabled, sevenThirty.scheduledAt)
            events.insert(event)
            assertIs<Outcome.Failure<DomainError>>(events.insert(event.copy(action = CommitmentAction.Deleted)))
            assertEquals(Outcome.Success(listOf(event)), events.all())
        }

    @Test
    fun `rows this build cannot read are skipped`() =
        runTest(timeout = TIMEOUT) {
            alarms.upsert(anAlarm(id = "a"))
            val dao = database.pendingChangeDao()
            dao.put(PendingChangeEntity("a", "GraceSeconds", "not json", "a", 0))
            dao.put(PendingChangeEntity("a", "Checks", """{"type":"GraceSeconds","seconds":30}""", "a", 0))
            dao.put(PendingChangeEntity("a", "BaseFee", """{"type":"BaseFee","tier":1}""", "a", 0))
            database.commitmentEventDao().insert(CommitmentEventEntity("e1", "a", 0, "Snoozed", 0))

            assertEquals(Outcome.Success(emptyList()), roomPending.all())
            assertEquals(Outcome.Success(emptyList()), events.all())
        }

    @Test
    fun `the global settings default, persist, and read an out-of-range value as the default`() =
        runTest(timeout = TIMEOUT) {
            assertEquals(Outcome.Success(GlobalSettings()), global.get())

            global.setBaseFeeTier(4)
            global.setMaxSnoozes(2)
            assertEquals(GlobalSettings(baseFeeTier = 4, maxSnoozes = 2), global.observe().first())
            settingsStore.close()
            settingsStore = SettingsDataStore(context)
            val reopened = DataStoreGlobalSettings(settingsStore.store, logger, cache)
            assertEquals(Outcome.Success(GlobalSettings(baseFeeTier = 4, maxSnoozes = 2)), reopened.get())

            settingsStore.store.edit {
                it[DataStoreGlobalSettings.BASE_FEE_TIER] = 11
                it[DataStoreGlobalSettings.MAX_SNOOZES] = 0
                it[DataStoreGlobalSettings.PENDING_GLOBAL] = "[{"
            }
            assertEquals(Outcome.Success(GlobalSettings()), reopened.get())
            assertEquals(Outcome.Success(emptyList()), reopened.allPending())
        }

    @Test
    fun `a broken settings file reads as the defaults in the flows and fails the one-shot calls`() =
        runTest(timeout = TIMEOUT) {
            val broken = DataStoreGlobalSettings(BrokenStore(), logger, cache)

            assertEquals(GlobalSettings(), broken.observe().first())
            assertEquals(emptyList(), broken.observePending().first())
            assertTrue(logger.events.contains(LogEvent.OperationFailed("read global settings", "IOException")))
            assertIs<Outcome.Failure<DomainError>>(broken.get())
            assertIs<Outcome.Failure<DomainError>>(broken.snapshot())
            assertIs<Outcome.Failure<DomainError>>(broken.setBaseFeeTier(2))
            assertIs<Outcome.Failure<DomainError>>(broken.putPending(PendingChange(null, SettingValue.MaxSnoozes(5), sevenThirty)))
            assertIs<Outcome.Failure<DomainError>>(CompositePendingChangeRepository(roomPending, broken).all())
        }

    @Test
    fun `one snapshot holds the settings and the global changes, and every read or write becomes the last-known one`() =
        runTest(timeout = TIMEOUT) {
            alarms.upsert(anAlarm(id = "a"))
            val fee = PendingChange(null, SettingValue.BaseFeeTier(1), sevenThirty)
            val grace = PendingChange("a", SettingValue.GraceSeconds(30), sevenThirty)
            global.setBaseFeeTier(4)
            assertEquals(SettingsSnapshot(GlobalSettings(baseFeeTier = 4), emptyList()), global.lastKnown(), "a write")
            pending.put(fee)
            pending.put(grace)
            assertEquals(SettingsSnapshot(GlobalSettings(baseFeeTier = 4), listOf(fee)), global.lastKnown(), "a pending write")

            cache.snapshot = null
            assertEquals(Outcome.Success(SettingsSnapshot(GlobalSettings(baseFeeTier = 4), listOf(fee))), global.snapshot())
            assertEquals(SettingsSnapshot(GlobalSettings(baseFeeTier = 4), listOf(fee)), global.lastKnown(), "a read")
            assertEquals(Outcome.Success(listOf(grace)), pending.forAlarm("a"), "only the alarm's own")
            assertEquals(Outcome.Success(emptyList()), pending.forAlarm("b"))
        }

    @Test
    fun `the last-known settings survive in their own device-protected preferences`() {
        val stored =
            SettingsSnapshot(
                GlobalSettings(baseFeeTier = 7, maxSnoozes = 2),
                listOf(PendingChange(null, SettingValue.MaxSnoozes(5), sevenThirty)),
            )
        val first = SharedPreferencesSettingsCache(context)
        assertEquals(null, first.load())

        first.save(stored)

        assertEquals(stored, SharedPreferencesSettingsCache(context).load())
        val file = File(context.createDeviceProtectedStorageContext().dataDir, "shared_prefs/${SharedPreferencesSettingsCache.PREFS}.xml")
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(SharedPreferencesSettingsCache.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("snapshot", "not json")
            .commit()
        assertEquals(null, SharedPreferencesSettingsCache(context).load(), "a damaged value reads as none")
        assertTrue(file.isFile, "in device-protected storage")
    }

    /** A DataStore whose reads throw an IOException and whose writes fail. */
    private class BrokenStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk unreadable") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
    }

    private companion object {
        val TIMEOUT = 5.minutes
    }
}
