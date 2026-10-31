package com.yawnandpawn.app.ui

import com.yawnandpawn.app.ui.about.AboutViewModel
import com.yawnandpawn.app.ui.daydetail.DayDetailViewModel
import com.yawnandpawn.app.ui.editor.AlarmEditorArgs
import com.yawnandpawn.app.ui.editor.AlarmEditorViewModel
import com.yawnandpawn.app.ui.editor.EditorFeeSources
import com.yawnandpawn.app.ui.editor.EditorSoundFiles
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.home.HomeViewModel
import com.yawnandpawn.app.ui.nav.AppOpenRequests
import com.yawnandpawn.app.ui.nav.SkipRequests
import com.yawnandpawn.app.ui.onboarding.ChecklistScreens
import com.yawnandpawn.app.ui.onboarding.FirstAlarmEditors
import com.yawnandpawn.app.ui.onboarding.FirstAlarmSources
import com.yawnandpawn.app.ui.onboarding.OnboardingViewModel
import com.yawnandpawn.app.ui.onboarding.TestAlarmSources
import com.yawnandpawn.app.ui.onboarding.UsageStatsSources
import com.yawnandpawn.app.ui.payments.PaymentsViewModel
import com.yawnandpawn.app.ui.payments.ProblemWithChargeViewModel
import com.yawnandpawn.app.ui.progress.ProgressViewModel
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryViewModel
import com.yawnandpawn.app.ui.reliability.ChecklistTestAlarm
import com.yawnandpawn.app.ui.reliability.ManufacturerGuidanceViewModel
import com.yawnandpawn.app.ui.reliability.ReliabilityViewModel
import com.yawnandpawn.app.ui.settings.DefaultSettingSetters
import com.yawnandpawn.app.ui.settings.SettingsViewModel
import com.yawnandpawn.app.ui.settings.WeeklySummarySwitch
import com.yawnandpawn.app.ui.you.YouViewModel
import kotlinx.datetime.LocalDate
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * Koin bindings of :composeApp (AD-13): one ViewModel per screen. The core ports and use cases they take
 * (`AlarmRepository`, the alarm use cases, `Clock`, `MonotonicClock`, `TimeZoneProvider`, `TimeChangeSignal`, `Logger`,
 * `SoundLibrary`, `SoundPreview`, `ExternalLinks`, `AppLinks`, `DeviceInfoSource`, `ManufacturerSettings`,
 * `ConfirmMakerGuidance`, `ConsentStore`, `SetAnalyticsConsent`, `EvaluateReliability`, `ReliabilitySettings`,
 * `CameraPermission`, `RecordingRepository`, `CustomSoundRepository`, `SoundFilePicker`, `SoundImporter`,
 * `ScheduleTestAlarm`, `OnboardingRepository`, `ReliabilityRecords`, the onboarding use cases, `OnboardingTestNote`,
 * `ObserveProgress`, `WeeklySummaryStore`, `SetWeeklySummary`, `SkipNextAlarm` and `UndoSkip`)
 * are bound by :androidApp and :data.
 */
