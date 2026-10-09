package com.yawnandpawn.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.koin.compose.viewmodel.koinViewModel

/** The Settings rows built so far (Story 4.5): the Snooze card's base fee and max snoozes. Epic 5 adds the others. */
val BuiltSettingsRows: Set<SettingsRow> = setOf(SettingsRow.BaseFee, SettingsRow.MaxSnoozes)

/**
 * The Settings tab route: [SettingsViewModel] (scoped to the nav entry) feeding [SettingsScreen] with [rows]. Until the
 * stored settings are read it shows the title only, so no default fee flashes. System Back on a sub-screen returns to
 * the Settings main screen; on the main screen it is left to the tab navigation.
 */
@Composable
fun SettingsRoute(
    is24Hour: Boolean,
    modifier: Modifier = Modifier,
    rows: Set<SettingsRow> = BuiltSettingsRows,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shown = state
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
