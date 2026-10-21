package com.yawnandpawn.app.ui

import com.yawnandpawn.app.ui.editor.AlarmEditorArgs
import com.yawnandpawn.app.ui.editor.AlarmEditorViewModel
import com.yawnandpawn.app.ui.editor.EditorFeeSources
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.home.HomeViewModel
import com.yawnandpawn.app.ui.payments.PaymentsViewModel
import com.yawnandpawn.app.ui.payments.ProblemWithChargeViewModel
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryViewModel
import com.yawnandpawn.app.ui.reliability.ManufacturerGuidanceViewModel
import com.yawnandpawn.app.ui.settings.DefaultSettingSetters
import com.yawnandpawn.app.ui.settings.SettingsViewModel
import com.yawnandpawn.app.ui.you.YouViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin bindings of :composeApp (AD-13): one ViewModel per screen. The core ports and use cases they take
 * (`AlarmRepository`, the alarm use cases, `Clock`, `MonotonicClock`, `TimeZoneProvider`, `TimeChangeSignal`, `Logger`,
 * `SoundLibrary`, `SoundPreview`, `ExternalLinks`, `AppLinks`, `DeviceInfoSource`, `ManufacturerSettings`,
 * `ConfirmMakerGuidance`, `ConsentStore`, `SetAnalyticsConsent`) are bound by :androidApp and :data.
 */
val uiModule =
    module {
        factory { AlarmActions(get(), get(), get()) }
        viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel { PurchaseHistoryViewModel(get(), get(), get(), get(), get()) }
        viewModel {
            SettingsViewModel(
                globalSettings = get(),
                pendingChanges = get(),
                priceCatalog = get(),
                setBaseFee = get(),
                setMaxSnoozes = get(),
                clock = get(),
                timeZoneProvider = get(),
                timeChanges = get(),
                moneyFormatter = get(),
                logger = get(),
                defaults = DefaultSettingSetters(get(), get(), get(), get()),
                outlivingScope = get(named(SettingsViewModel.SAVE_SCOPE)),
                consents = get(),
                setAnalyticsConsent = get(),
            )
        }
        viewModel { ProblemWithChargeViewModel(get(), get(), get(), get(), get()) }
        viewModel { ManufacturerGuidanceViewModel(get(), get(), get()) }
        // The You tab (Stories 5.8 and 5.9): its links, and "Delete all data" through core's DeleteAllData (bound by :androidApp).
        viewModel { YouViewModel(links = get(), appLinks = get(), clock = get(), deleteAllData = get(), sessionLock = get()) }
        viewModel { PaymentsViewModel(priceCatalog = get(), clock = get(), logger = get()) }
        viewModel { params ->
            AlarmEditorViewModel(
                alarmId = params.get<AlarmEditorArgs>().alarmId,
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
                copyOf = params.get<AlarmEditorArgs>().copyOf,
                accessibility = get(),
                cameraPermission = get(),
                scanCode = params.get<AlarmEditorArgs>().scanCode,
                reRegisterCode = get(),
                pendingChanges = get(),
                fees = EditorFeeSources(globalSettings = get(), priceCatalog = get(), moneyFormatter = get()),
                newAlarmDefaults = get(),
            )
        }
    }
