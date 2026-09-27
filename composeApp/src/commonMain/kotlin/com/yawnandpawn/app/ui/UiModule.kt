package com.yawnandpawn.app.ui

import com.yawnandpawn.app.ui.alarms.AlarmsViewModel
import com.yawnandpawn.app.ui.editor.AlarmEditorArgs
import com.yawnandpawn.app.ui.editor.AlarmEditorViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings of :composeApp (AD-13): one ViewModel per screen. The core ports and use cases they take
 * (`AlarmRepository`, `SaveAlarm`, `Clock`, `TimeZoneProvider`) are bound by :androidApp and :data.
 */
val uiModule =
    module {
        viewModel { AlarmsViewModel(get()) }
        viewModel { params ->
            AlarmEditorViewModel(
                alarmId = params.get<AlarmEditorArgs>().alarmId,
                repository = get(),
                saveAlarm = get(),
                clock = get(),
                timeZoneProvider = get(),
            )
        }
    }
