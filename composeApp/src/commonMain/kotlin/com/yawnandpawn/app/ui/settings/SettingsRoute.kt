package com.yawnandpawn.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Settings rows built so far: the Snooze card's base fee and max snoozes (Story 4.5); its default snooze length, the
 * Wake card's default quiet time and "Vibrate during quiet time", and Appearance (Story 5.1); the reliability checklist
 * (Story 5.4); the usage stats switch (Story 5.6). Bright wake screen (Story 5.2) and weekly summary come with their own
 * stories.
 */
val BuiltSettingsRows: Set<SettingsRow> =
    setOf(
        SettingsRow.BaseFee,
        SettingsRow.MaxSnoozes,
        SettingsRow.DefaultSnoozeLength,
        SettingsRow.DefaultQuietTime,
        SettingsRow.VibrateDuringQuietTime,
        SettingsRow.Appearance,
        SettingsRow.UsageStats,
        SettingsRow.Reliability,
    )

/**
 * The Settings tab route: [SettingsViewModel] (scoped to the nav entry) feeding [SettingsScreen] with [rows]. Until the
 * stored settings are read it shows the title only, so no default fee flashes. System Back on a sub-screen returns to
 * the Settings main screen; on the main screen it is left to the tab navigation. Every start evaluates the reliability
 * checklist again (the banner clears itself once fixed), and its "Fix" and the "Reliability checklist" row call
 * [onOpenReliability] (Story 5.4).
 */
@Composable
fun SettingsRoute(
    is24Hour: Boolean,
    modifier: Modifier = Modifier,
    rows: Set<SettingsRow> = BuiltSettingsRows,
    onOpenReliability: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shown = state
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.OpenReliability -> onOpenReliability()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onIntent(SettingsIntent.Started) }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = shown != null && shown.pane != SettingsPane.Main,
    ) {
        viewModel.onIntent(SettingsIntent.Back)
    }
    SettingsScreen(
        state = shown ?: Loading,
        is24Hour = is24Hour,
        onIntent = viewModel::onIntent,
        modifier = modifier,
        rows = if (shown == null) emptySet() else rows,
    )
}

/** Never shown with a row: the title only, while the stored settings load. */
private val Loading = SettingsUiState(baseFee = "")
