package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.checks.AccessibilityState
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.reliability.ReliabilitySettings
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.stats.CheckRegistrations
import com.yawnandpawn.app.core.stats.ReRegisterSuggestions
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAccessibilityState
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMissedNoteDismissals
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakePurchaseRecordRepository
import com.yawnandpawn.app.testing.FakeReRegisterDismissals
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.testing.FakeReliabilitySettings
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.anAppVersion
import com.yawnandpawn.app.ui.editor.AlarmEditorArgs
import com.yawnandpawn.app.ui.editor.AlarmEditorViewModel
import com.yawnandpawn.app.ui.home.HomeViewModel
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryViewModel
import com.yawnandpawn.app.ui.qr.CameraPermission
import com.yawnandpawn.app.ui.qr.TestCameraPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalTime
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
    fun `the ui module builds the Home, Purchase history and editor ViewModels from the core ports`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val source = anAlarm(id = "source", time = LocalTime(6, 45), label = "Gym")
            val repository = FakeAlarmRepository(listOf(source))
            val ports =
                module {
                    single<AlarmRepository> { repository }
                    single<Clock> { FakeClock() }
                    single<TimeZoneProvider> { FakeTimeZoneProvider() }
                    single<TimeChangeSignal> { FakeTimeChangeSignal() }
                    single<Logger> { FakeLogger() }
                    single<SoundLibrary> { FakeSoundLibrary() }
                    single<SoundPreview> { FakeSoundPreview() }
                    single<ReliabilityProbe> { FakeReliabilityProbe() }
                    single<ReliabilitySettings> { FakeReliabilitySettings() }
                    single<NotificationPermission> { FakeNotificationPermission() }
                    single<AccessibilityState> { FakeAccessibilityState() }
                    single<CameraPermission> { TestCameraPermission() }
                    single<PurchaseRecordRepository> { FakePurchaseRecordRepository() }
                    single<SessionHistoryRepository> { FakeSessionHistoryRepository() }
                    single { AlarmUseCasesFixture(repository = get(), clock = get(), timeZoneProvider = get()) }
                    single<CheckConfigRepository> { get<AlarmUseCasesFixture>().checkConfigs }
                    factory { get<AlarmUseCasesFixture>().save }
                    factory { get<AlarmUseCasesFixture>().setEnabled }
                    factory { get<AlarmUseCasesFixture>().delete }
                    factory { get<AlarmUseCasesFixture>().duplicate }
                    factory { get<AlarmUseCasesFixture>().reRegisterCode }
                    single { MissedNotes(FakeSessionHistoryRepository(), FakeMissedNoteDismissals()) }
                    single {
                        ReRegisterSuggestions(FakeSessionHistoryRepository(), CheckRegistrations.None, FakeReRegisterDismissals(), get())
                    }
                    single { ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), get(), get()) }
                }
            val koin = koinApplication { modules(ports, uiModule) }.koin

            val home = koin.get<HomeViewModel>()
            assertTrue(home.state.value.isLoading)
            val purchases = koin.get<PurchaseHistoryViewModel>()
            assertTrue(purchases.state.value.loading)
            val newEditor = koin.get<AlarmEditorViewModel> { parametersOf(AlarmEditorArgs(alarmId = null)) }
            assertTrue(newEditor.state.value.isNew)
            val editEditor = koin.get<AlarmEditorViewModel> { parametersOf(AlarmEditorArgs(alarmId = "some-id")) }
            assertFalse(editEditor.state.value.isNew)
            // Duplicate (owner decision 2026-10-05): a new alarm prefilled from the stored one, nothing stored.
            val duplicate = koin.get<AlarmEditorViewModel> { parametersOf(AlarmEditorArgs(alarmId = null, copyOf = source.id)) }
            val state = duplicate.state.value
            assertTrue(state.isNew)
            assertFalse(state.isLoading)
            assertFalse(state.hasOverflowMenu)
            assertEquals(LocalTime(6, 45), state.form.time)
            assertEquals("Gym", state.form.label)
            assertEquals(listOf(source), repository.current)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `ui tests can use builders from the testing module`() {
        assertEquals("0.1.0", anAppVersion().versionName)
    }
}
