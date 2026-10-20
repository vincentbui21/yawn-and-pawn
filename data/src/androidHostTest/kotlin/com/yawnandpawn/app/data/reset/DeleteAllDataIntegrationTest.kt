package com.yawnandpawn.app.data.reset

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.alarm.RequestCodeHighWaterMark
import com.yawnandpawn.app.core.alarm.RequestCodeSequence
import com.yawnandpawn.app.core.billing.GrantLedgerStore
import com.yawnandpawn.app.core.billing.InstallIdProvider
import com.yawnandpawn.app.core.billing.PriceCacheStore
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.billing.PriceSnapshotLookup
import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.billing.PurchaseIntentStore
import com.yawnandpawn.app.core.billing.PurchaseLedger
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.config.CommitmentAction
import com.yawnandpawn.app.core.config.CommitmentEvent
import com.yawnandpawn.app.core.config.CommitmentEventRepository
import com.yawnandpawn.app.core.config.Occurrence
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.config.SettingsSnapshot
import com.yawnandpawn.app.core.config.SettingsSnapshotCache
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNoteDismissals
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.ChecklistRow
import com.yawnandpawn.app.core.reliability.ReliabilityRecords
import com.yawnandpawn.app.core.reliability.StoredReliability
import com.yawnandpawn.app.core.reset.DeleteAllData
import com.yawnandpawn.app.core.reset.DeletionMarker
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.RuntimeWrite
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.session.ThemeMode
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.core.telemetry.ConsentStore
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.data.config.DataStoreGlobalSettings
import com.yawnandpawn.app.data.dataModule
import com.yawnandpawn.app.data.settings.SettingsDataStore
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeAnalyticsDataReset
import com.yawnandpawn.app.testing.FakeBackgroundWork
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeUnsentCrashReports
import com.yawnandpawn.app.testing.aGrant
import com.yawnandpawn.app.testing.aPriceSnapshot
import com.yawnandpawn.app.testing.aPurchaseIntent
import com.yawnandpawn.app.testing.aPurchaseRecord
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionHistoryRow
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.checkConfigsOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 5.9 on the real files: both Room databases, the settings, install id and price cache DataStores, the settings
 * fallback preferences and the marker file are filled, "Delete all data" runs through the data adapters, and every table
 * and file is empty except the grant ledger rows, the records still owed a consume, the request-code high-water mark and
 * the per-device preferences.
 */
