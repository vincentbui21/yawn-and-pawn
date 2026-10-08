package com.yawnandpawn.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.RequestCodeSequence
import com.yawnandpawn.app.core.billing.InstallIdProvider
import com.yawnandpawn.app.core.billing.PurchaseIntentStore
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNoteDismissals
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.stats.CheckRegistrations
import com.yawnandpawn.app.core.stats.FallbackHistory
import com.yawnandpawn.app.core.stats.ReRegisterDismissals
import com.yawnandpawn.app.core.stats.StoredCheckRegistrations
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import com.yawnandpawn.app.data.alarm.RoomRequestCodeSequence
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.RuntimeDatabase
import com.yawnandpawn.app.data.history.RoomSessionHistoryRepository
import com.yawnandpawn.app.data.session.RoomActiveSessionStore
import com.yawnandpawn.app.data.session.RoomPurchaseIntentStore
import com.yawnandpawn.app.data.settings.DataStoreInstallIdProvider
import com.yawnandpawn.app.data.settings.DataStoreMissedNoteDismissals
import com.yawnandpawn.app.data.settings.DataStoreReRegisterDismissals
import com.yawnandpawn.app.data.settings.InstallIdDataStore
import com.yawnandpawn.app.data.settings.SettingsDataStore
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.anAppVersion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class DataModuleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the data module binds the alarm repository, the request code sequence and session history to Room over one app database`() {
        val app = koinApplication { modules(module { single<Context> { context } }, dataModule) }
        val koin = app.koin
        val database = koin.get<AppDatabase>()
        try {
            assertIs<RoomAlarmRepository>(koin.get<AlarmRepository>())
            assertIs<RoomRequestCodeSequence>(koin.get<RequestCodeSequence>())
            assertIs<RoomSessionHistoryRepository>(koin.get<SessionHistoryRepository>())
            assertIs<RoomSessionHistoryRepository>(koin.get<FallbackHistory>())
            assertIs<StoredCheckRegistrations>(koin.get<CheckRegistrations>())
            assertSame(database, koin.get<AppDatabase>())
        } finally {
            database.close()
            app.close()
        }
    }

    @Test
    fun `the data module binds the active session store to Room over one runtime database`() {
        val app = koinApplication { modules(module { single<Context> { context } }, module { single<Clock> { FakeClock() } }, dataModule) }
        val koin = app.koin
        val database = koin.get<RuntimeDatabase>()
        try {
            assertIs<RoomActiveSessionStore>(koin.get<ActiveSessionStore>())
            assertSame(database, koin.get<RuntimeDatabase>())
        } finally {
            database.close()
            app.close()
        }
    }

    @Test
    fun `the data module binds the missed-note dismissals to one settings DataStore and releases it on close`() {
        val ports =
            module {
                single<Context> { context }
                single<Logger> { FakeLogger() }
            }
        val app = koinApplication { modules(ports, dataModule) }
        val first = app.koin.get<MissedNoteDismissals>()
        assertIs<DataStoreMissedNoteDismissals>(first)
        assertIs<DataStoreReRegisterDismissals>(app.koin.get<ReRegisterDismissals>())
        assertSame(app.koin.get<SettingsDataStore>(), app.koin.get<SettingsDataStore>())
        assertEquals(Outcome.Success(Unit), runBlocking { first.dismiss("s1") })
        app.close()

        // A second app on the same file works once the first one is closed (DataStore refuses two active instances).
        val again = koinApplication { modules(ports, dataModule) }
        try {
            val second = again.koin.get<MissedNoteDismissals>()
            assertEquals(Outcome.Success(Unit), runBlocking { second.dismiss("s2") })
            assertEquals(setOf("s1", "s2"), runBlocking { second.dismissed().first() })
        } finally {
            again.close()
        }
    }

    @Test
    fun `the data module binds the intent store over runtime db and the install id over its own DataStore (Story 4-8)`() {
        val ports =
            module {
                single<Context> { context }
                single<Logger> { FakeLogger() }
                single<IdGenerator> { UuidV4IdGenerator() }
            }
        val app = koinApplication { modules(ports, dataModule) }
        try {
            assertIs<RoomPurchaseIntentStore>(app.koin.get<PurchaseIntentStore>())
            val ids = app.koin.get<InstallIdProvider>()
            assertIs<DataStoreInstallIdProvider>(ids)
            assertSame(app.koin.get<InstallIdDataStore>(), app.koin.get<InstallIdDataStore>())
            val id = assertIs<Outcome.Success<String>>(runBlocking { ids.installId() }).value
            assertEquals(Outcome.Success(id), runBlocking { app.koin.get<InstallIdProvider>().installId() })
        } finally {
            app.koin.get<RuntimeDatabase>().close()
            app.close()
        }
    }

    @Test
    fun `data tests can use builders from the testing module`() {
        assertEquals(100, anAppVersion().versionCode)
    }
}
