package com.yawnandpawn.app.ui.onboarding

import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.config.SaveGlobalSetting
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.onboarding.AcceptDisclosure
import com.yawnandpawn.app.core.onboarding.CompleteOnboarding
import com.yawnandpawn.app.core.onboarding.RememberOnboardingAlarm
import com.yawnandpawn.app.core.onboarding.SaveOnboardingStep
import com.yawnandpawn.app.core.reliability.DeviceInfo
import com.yawnandpawn.app.core.reliability.EvaluateReliability
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.telemetry.SetAnalyticsConsent
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeConsentStore
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMoneyFormatter
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakeOnboardingRepository
import com.yawnandpawn.app.testing.FakePendingChangeRepository
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.testing.FakeReliabilityRecords
import com.yawnandpawn.app.testing.FakeReliabilitySettings
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.OnboardingPresets
import com.yawnandpawn.app.testing.aPriceSnapshot
import com.yawnandpawn.app.ui.editor.AlarmEditorViewModel
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.qr.ScanResult
import com.yawnandpawn.app.ui.qr.TestCameraPermission
import com.yawnandpawn.app.ui.reliability.ChecklistTestAlarm
import com.yawnandpawn.app.ui.reliability.ReliabilityViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The fakes and helpers shared by the onboarding ViewModel tests (Stories 5.10 and 5.11): the real core use cases on
 * fakes, the app's editor as the first alarm and Story 5.4's checklist as step 6.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("UnnecessaryAbstractClass") // A base for the two test classes, never run itself.
abstract class OnboardingViewModelFixture {
    protected val dispatcher = UnconfinedTestDispatcher()

    /** Monday 2027-03-08, 23:40 UTC (the price cache, fetched on the 3rd, is still fresh). */
    protected val lateEvening = Instant.parse("2027-03-08T23:40:00Z")
    protected val clock = FakeClock(lateEvening)
    protected val zone = FakeTimeZoneProvider(TimeZone.UTC)
    protected val logger = FakeLogger()
    protected val alarms = FakeAlarmRepository()
    protected val pending = FakePendingChangeRepository()
    protected val settings = FakeGlobalSettingsRepository(GlobalSettings(baseFeeTier = 1), pending)
    protected val prices = FakePriceCatalog(aPriceSnapshot(currency = "EUR"))
    protected val onboarding = FakeOnboardingRepository(OnboardingPresets.Fresh)
    protected val ticks = FakeMonotonicClock(1_000)
    protected val sessionLock =
        SessionLockGuard(MutableStateFlow<SessionState>(SessionState.Idle), MutableStateFlow(true), MutableStateFlow(false))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The alarm use cases on the same repository, onboarding store and clock (SaveAlarm checks the consent). */
    protected val checkRows = FakeCheckConfigRepository(alarms)
    protected val fixture =
        AlarmUseCasesFixture(
            repository = alarms,
            clock = clock,
            timeZoneProvider = zone,
            checkConfigs = checkRows,
            pendingChanges = pending,
            onboarding = onboarding,
        )
    protected val notifications = FakeNotificationPermission(needed = true)
    protected var camera = TestCameraPermission()
    protected val probe = FakeReliabilityProbe()
    protected val records = FakeReliabilityRecords()
    protected val systemScreens = FakeReliabilitySettings()
    protected val consents = FakeConsentStore()

    /** How many first-alarm editors onboarding opened (Story 5.11). */
    protected var editorsOpened = 0

    /** The app's editor as onboarding's first alarm, on the fakes. */
    protected fun editor(
        alarmId: String?,
        onSaved: (String) -> Unit,
    ): AlarmEditorViewModel {
        editorsOpened++
        return AlarmEditorViewModel(
            alarmId = alarmId,
            repository = alarms,
            checkConfigs = checkRows,
            saveAlarm = fixture.save,
            clock = clock,
            timeZoneProvider = zone,
            actions = AlarmActions(fixture.setEnabled, fixture.delete, logger),
            soundLibrary = FakeSoundLibrary(),
            soundPreview = FakeSoundPreview(),
            notificationPermission = notifications,
            testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), clock, logger),
            cameraPermission = camera,
            newAlarmDefaults = settings,
            onSaved = onSaved,
        )
    }

    /** Story 5.4's checklist as onboarding's step 6, on the fakes (a Google phone on API 34). */
    protected fun checklistScreen(): ReliabilityViewModel =
        ReliabilityViewModel(
            evaluate =
                EvaluateReliability(
                    probe = probe,
                    device = { DeviceInfo(sdkInt = 34, maker = "Google", fingerprint = "google/build") },
                    checks = checkRows,
                    records = records,
                    logger = logger,
                ),
            settings = systemScreens,
            camera = camera,
            ringTest =
                ChecklistTestAlarm(
                    checkRows,
                    settings,
                    ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), clock, logger),
                    clock,
                    zone,
                    logger,
                ),
            logger = logger,
            // The test's main dispatcher, so an evaluation is over when the call returns.
            evaluationContext = dispatcher,
        )

    protected fun TestScope.viewModel(priceCatalog: PriceCatalog = prices): OnboardingViewModel {
        val save = SaveGlobalSetting(settings, pending, alarms, clock, zone, AlarmWriteLock(), sessionLock)
        val viewModel =
            OnboardingViewModel(
                onboarding = onboarding,
                saveStep = SaveOnboardingStep(onboarding, sessionLock),
                acceptDisclosure = AcceptDisclosure(onboarding, clock, sessionLock),
                completeOnboarding = CompleteOnboarding(onboarding, sessionLock),
                globalSettings = settings,
                priceCatalog = priceCatalog,
                setBaseFee = SetBaseFee(save),
                clock = clock,
                moneyFormatter = FakeMoneyFormatter(),
                logger = logger,
                firstAlarm =
                    FirstAlarmSources(editors = ::editor, remember = RememberOnboardingAlarm(onboarding, sessionLock), alarms = alarms),
                checklist = ChecklistScreens(::checklistScreen),
                usageStats = UsageStatsSources(consents = consents, setConsent = SetAnalyticsConsent(consents, sessionLock)),
                monotonicClock = ticks,
                timeZoneProvider = zone,
            )
        backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
        return viewModel
    }

    protected val code = ScanResult(CodeFormat.Ean13, "4006381333931").code!!

    /** A tap on the step's action once the step has shown for the dwell time. */
    protected fun OnboardingViewModel.next() {
        settle()
        onIntent(OnboardingIntent.Next(shown.step))
    }

    /** The step shown has been on screen for the dwell time, so its actions count. */
    protected fun settle() = ticks.advanceBy(OnboardingViewModel.STEP_DWELL_MILLIS.milliseconds)

    /** A user who chose the base fee and is on [step] (the first alarm stored as [alarmId], if any). */
    protected fun onStep(
        step: OnboardingStep,
        alarmId: String? = null,
    ) {
        onboarding.current = OnboardingPresets.Consented.copy(step = step.name, alarmId = alarmId)
    }

    protected val OnboardingViewModel.shown: OnboardingUiState
        get() = assertNotNull(state.value, "the stored progress was read")

    /** The failures logged for [operation]. */
    protected fun failed(operation: String): List<LogEvent> =
        logger.events.filter { (it as? LogEvent.OperationFailed)?.operation == operation }
}
