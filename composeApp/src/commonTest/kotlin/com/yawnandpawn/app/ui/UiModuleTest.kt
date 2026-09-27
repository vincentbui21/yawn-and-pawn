package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.anAppVersion
import com.yawnandpawn.app.ui.alarms.AlarmsViewModel
import com.yawnandpawn.app.ui.editor.AlarmEditorArgs
import com.yawnandpawn.app.ui.editor.AlarmEditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UiModuleTest {
    @Test
    fun `the ui module loads into a Koin application`() {
        val app = koinApplication { modules(uiModule) }

        assertNotNull(app.koin)
        app.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the ui module builds both ViewModels from the core ports`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val repository = FakeAlarmRepository()
            val ports =
                module {
                    single<AlarmRepository> { repository }
                    single<Clock> { FakeClock() }
                    single<TimeZoneProvider> { FakeTimeZoneProvider() }
                    factory { SaveAlarm(get(), FakeIdGenerator(), get(), AlarmWriteLock()) }
                }
            val koin = koinApplication { modules(ports, uiModule) }.koin

            assertNotNull(koin.get<AlarmsViewModel>())
            val newEditor = koin.get<AlarmEditorViewModel> { parametersOf(AlarmEditorArgs(alarmId = null)) }
            assertTrue(newEditor.state.value.isNew)
            val editEditor = koin.get<AlarmEditorViewModel> { parametersOf(AlarmEditorArgs(alarmId = "some-id")) }
            assertFalse(editEditor.state.value.isNew)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `ui tests can use builders from the testing module`() {
        assertEquals("0.1.0", anAppVersion().versionName)
    }
}
