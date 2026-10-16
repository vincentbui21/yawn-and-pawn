package com.yawnandpawn.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.session.ThemeMode
import com.yawnandpawn.app.ui.nav.AppNavHost
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.toPps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/**
 * Root composable of the app screens: the Navigation 3 host in [PpsTheme] with the theme stored in Settings ›
 * Appearance (Story 5.1). The first frame uses the last-known settings, so a restart opens in the chosen theme at once;
 * then every change of the stored theme re-themes the app. System follows the device light/dark setting. Wake screens
 * (`WakeSurface`) always use Sunrise, whatever is chosen. An unreadable store already reads as the defaults (System).
 */
@Composable
fun App(
    modifier: Modifier = Modifier,
    settings: GlobalSettingsRepository = koinInject(),
) {
    val initial = remember(settings) { settings.lastKnown()?.settings?.themeMode ?: ThemeMode.System }
    val stored = remember(settings) { settings.themeModes() }
    val mode by stored.collectAsStateWithLifecycle(initialValue = initial)
    PpsTheme(mode = mode.toPps()) {
        AppNavHost(modifier = modifier)
    }
}

/**
 * The stored theme on every change. An unreadable store reads as the defaults in `observe()` (System), as every other
 * setting does; anything else that throws ends the flow, and the app keeps the theme it has.
 */
private fun GlobalSettingsRepository.themeModes(): Flow<ThemeMode> = observe().map { it.themeMode }.distinctUntilChanged().catch { }