@RunWith(RobolectricTestRunner::class)
class DeleteAllDataIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock(NOW)
    private val testLogger = FakeLogger()
    private val app =
        koinApplication {
            modules(
                module {
                    single<Context> { context }
                    single<Logger> { testLogger }
                    single<Clock> { clock }
                    single<IdGenerator> { UuidV4IdGenerator() }
                },
                dataModule,
            )
        }
    private val koin: Koin = app.koin

    @After
    fun tearDown() {
        app.close()
    }

    @Test
    fun `everything is deleted but the grant ledger and the owed records, the marker is gone and the install id stays`() =
        runBlocking {
            fill(owed = true)
            val installId = installId()

            assertEquals(Outcome.Success(Unit), deleteAllData()())

            assertEverythingDeleted()
            // The grant ledger stays whole: the pending row and the settled marker.
            val ledger = koin.get<GrantLedgerStore>()
            assertEquals(listOf(PENDING_GRANT), assertIs<Outcome.Success<*>>(ledger.all()).value)
            assertNotNull(assertIs<Outcome.Success<*>>(ledger.get(SETTLED_GRANT.token)).value)
            // Only the records still owed a consume stay, and the pending row's record whatever its status (review item 4).
            val records = assertIs<Outcome.Success<List<PurchaseRecord>>>(koin.get<PurchaseRecordRepository>().all()).value
            assertEquals(
                setOf(OWED.tokenHash, REUSED_NOT_CONSUMED.tokenHash, PENDING_STRANDED.tokenHash),
                records.map { it.tokenHash }.toSet(),
            )
            // The pending row's session keeps its intent, which prices the record its settle writes.
            val intents = koin.get<PurchaseIntentStore>().forSession(PENDING_SESSION)
            assertEquals(
                listOf(PENDING_INTENT.intentId),
                assertIs<Outcome.Success<List<PurchaseIntent>>>(intents).value.map { it.intentId },
            )
            assertEquals(installId, installId(), "something is owed: the install id is kept")
        }

    @Test
    fun `with nothing owed every record goes and the install id is new`() =
        runBlocking {
            fill(owed = false)
            val installId = installId()

            assertEquals(Outcome.Success(Unit), deleteAllData()())

            assertEverythingDeleted()
            assertEquals(Outcome.Success(emptyList<PurchaseRecord>()), koin.get<PurchaseRecordRepository>().all())
            assertNotEquals(installId, installId())
        }

    @Test
    fun `the marker file is written to disk, read back by a new deletion as pending, and removed`() =
        runBlocking {
            val marker = koin.get<DeletionMarker>()
            assertFalse(marker.exists())

            assertEquals(0, marker.countStart(), "no marker, no start counted")
            assertEquals(Outcome.Success(Unit), marker.write(resetAttempts = true))
            assertTrue(FileDeletionMarker.markerFile(context).isFile)
            assertTrue(deleteAllData().pending.value, "a marker found at start is a pending deletion")
            // The start count is kept in the file: a resume's write keeps it, the user's new delete sets it to 0.
            assertEquals(1, marker.countStart())
            assertEquals(2, marker.countStart())
            assertEquals(Outcome.Success(Unit), marker.write(resetAttempts = false), "writing it again is not an error")
            assertEquals(3, marker.countStart())
            assertEquals(Outcome.Success(Unit), marker.write(resetAttempts = true))
            assertEquals(1, marker.countStart())

            assertEquals(Outcome.Success(Unit), marker.remove())
            assertFalse(marker.exists())
            assertEquals(Outcome.Success(Unit), marker.remove(), "nothing there is not an error")
        }

    private fun deleteAllData(): DeleteAllData =
        DeleteAllData(
            lock = AlarmWriteLock(),
            sessionLock = SessionLockGuard(MutableStateFlow(SessionState.Idle), MutableStateFlow(true), MutableStateFlow(false)),
            marker = koin.get(),
            alarms = koin.get(),
            highWaterMark = koin.get(),
            scheduler = FakeAlarmScheduler(),
            eraser = koin.get(),
            history = koin.get(),
            ledger =
                PurchaseLedger(
                    koin.get(),
                    koin.get(),
                    koin.get(),
                    FakeBilling(),
                    FakeBackgroundWork(),
                    PriceSnapshotLookup.None,
                    clock,
                    testLogger,
                ),
            activeSession = koin.get(),
            installId = koin.get(),
            crashReports = FakeUnsentCrashReports(),
            analytics = FakeAnalyticsDataReset(),
            work = FakeBackgroundWork(),
            clock = clock,
            logger = testLogger,
        )

    private suspend fun installId(): String = assertIs<Outcome.Success<String>>(koin.get<InstallIdProvider>().installId()).value

    private suspend fun fill(owed: Boolean) {
        // app.db: two alarms with their checks, two request codes, pending changes, a commitment event, history and a merge.
        val alarms = listOf(anAlarm(id = ALARM_1, requestCode = 1000), anAlarm(id = ALARM_2, requestCode = 1001))
        val sequence = koin.get<RequestCodeSequence>()
        alarms.forEach { alarm ->
            assertIs<Outcome.Success<*>>(sequence.next())
            assertIs<Outcome.Success<*>>(
                koin.get<CheckConfigRepository>().saveWithAlarm(alarm, checkConfigsOf(alarm.id, CheckConfig.DEFAULT_ENTRIES)),
            )
        }
        val waitsFor = Occurrence(ALARM_1, NOW)
        val pending = koin.get<PendingChangeRepository>()
        assertIs<Outcome.Success<*>>(pending.put(PendingChange(ALARM_1, SettingValue.GraceSeconds(30), waitsFor)))
        assertIs<Outcome.Success<*>>(pending.put(PendingChange(null, SettingValue.MaxSnoozes(5), waitsFor)))
        val event = CommitmentEvent("event-1", ALARM_1, NOW, CommitmentAction.Disabled, NOW)
        assertIs<Outcome.Success<*>>(koin.get<CommitmentEventRepository>().insert(event))
        val history = koin.get<SessionHistoryRepository>()
        assertIs<Outcome.Success<*>>(history.upsert(aSessionHistoryRow(sessionId = SESSION, alarmId = ALARM_1)))
        assertIs<Outcome.Success<*>>(history.recordMerge(SessionMergeRow(SESSION, ALARM_2, NOW, NOW)))
        // Purchase records: settled ones always, owed ones when asked.
        val records = koin.get<PurchaseRecordRepository>()
        val settled =
            listOf(
                aPurchaseRecord(token = "consumed", status = RecordStatus.Consumed),
                aPurchaseRecord(token = "stranded", status = RecordStatus.Stranded),
                aPurchaseRecord(token = "reused-consumed", status = RecordStatus.Reused, consumedAt = NOW),
            )
        (
            settled +
                if (owed) {
                    listOf(
                        OWED,
                        REUSED_NOT_CONSUMED,
                        PENDING_STRANDED,
                    )
                } else {
                    emptyList()
                }
        ).forEach { assertIs<Outcome.Success<*>>(records.putRecord(it)) }
        // runtime.db: intents, the grant ledger (a settled marker always, a pending row when owed) and a stored session.
        val session = koin.get<ActiveSessionStore>()
        val writes =
            listOf(RuntimeWrite.PutPurchaseIntent(aPurchaseIntent(sessionId = SESSION)), RuntimeWrite.PutGrant(SETTLED_GRANT)) +
                if (owed) listOf(RuntimeWrite.PutGrant(PENDING_GRANT), RuntimeWrite.PutPurchaseIntent(PENDING_INTENT)) else emptyList()
        assertIs<Outcome.Success<*>>(session.commit(SessionState.Ringing(aSession(sessionId = SESSION)), writes))
        assertIs<Outcome.Success<*>>(koin.get<GrantLedgerStore>().markSettled(SETTLED_GRANT.token, NOW))
        fillStoresAndPreferences()
    }

    /** The DataStores and preferences: settings (5.1's defaults and theme too), its fallback, the prices, reliability. */
    private suspend fun fillStoresAndPreferences() {
        // The DataStores and preferences: settings (a dismissal and a key a later story adds), its fallback, the prices.
        assertIs<Outcome.Success<*>>(koin.get<MissedNoteDismissals>().dismiss("missed-session"))
        koin.get<SettingsDataStore>().store.edit { it[FUTURE_KEY] = "later story" }
        // Story 5.1's defaults and theme: their DataStore keys, and the same fields in the settings fallback snapshot.
        koin.get<SettingsDataStore>().store.edit {
            it[DataStoreGlobalSettings.DEFAULT_GRACE_SECONDS] = 25
            it[DataStoreGlobalSettings.DEFAULT_SNOOZE_LENGTH] = 15
            it[DataStoreGlobalSettings.DEFAULT_VIBRATE_IN_GRACE] = false
            it[DataStoreGlobalSettings.THEME_MODE] = ThemeMode.Dark.name
        }
        koin.get<SettingsSnapshotCache>().save(
            SettingsSnapshot(
                GlobalSettings(
                    baseFeeTier = 3,
                    maxSnoozes = 2,
                    defaultGraceSeconds = 25,
                    defaultSnoozeLengthMinutes = 15,
                    defaultVibrateInGrace = false,
                    themeMode = ThemeMode.Dark,
                ),
                emptyList(),
            ),
        )
        assertEquals(
            ThemeMode.Dark,
            koin
                .get<SettingsSnapshotCache>()
                .load()
                ?.settings
                ?.themeMode,
            "the fallback holds the theme",
        )
        assertIs<Outcome.Success<*>>(koin.get<PriceCacheStore>().update { aPriceSnapshot() })
        // The reliability checklist's own DataStore (Story 5.3): a passed locked test and a row seen OK.
        val reliability = koin.get<ReliabilityRecords>()
        assertIs<Outcome.Success<*>>(reliability.lockedTestPassed(NOW))
        assertIs<Outcome.Success<*>>(reliability.saveLastOk(setOf(ChecklistRow.entries.first())))
        // The usage stats choice (Story 5.6): its own DataStore, consent on and asked.
        assertIs<Outcome.Success<*>>(koin.get<ConsentStore>().setConsent(true))
        assertEquals(AnalyticsConsent(consent = true, asked = true), koin.get<ConsentStore>().observe().first())
        // A per-device preference the deletion never touches.
        deviceContext()
            .getSharedPreferences(WAKE_RUNTIME, Context.MODE_PRIVATE)
            .edit()
            .putInt("saved_volume", 5)
            .commit()
    }

    private suspend fun assertEverythingDeleted() {
        assertFalse(FileDeletionMarker.markerFile(context).exists(), "the marker is removed")
        assertEquals(Outcome.Success(emptyList<Alarm>()), koin.get<AlarmRepository>().listAll())
        assertEquals(Outcome.Success(emptyList<CheckConfig>()), koin.get<CheckConfigRepository>().forAlarm(ALARM_1))
        assertEquals(Outcome.Success(emptyList<PendingChange>()), koin.get<PendingChangeRepository>().all())
        assertEquals(Outcome.Success(emptyList<CommitmentEvent>()), koin.get<CommitmentEventRepository>().all())
        val history = koin.get<SessionHistoryRepository>()
        assertNull(assertIs<Outcome.Success<*>>(history.find(SESSION)).value)
        assertEquals(Outcome.Success(emptyList<SessionMergeRow>()), history.merges(SESSION))
        assertEquals(Outcome.Success(emptyList<PurchaseIntent>()), koin.get<PurchaseIntentStore>().forSession(SESSION))
        assertEquals(Outcome.Success(StoredSession.Empty), koin.get<ActiveSessionStore>().load())
        assertTrue(
            koin
                .get<SettingsDataStore>()
                .store.data
                .first()
                .asMap()
                .isEmpty(),
            "every settings key is cleared",
        )
        assertNull(koin.get<SettingsSnapshotCache>().load())
        assertEquals(PriceCatalogSnapshot.EMPTY, koin.get<PriceCacheStore>().observe().first())
        assertEquals(Outcome.Success(StoredReliability()), koin.get<ReliabilityRecords>().read(), "the reliability records are cleared")
        assertEquals(AnalyticsConsent(), koin.get<ConsentStore>().observe().first(), "usage stats off and not asked (Story 5.6)")
        // Kept: the request-code high-water mark and the per-device preferences.
        assertEquals(Outcome.Success(1001), koin.get<RequestCodeHighWaterMark>().highWaterMark())
        assertEquals(5, deviceContext().getSharedPreferences(WAKE_RUNTIME, Context.MODE_PRIVATE).getInt("saved_volume", 0))
    }

    private fun deviceContext(): Context = context.createDeviceProtectedStorageContext()

    private companion object {
        val NOW: Instant = Instant.parse("2027-03-10T07:00:00Z")
        const val ALARM_1 = "alarm-1"
        const val ALARM_2 = "alarm-2"
        const val SESSION = "session-1"
        const val WAKE_RUNTIME = "wake_runtime"
        val FUTURE_KEY = stringPreferencesKey("a_key_a_later_story_adds")
        val OWED = aPurchaseRecord(token = "owed", status = RecordStatus.Granted)
        val REUSED_NOT_CONSUMED = aPurchaseRecord(token = "reused-owed", status = RecordStatus.Reused, consumedAt = null)
        const val PENDING_SESSION = "session-pending"
        val PENDING_GRANT = aGrant(token = "pending-token", sessionId = PENDING_SESSION)

        /** A stranded record whose reuse the pending row still settles: it stays, though stranded records go. */
        val PENDING_STRANDED = aPurchaseRecord(token = "pending-token", status = RecordStatus.Stranded, sessionId = PENDING_SESSION)
        val PENDING_INTENT = aPurchaseIntent(intentId = "intent-pending", sessionId = PENDING_SESSION)
        val SETTLED_GRANT = aGrant(token = "settled-token")
    }
}
