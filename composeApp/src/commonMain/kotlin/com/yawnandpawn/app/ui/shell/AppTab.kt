package com.yawnandpawn.app.ui.shell

import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.nav_alarms
import com.yawnandpawn.app.ui.resources.nav_progress
import com.yawnandpawn.app.ui.resources.nav_settings
import com.yawnandpawn.app.ui.resources.symbol_alarm
import com.yawnandpawn.app.ui.resources.symbol_alarm_fill1
import com.yawnandpawn.app.ui.resources.symbol_bar_chart
import com.yawnandpawn.app.ui.resources.symbol_bar_chart_fill1
import com.yawnandpawn.app.ui.resources.symbol_settings
import com.yawnandpawn.app.ui.resources.symbol_settings_fill1
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

/** The three bottom-navigation destinations (EXPERIENCE.md Information Architecture). */
enum class AppTab(
    internal val label: StringResource,
    internal val icon: DrawableResource,
    internal val selectedIcon: DrawableResource,
) {
    Alarms(Res.string.nav_alarms, Res.drawable.symbol_alarm, Res.drawable.symbol_alarm_fill1),
    Progress(Res.string.nav_progress, Res.drawable.symbol_bar_chart, Res.drawable.symbol_bar_chart_fill1),
    Settings(Res.string.nav_settings, Res.drawable.symbol_settings, Res.drawable.symbol_settings_fill1),
}
