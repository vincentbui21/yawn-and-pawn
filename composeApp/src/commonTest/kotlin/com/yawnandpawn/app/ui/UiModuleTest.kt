package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.billing.MoneyFormatter
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.checks.AccessibilityState
import com.yawnandpawn.app.core.config.CommitmentEventRepository
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.onboarding.OnboardingTestNote
import com.yawnandpawn.app.core.recording.RecordingRepository
import com.yawnandpawn.app.core.reliability.ConfirmMakerGuidance
import com.yawnandpawn.app.core.reliability.DeviceInfo
import com.yawnandpawn.app.core.reliability.DeviceInfoSource
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.core.reliability.EvaluateReliability
import com.yawnandpawn.app.core.reliability.ManufacturerSettings
import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.reliability.ReliabilitySettings
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.stats.CheckRegistrations
import com.yawnandpawn.app.core.stats.DayTicker
import com.yawnandpawn.app.core.stats.ObserveProgress
import com.yawnandpawn.app.core.stats.ReRegisterSuggestions
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAccessibilityState
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeCommitmentEventRepository
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeManufacturerSettings
import com.yawnandpawn.app.testing.FakeMissedNoteDismissals
import com.yawnandpawn.app.testing.FakeMoneyFormatter
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakeOnboardingRepository
import com.yawnandpawn.app.testing.FakePendingChangeRepository
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.FakePurchaseRecordRepository
import com.yawnandpawn.app.testing.FakeReRegisterDismissals
import com.yawnandpawn.app.testing.FakeRecordingRepository
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.testing.FakeReliabilityRecords
import com.yawnandpawn.app.testing.FakeReliabilitySettings
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aDeleteAllData
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.anAppVersion
import com.yawnandpawn.app.testing.anEvaluateReliability
import com.yawnandpawn.app.ui.daydetail.DayDetailViewModel
import com.yawnandpawn.app.ui.editor.AlarmEditorArgs
import com.yawnandpawn.app.ui.editor.AlarmEditorViewModel
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.home.HomeHero
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.home.HomeViewModel
import com.yawnandpawn.app.ui.links.AppLinks
import com.yawnandpawn.app.ui.links.ExternalLinks
import com.yawnandpawn.app.ui.nav.AppOpenRequests
import com.yawnandpawn.app.ui.onboarding.FirstAlarmEditors
import com.yawnandpawn.app.ui.payments.PaymentsViewModel
import com.yawnandpawn.app.ui.progress.ProgressViewModel
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryViewModel
import com.yawnandpawn.app.ui.qr.CameraPermission
import com.yawnandpawn.app.ui.qr.TestCameraPermission
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ManufacturerGuidanceViewModel
import com.yawnandpawn.app.ui.reliability.ReliabilityViewModel
import com.yawnandpawn.app.ui.you.YouUiState
import com.yawnandpawn.app.ui.you.YouViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UiModuleTest {
    @Test
    fun `the ui module loads into a Koin application`() {
        val app = koinApplication { modules(uiModule) }

        assertNotNull(app.koin)
        app.close()
    }

    @Test
    fun `the tab requests from notifications are one per app (Story 6_8)`() {
        val app = koinApplication { modules(uiModule) }

        assertSame(app.koin.get<AppOpenRequests>(), app.koin.get<AppOpenRequests>())
        app.close()
    }

    /** Story 5.12: Home's note after "Skip for now" on onboarding's test alarm, on fakes. */
    private fun aTestNote(): OnboardingTestNote =
        OnboardingTestNote(
            FakeOnboardingRepository(),
            FakeReliabilityRecords(),
            SessionLockGuard(MutableStateFlow<SessionState>(SessionState.Idle), MutableStateFlow(true), MutableStateFlow(false)),
        )

    /** The core ports the Home, Purchase history and editor ViewModels take, on fakes over [repository]. */
    private fun homePorts(repository: FakeAlarmRepository) =
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
            single<RecordingRepository> { FakeRecordingRepository() }
            single<PurchaseRecordRepository> { FakePurchaseRecordRepository() }
            single<SessionHistoryRepository> { FakeSessionHistoryRepository() }
            // Story 6.7: Day detail reads the logged alarm changes.
            single<CommitmentEventRepository> { FakeCommitmentEventRepository() }
            // Story 4.6: the editor's pending values and fee ladder.
            single<PendingChangeRepository> { FakePendingChangeRepository() }
            single<GlobalSettingsRepository> { FakeGlobalSettingsRepository() }
            single<PriceCatalog> { FakePriceCatalog() }
            single<MoneyFormatter> { FakeMoneyFormatter() }
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
            // Story 5.4: Home's banner evaluates the reliability checklist.
            factory<EvaluateReliability> { anEvaluateReliability(probe = get(), checks = get()) }
            single { aTestNote() }
            // Stories 6.3 and 6.5: the Home hero's stats and Progress over the real Story 6.2 snapshot on the fakes.
            factory { DayTicker(get(), get(), get()) }
            factory { ObserveProgress(get(), get(), get(), EmptyCoroutineContext) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the ui module builds the Home, Purchase history, Progress, Day detail and editor ViewModels from the core ports`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val source = anAlarm(id = "source", time = LocalTime(6, 45), label = "Gym")
            val repository = FakeAlarmRepository(listOf(source))
            val koin = koinApplication { modules(homePorts(repository), uiModule) }.koin

            val home = koin.get<HomeViewModel>()
            assertTrue(home.state.value.isLoading)
            val purchases = koin.get<PurchaseHistoryViewModel>()
            assertTrue(purchases.state.value.loading)
            val progress = koin.get<ProgressViewModel>()
            val dayDetail = koin.get<DayDetailViewModel> { parametersOf(LocalDate(2027, 3, 2)) }
            assertTrue(dayDetail.state.value.loading)
            assertEquals(LocalDate(2027, 3, 2), dayDetail.state.value.date)
            assertTrue(progress.state.value.loading)
            val newEditor = koin.get<AlarmEditorViewModel> { parametersOf(AlarmEditorArgs(alarmId = null)) }
            assertTrue(newEditor.state.value.isNew)
            // Story 7.2: the app's editor has the Motivation row; onboarding's first alarm (built the same way) does not.
            assertTrue(
                EditorPane.Motivation in
                    newEditor.state.value.full
                        ?.rows
                        .orEmpty(),
            )
            val onboardingEditor = koin.get<FirstAlarmEditors>().create(null) {}
            assertFalse(
                EditorPane.Motivation in
                    onboardingEditor.state.value.full
                        ?.rows
                        .orEmpty(),
            )
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
            // Story 6.3: the app hands Home its stats, so the hero shows for the stored alarm.
            var shown: HomeUiState? = null
            runTest { shown = home.state.first { !it.isLoading } }
            assertEquals(HomeHero(0), shown?.hero)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the ui module builds the maker guidance ViewModel from the core ports (Story 5_5)`() {
        val ports =
            module {
                single<Logger> { FakeLogger() }
                single<DeviceInfoSource> { DeviceInfoSource { DeviceInfo(34, "Xiaomi", "build-1", DeviceMaker.Xiaomi) } }
                single<ManufacturerSettings> { FakeManufacturerSettings() }
                factory { ConfirmMakerGuidance(get(), FakeReliabilityRecords(), get()) }
            }
        val app = koinApplication { modules(ports, uiModule) }

        assertEquals(
            DeviceMaker.Xiaomi,
            app.koin
                .get<ManufacturerGuidanceViewModel>()
                .state.value.maker,
        )
        app.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the ui module builds the You and Payments ViewModels from the links, clock and price ports`() {
        val ports =
            module {
                single<MonotonicClock> { FakeMonotonicClock() }
                single<Clock> { FakeClock() }
                single<PriceCatalog> { FakePriceCatalog() }
                // "Delete all data" (Story 5.9): the You tab's use case and the session guard it follows.
                single {
                    SessionLockGuard(
                        MutableStateFlow<SessionState>(SessionState.Idle),
                        MutableStateFlow(true),
                        MutableStateFlow(false),
                    )
                }
                single { aDeleteAllData(get()) }
                single<Logger> { FakeLogger() }
                single<ExternalLinks> {
                    object : ExternalLinks {
                        override fun openUrl(url: String): Boolean = true

                        override fun sendEmail(
                            to: String,
                            subject: String,
                            body: String,
                        ): Boolean = true

                        override fun copyText(text: String): Boolean = false
                    }
                }
                single {
                    AppLinks(
                        supportEmail = "help@example.com",
                        orderHistoryUrl = "https://example.com/orders",
                        privacyUrl = "https://example.com/privacy/",
                        termsUrl = "https://example.com/terms/",
                        supportUrl = "https://example.com/support/",
                    )
                }
            }
        val app = koinApplication { modules(ports, uiModule) }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val you = app.koin.get<YouViewModel>()

            assertEquals(YouUiState(appVersion = ""), you.state.value)
            assertNull(
                app.koin
                    .get<PaymentsViewModel>()
                    .cap.value,
            )
        } finally {
            Dispatchers.resetMain()
            app.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the ui module builds the reliability checklist ViewModel from the core ports (Story 5_4)`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val ports =
                module {
                    single<Logger> { FakeLogger() }
                    single<Clock> { FakeClock() }
                    single<TimeZoneProvider> { FakeTimeZoneProvider() }
                    single<CheckConfigRepository> { FakeCheckConfigRepository() }
                    single<GlobalSettingsRepository> { FakeGlobalSettingsRepository() }
                    single<ReliabilitySettings> { FakeReliabilitySettings() }
                    single<CameraPermission> { TestCameraPermission() }
                    single { ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), get(), get()) }
                    factory<EvaluateReliability> { anEvaluateReliability(checks = get()) }
                }
            val app = koinApplication { modules(ports, uiModule) }
            val checklist = app.koin.get<ReliabilityViewModel>()

            checklist.refresh()

            // The app evaluates on Dispatchers.Default (review item 4): wait for the rows in real time.
            runTest { withContext(Dispatchers.Default) { withTimeout(5_000) { checklist.state.first { it.rows.isNotEmpty() } } } }

            val shown =
                checklist.state.value.rows
                    .map { it.item }
            val apiThirtyFour = listOf(ChecklistItem.Notifications, ChecklistItem.FullScreen, ChecklistItem.DoNotDisturb)
            assertEquals(apiThirtyFour + listOf(ChecklistItem.Battery, ChecklistItem.TestAlarm), shown)
            app.close()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `ui tests can use builders from the testing module`() {
        assertEquals("0.1.0", anAppVersion().versionName)
    }
}
