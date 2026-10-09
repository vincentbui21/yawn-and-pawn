package com.yawnandpawn.app.ui

import com.yawnandpawn.app.ui.editor.AlarmEditorArgs
import com.yawnandpawn.app.ui.editor.AlarmEditorViewModel
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.home.HomeViewModel
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings of :composeApp (AD-13): one ViewModel per screen. The core ports and use cases they take
 * (`AlarmRepository`, the alarm use cases, `Clock`, `TimeZoneProvider`, `TimeChangeSignal`, `Logger`, `SoundLibrary`,
 * `SoundPreview`) are bound by :androidApp and :data.
 */
val uiModule =
    module {
        factory { AlarmActions(get(), get(), get(), get()) }
        viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel { PurchaseHistoryViewModel(get(), get(), get(), get(), get()) }
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
            )
        }
    }
