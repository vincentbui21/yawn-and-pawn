package com.yawnandpawn.app.data

import android.content.Context
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.alarm.RequestCodeSequence
import com.yawnandpawn.app.core.billing.GrantLedgerStore
import com.yawnandpawn.app.core.billing.InstallIdProvider
import com.yawnandpawn.app.core.billing.PriceCacheStore
import com.yawnandpawn.app.core.billing.PurchaseIntentStore
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.config.CommitmentEventRepository
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.config.SettingsSnapshotCache
import com.yawnandpawn.app.core.history.MissedNoteDismissals
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.TestAlarmStore
import com.yawnandpawn.app.core.stats.CheckRegistrations
import com.yawnandpawn.app.core.stats.FallbackHistory
import com.yawnandpawn.app.core.stats.ReRegisterDismissals
import com.yawnandpawn.app.core.stats.StoredCheckRegistrations
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import com.yawnandpawn.app.data.alarm.RoomCheckConfigRepository
import com.yawnandpawn.app.data.alarm.RoomRequestCodeSequence
import com.yawnandpawn.app.data.billing.RoomPurchaseRecordRepository
import com.yawnandpawn.app.data.config.CompositePendingChangeRepository
import com.yawnandpawn.app.data.config.DataStoreGlobalSettings
import com.yawnandpawn.app.data.config.RoomCommitmentEventRepository
import com.yawnandpawn.app.data.config.RoomPendingChangeRepository
import com.yawnandpawn.app.data.config.SharedPreferencesSettingsCache
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.RuntimeDatabase
import com.yawnandpawn.app.data.db.buildAppDatabase
import com.yawnandpawn.app.data.db.buildRuntimeDatabase
import com.yawnandpawn.app.data.history.RoomSessionHistoryRepository
import com.yawnandpawn.app.data.session.RoomActiveSessionStore
import com.yawnandpawn.app.data.session.RoomGrantLedgerStore
import com.yawnandpawn.app.data.session.RoomPurchaseIntentStore
import com.yawnandpawn.app.data.settings.DataStoreInstallIdProvider
import com.yawnandpawn.app.data.settings.DataStoreMissedNoteDismissals
import com.yawnandpawn.app.data.settings.DataStorePriceCacheStore
import com.yawnandpawn.app.data.settings.DataStoreReRegisterDismissals
import com.yawnandpawn.app.data.settings.DataStoreTestAlarmStore
import com.yawnandpawn.app.data.settings.InstallIdDataStore
import com.yawnandpawn.app.data.settings.PriceCacheDataStore
import com.yawnandpawn.app.data.settings.SettingsDataStore
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.module

/**
 * Koin bindings of :data (AD-13). Needs the Android `Context` (registered by `androidContext` in the app), the
 * `Clock` port (from the app's time module), the `Logger` (for the settings DataStore's read errors) and the
 * `IdGenerator` (for the install id).
 */
val dataModule =
    module {
        // Closed when Koin stops, so a new app instance never opens a file another instance still holds.
        single { buildAppDatabase(get<Context>()) } withOptions { onClose { it?.close() } }
        single { get<AppDatabase>().alarmDao() }
        single { get<AppDatabase>().requestCodeSequenceDao() }
        single<AlarmRepository> { RoomAlarmRepository(get()) }
        // check_config (Story 3.5): written only by the guarded alarm use cases.
        single { get<AppDatabase>().checkConfigDao() }
        single<CheckConfigRepository> { RoomCheckConfigRepository(get()) }
        single<RequestCodeSequence> { RoomRequestCodeSequence(get()) }
        // session_history (Story 1.13): written only by core's SessionRecorder, wired in the app.
        single { get<AppDatabase>().sessionHistoryDao() }
        single<SessionHistoryRepository> { RoomSessionHistoryRepository(get()) }
        // Home's re-register banner (Story 3.13) reads the fallback rows; stateless over the same DAO.
        single<FallbackHistory> { RoomSessionHistoryRepository(get()) }
        // Story 3.10: the stored QR/Barcode codes and when each was registered (the re-register banner, Story 3.13).
        single<CheckRegistrations> { StoredCheckRegistrations(get()) }
        // runtime.db (Story 1.12): the write-ahead copy of the active session, not backed up.
        single { buildRuntimeDatabase(get<Context>()) } withOptions { onClose { it?.close() } }
        single { get<RuntimeDatabase>().activeSessionDao() }
        single<ActiveSessionStore> { RoomActiveSessionStore(get(), get()) }
        // The purchase intents (Story 4.8): written only by the engine's commit, read and purged here.
        single<PurchaseIntentStore> { RoomPurchaseIntentStore(get<RuntimeDatabase>().purchaseIntentDao()) }
        // The install id (Story 4.8) in its own device-protected DataStore, excluded from backup; released like the others.
        single { InstallIdDataStore(get<Context>(), get(), get()) } withOptions { onClose { it?.close() } }
        single<InstallIdProvider> { DataStoreInstallIdProvider(get<InstallIdDataStore>().store, get(), get()) }
        // The settings DataStore (Story 1.16), device-protected; released when Koin stops, like the databases.
        single { SettingsDataStore(get<Context>()) } withOptions { onClose { it?.close() } }
        single<MissedNoteDismissals> { DataStoreMissedNoteDismissals(get<SettingsDataStore>().store, get()) }
        // The pending test ring (Story 1.18), in the same device-protected DataStore.
        single<TestAlarmStore> { DataStoreTestAlarmStore(get<SettingsDataStore>().store, get()) }
        // When each re-register banner was dismissed (Story 3.13), in the same device-protected DataStore.
        single<ReRegisterDismissals> { DataStoreReRegisterDismissals(get<SettingsDataStore>().store, get()) }
        // The commitment lock (Story 4.4): the global settings and their pending changes in the same DataStore (AD-6),
        // the alarms' pending changes and the commitment events in app.db.
        // The last-known settings for a fire that cannot read the DataStore in time (review fix 11); not backed up.
        single<SettingsSnapshotCache> { SharedPreferencesSettingsCache(get<Context>()) }
        single { DataStoreGlobalSettings(get<SettingsDataStore>().store, get(), get()) }
        single<GlobalSettingsRepository> { get<DataStoreGlobalSettings>() }
        single { get<AppDatabase>().pendingChangeDao() }
        single { RoomPendingChangeRepository(get()) }
        single<PendingChangeRepository> { CompositePendingChangeRepository(get(), get()) }
        single { get<AppDatabase>().commitmentEventDao() }
        single<CommitmentEventRepository> { RoomCommitmentEventRepository(get()) }
        // Story 4.10: the grant ledger in runtime.db (rows inserted only by the engine's commit; the raw tokens live only
        // there) and the purchase records in app.db (backed up, keyed by token hash; written only by PurchaseLedger).
        single<GrantLedgerStore> { RoomGrantLedgerStore(get<RuntimeDatabase>().grantLedgerDao()) }
        single<PurchaseRecordRepository> { RoomPurchaseRecordRepository(get<AppDatabase>().purchaseRecordDao()) }
        // The cached Play prices (Story 4.3): their own device-protected DataStore, never backed up; released like the others.
        single { PriceCacheDataStore(get<Context>()) } withOptions { onClose { it?.close() } }
        single<PriceCacheStore> { DataStorePriceCacheStore(get<PriceCacheDataStore>().store, get()) }
    }
