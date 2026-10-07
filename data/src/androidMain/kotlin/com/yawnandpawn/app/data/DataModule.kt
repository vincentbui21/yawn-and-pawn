package com.yawnandpawn.app.data

import android.content.Context
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.alarm.RequestCodeSequence
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
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.RuntimeDatabase
import com.yawnandpawn.app.data.db.buildAppDatabase
import com.yawnandpawn.app.data.db.buildRuntimeDatabase
import com.yawnandpawn.app.data.history.RoomSessionHistoryRepository
import com.yawnandpawn.app.data.session.RoomActiveSessionStore
import com.yawnandpawn.app.data.settings.DataStoreMissedNoteDismissals
import com.yawnandpawn.app.data.settings.DataStoreReRegisterDismissals
import com.yawnandpawn.app.data.settings.DataStoreTestAlarmStore
import com.yawnandpawn.app.data.settings.SettingsDataStore
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.module

/**
 * Koin bindings of :data (AD-13). Needs the Android `Context` (registered by `androidContext` in the app), the
 * `Clock` port (from the app's time module) and the `Logger` (for the settings DataStore's read errors).
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
        // The settings DataStore (Story 1.16), device-protected; released when Koin stops, like the databases.
        single { SettingsDataStore(get<Context>()) } withOptions { onClose { it?.close() } }
        single<MissedNoteDismissals> { DataStoreMissedNoteDismissals(get<SettingsDataStore>().store, get()) }
        // The pending test ring (Story 1.18), in the same device-protected DataStore.
        single<TestAlarmStore> { DataStoreTestAlarmStore(get<SettingsDataStore>().store, get()) }
        // When each re-register banner was dismissed (Story 3.13), in the same device-protected DataStore.
        single<ReRegisterDismissals> { DataStoreReRegisterDismissals(get<SettingsDataStore>().store, get()) }
    }