val uiModule =
    module {
        // Story 6.8: the tab a notification asks the app to open (the weekly summary opens Progress), one per app.
        single { AppOpenRequests() }
        // Story 7.11: the alarm a pre-alarm notification's "Skip this one" asks to skip, one per app.
        single { SkipRequests() }
        factory { AlarmActions(get(), get(), get(), skipNext = get(), undoSkip = get()) }
        // Story 5.12: Home's note after "Skip for now" on onboarding's test alarm. Story 6.3: the hero's stats.
        viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), testNote = get(), progress = get()) }
        viewModel { PurchaseHistoryViewModel(get(), get(), get(), get(), get()) }
        // Story 6.5: Progress over core's ObserveProgress (Story 6.2, bound by :androidApp).
        viewModel { ProgressViewModel(observeProgress = get(), logger = get()) }
        // Story 6.7: Day detail of the date the route passes; CommitmentEventRepository is bound by :data (Story 4.4).
        viewModel { params -> DayDetailViewModel(params.get<LocalDate>(), get(), get(), get(), get(), get(), get()) }
        viewModel {
            SettingsViewModel(
                globalSettings = get(),
                pendingChanges = get(),
                priceCatalog = get(),
                setBaseFee = get(),
                setMaxSnoozes = get(),
                setQuietTime = get(),
                clock = get(),
                timeZoneProvider = get(),
                timeChanges = get(),
                moneyFormatter = get(),
                logger = get(),
                defaults = DefaultSettingSetters(get(), get(), get(), get()),
                outlivingScope = get(named(SettingsViewModel.SAVE_SCOPE)),
                consents = get(),
                setAnalyticsConsent = get(),
                reliability = get(),
                // Story 6.8: the "Weekly summary" switch.
                weeklySummary = WeeklySummarySwitch(store = get(), set = get()),
            )
        }
        viewModel { ProblemWithChargeViewModel(get(), get(), get(), get(), get()) }
        factory { ChecklistTestAlarm(get(), get(), get(), get(), get(), get()) }
        viewModel { ReliabilityViewModel(get(), get(), get(), get(), get()) }
        viewModel { ManufacturerGuidanceViewModel(get(), get(), get()) }
        // The You tab (Stories 5.8 and 5.9): its links, and "Delete all data" through core's DeleteAllData (bound by :androidApp).
        viewModel { YouViewModel(links = get(), appLinks = get(), clock = get(), deleteAllData = get(), sessionLock = get()) }
        viewModel { AboutViewModel(links = get(), appLinks = get(), appInfo = get(), clock = get()) }
        viewModel { PaymentsViewModel(priceCatalog = get(), clock = get(), logger = get()) }
        viewModel {
            OnboardingViewModel(
                onboarding = get(),
                saveStep = get(),
                acceptDisclosure = get(),
                completeOnboarding = get(),
                globalSettings = get(),
                priceCatalog = get(),
                setBaseFee = get(),
                clock = get(),
                moneyFormatter = get(),
                logger = get(),
                // Story 5.11: steps 4 and 5 are the editor's form, built exactly like the app's editor.
                firstAlarm =
                    FirstAlarmSources(
                        editors = get(),
                        remember = get(),
                        alarms = get(),
                    ),
                // Step 6 is Story 5.4's own checklist, built exactly like the checklist screen's.
                checklist = ChecklistScreens { ReliabilityViewModel(get(), get(), get(), get(), get()) },
                usageStats = UsageStatsSources(consents = get(), setConsent = get()),
                // Step 8 rings the checklist's own test on the first alarm and watches 5.3's locked pass.
                testAlarm = TestAlarmSources(ring = get(), remember = get(), records = get(), timeChanges = get()),
                monotonicClock = get(),
                timeZoneProvider = get(),
            )
        }
        viewModel { params -> alarmEditor(params.get<AlarmEditorArgs>()) }
        // Onboarding's first alarm (Story 5.11): the app's editor with onSaved, so without the Motivation row (Story 7.2).
        factory { FirstAlarmEditors { alarmId, onSaved -> alarmEditor(AlarmEditorArgs(alarmId), onSaved) } }
    }

/**
 * The Alarm editor for [args], shared by the app's editor and onboarding's first alarm (Story 5.11), which passes
 * [onSaved] to learn the stored alarm's id.
 */
private fun Scope.alarmEditor(
    args: AlarmEditorArgs,
    onSaved: ((String) -> Unit)? = null,
): AlarmEditorViewModel =
    AlarmEditorViewModel(
        alarmId = args.alarmId,
        repository = get(),
        checkConfigs = get(),
        saveAlarm = get(),
        clock = get(),
        timeZoneProvider = get(),
        actions = get(),
        soundLibrary = get(),
        soundPreview = get(),
        notificationPermission = get(),
        testAlarm = get(),
        copyOf = args.copyOf,
        accessibility = get(),
        cameraPermission = get(),
        scanCode = args.scanCode,
        reRegisterCode = get(),
        pendingChanges = get(),
        fees = EditorFeeSources(globalSettings = get(), priceCatalog = get(), moneyFormatter = get()),
        newAlarmDefaults = get(),
        onSaved = onSaved,
        // Story 7.2: the Motivation row and Recordings in the app's editor only, never in onboarding's first alarm.
        recordings = if (onSaved == null) get() else null,
        // Story 7.4: "Your files" and "Pick a file" in the app's editor only; onboarding's first alarm shows no snackbars.
        soundFiles = if (onSaved == null) EditorSoundFiles(sounds = get(), picker = get(), importer = get()) else null,
    )
