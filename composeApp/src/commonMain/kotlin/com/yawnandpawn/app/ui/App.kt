package com.yawnandpawn.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.ui.nav.AppNavHost
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode

/**
 * Root composable of the app screens: the Navigation 3 host (Alarms, Alarm editor) in [PpsTheme]
 * (System mode by default, so it follows the device light/dark setting).
 */
@Composable
fun App(
    modifier: Modifier = Modifier,
    themeMode: PpsThemeMode = PpsThemeMode.System,
) {
    PpsTheme(mode = themeMode) {
        AppNavHost(modifier = modifier)
    }
}
