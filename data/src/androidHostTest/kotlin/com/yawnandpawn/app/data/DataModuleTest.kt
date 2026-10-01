package com.yawnandpawn.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.RequestCodeSequence
import com.yawnandpawn.app.core.history.MissedNoteDismissals
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import com.yawnandpawn.app.data.alarm.RoomRequestCodeSequence
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.RuntimeDatabase
import com.yawnandpawn.app.data.history.RoomSessionHistoryRepository
import com.yawnandpawn.app.data.session.RoomActiveSessionStore
import com.yawnandpawn.app.data.settings.DataStoreMissedNoteDismissals
import com.yawnandpawn.app.data.settings.SettingsDataStore
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.anAppVersion
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
        val app = koinApplication { modules(module { single<Context> { context } }, dataModule) }
        val koin = app.koin
        assertIs<DataStoreMissedNoteDismissals>(koin.get<MissedNoteDismissals>())
        assertSame(koin.get<SettingsDataStore>(), koin.get<SettingsDataStore>())
        app.close()

        // A second app on the same file works once the first one is closed (DataStore refuses two active instances).
        val again = koinApplication { modules(module { single<Context> { context } }, dataModule) }
        try {
            runBlocking { again.koin.get<MissedNoteDismissals>().dismiss("s1") }
        } finally {
            again.close()
        }
    }

    @Test
    fun `data tests can use builders from the testing module`() {
        assertEquals(100, anAppVersion().versionCode)
    }
}
