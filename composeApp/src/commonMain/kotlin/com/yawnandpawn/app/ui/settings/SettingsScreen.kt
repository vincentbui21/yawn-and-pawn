package com.yawnandpawn.app.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.ui.components.AppSnackbar
import com.yawnandpawn.app.ui.components.BannerWarning
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupCardOf
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PpsBackground
import com.yawnandpawn.app.ui.components.PpsSegmentedControl
import com.yawnandpawn.app.ui.components.RadioRow
import com.yawnandpawn.app.ui.components.ScreenTitle
import com.yawnandpawn.app.ui.components.SessionInProgressPanel
import com.yawnandpawn.app.ui.components.StepSlider
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.components.SwitchRow
import com.yawnandpawn.app.ui.components.TabScreen
import com.yawnandpawn.app.ui.components.rowIf
import com.yawnandpawn.app.ui.components.subScreenTransition
import com.yawnandpawn.app.ui.editor.EditorForm
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.editor_grace_seconds
import com.yawnandpawn.app.ui.resources.editor_quiet_time_note
import com.yawnandpawn.app.ui.resources.editor_snooze_minutes
import com.yawnandpawn.app.ui.resources.editor_vibrate_quiet_time
import com.yawnandpawn.app.ui.resources.home_fix
import com.yawnandpawn.app.ui.resources.home_reliability_banner
import com.yawnandpawn.app.ui.resources.nav_settings
import com.yawnandpawn.app.ui.resources.settings_appearance
import com.yawnandpawn.app.ui.resources.settings_base_fee
import com.yawnandpawn.app.ui.resources.settings_bright_wake
import com.yawnandpawn.app.ui.resources.settings_bright_wake_caption
import com.yawnandpawn.app.ui.resources.settings_default_quiet_time
import com.yawnandpawn.app.ui.resources.settings_default_snooze_length
import com.yawnandpawn.app.ui.resources.settings_delete_all
import com.yawnandpawn.app.ui.resources.settings_delete_body
import com.yawnandpawn.app.ui.resources.settings_delete_confirm
import com.yawnandpawn.app.ui.resources.settings_delete_keep
import com.yawnandpawn.app.ui.resources.settings_delete_title
import com.yawnandpawn.app.ui.resources.settings_max_snoozes
import com.yawnandpawn.app.ui.resources.settings_no_browser
import com.yawnandpawn.app.ui.resources.settings_payments
import com.yawnandpawn.app.ui.resources.settings_privacy
import com.yawnandpawn.app.ui.resources.settings_reliability
import com.yawnandpawn.app.ui.resources.settings_snooze
import com.yawnandpawn.app.ui.resources.settings_support
import com.yawnandpawn.app.ui.resources.settings_terms
import com.yawnandpawn.app.ui.resources.settings_theme_dark
import com.yawnandpawn.app.ui.resources.settings_theme_light
import com.yawnandpawn.app.ui.resources.settings_theme_system
import com.yawnandpawn.app.ui.resources.settings_usage_stats
import com.yawnandpawn.app.ui.resources.settings_wake
import com.yawnandpawn.app.ui.resources.settings_weekly_summary
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings (the Settings tab), stateless, in grouped `card-group`s whose rows show their value and open a sub-screen
 * (owner decision 2026-09-27): Snooze (base fee, max snoozes, default length), Wake (default quiet time, vibrate during
 * quiet time, bright wake screen), Appearance (System / Light / Dark), weekly summary and usage stats, and the
 * Reliability checklist: app behaviour only (owner decision 2026-10-01, feedback item 26: payments, privacy, delete all
 * data, support, terms and about moved to the You tab). The
 * reliability `banner-warning` sits on top while an item fails. During a session only `panel-session-in-progress` shows.
 * Only the [rows] given show (all by default); a card with none of its rows is left out.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    is24Hour: Boolean,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
    rows: Set<SettingsRow> = SettingsRow.entries.toSet(),
) {
    if (state.sessionInProgress) {
        PpsBackground(modifier = modifier) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                ScreenTitle(title = stringResource(Res.string.nav_settings))
                SessionInProgressPanel(onBackToAlarm = { onIntent(SettingsIntent.BackToAlarm) })
            }
        }
        return
    }
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = state.pane,
            transitionSpec = { subScreenTransition(forward = targetState != SettingsPane.Main) },
            label = "settings pane",
        ) { pane ->
            if (pane == SettingsPane.Main) {
                SettingsMain(state = state, onIntent = onIntent, rows = rows)
            } else {
                SettingsSubScreen(pane = pane, state = state, is24Hour = is24Hour, onIntent = onIntent)
            }
        }
    }
}

@Composable
private fun SettingsMain(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    rows: Set<SettingsRow>,
) {
    TabScreen(title = stringResource(Res.string.nav_settings)) {
        if (state.reliabilityProblem) {
            BannerWarning(
                message = stringResource(Res.string.home_reliability_banner),
                actionText = stringResource(Res.string.home_fix),
                onAction = { onIntent(SettingsIntent.FixSettings) },
            )
        }
        GroupCardOf(
            title = stringResource(Res.string.settings_snooze),
            rows =
                listOfNotNull(
                    rowIf(SettingsRow.BaseFee in rows) {
                        NavRow(
                            label = stringResource(Res.string.settings_base_fee),
                            value = state.baseFee,
                            onClick = { onIntent(SettingsIntent.OpenPane(SettingsPane.BaseFee)) },
                        )
                    },
                    rowIf(SettingsRow.MaxSnoozes in rows) {
                        NavRow(
                            label = stringResource(Res.string.settings_max_snoozes),
                            value = state.maxSnoozes.toString(),
                            onClick = { onIntent(SettingsIntent.OpenPane(SettingsPane.MaxSnoozes)) },
                        )
                    },
                    rowIf(SettingsRow.DefaultSnoozeLength in rows) {
                        NavRow(
                            label = stringResource(Res.string.settings_default_snooze_length),
                            value = stringResource(Res.string.editor_snooze_minutes, state.defaultSnoozeMinutes),
                            onClick = { onIntent(SettingsIntent.OpenPane(SettingsPane.SnoozeLength)) },
                        )
                    },
                ),
        )
        WakeCard(state = state, onIntent = onIntent, rows = rows)
        GeneralCards(state = state, onIntent = onIntent, rows = rows)
    }
}

/** Wake: default quiet time, vibrate during quiet time, bright wake screen. */
@Composable
private fun WakeCard(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    rows: Set<SettingsRow>,
) {
    GroupCardOf(
        title = stringResource(Res.string.settings_wake),
        rows =
            listOfNotNull(
                rowIf(SettingsRow.DefaultQuietTime in rows) {
                    NavRow(
                        label = stringResource(Res.string.settings_default_quiet_time),
                        value = stringResource(Res.string.editor_grace_seconds, state.defaultQuietSeconds),
                        onClick = { onIntent(SettingsIntent.OpenPane(SettingsPane.QuietTime)) },
                    )
                },
                rowIf(SettingsRow.VibrateDuringQuietTime in rows) {
                    SwitchRow(
                        label = stringResource(Res.string.editor_vibrate_quiet_time),
                        checked = state.vibrateDuringQuietTime,
                        onCheckedChange = { onIntent(SettingsIntent.VibrateDuringQuietTimeToggled(it)) },
                    )
                },
                rowIf(SettingsRow.BrightWakeScreen in rows) {
                    SwitchRow(
                        label = stringResource(Res.string.settings_bright_wake),
                        subtitle = stringResource(Res.string.settings_bright_wake_caption),
                        checked = state.brightWakeScreen,
                        onCheckedChange = { onIntent(SettingsIntent.BrightWakeScreenToggled(it)) },
                    )
                },
            ),
    )
}

/** Appearance, weekly summary and usage stats, and the Reliability checklist. */
@Composable
private fun GeneralCards(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    rows: Set<SettingsRow>,
) {
    GroupCardOf(
        title = stringResource(Res.string.settings_appearance),
        rows =
            listOfNotNull(
                rowIf(SettingsRow.Appearance in rows) {
                    PpsSegmentedControl(
                        options = PpsThemeMode.entries,
                        selected = state.theme,
                        label = { stringResource(it.label()) },
                        onSelect = { onIntent(SettingsIntent.ThemeSelected(it)) },
                        modifier = Modifier.fillMaxWidth().padding(PpsTheme.spacing.cardPadding),
                    )
                },
            ),
    )
    GroupCardOf(
        rows =
            listOfNotNull(
                rowIf(SettingsRow.WeeklySummary in rows) {
                    SwitchRow(
                        label = stringResource(Res.string.settings_weekly_summary),
                        checked = state.weeklySummary,
                        onCheckedChange = { onIntent(SettingsIntent.WeeklySummaryToggled(it)) },
                    )
                },
                rowIf(SettingsRow.UsageStats in rows) {
                    SwitchRow(
                        label = stringResource(Res.string.settings_usage_stats),
                        checked = state.usageStats,
                        onCheckedChange = { onIntent(SettingsIntent.UsageStatsToggled(it)) },
                    )
                },
            ),
    )
    GroupCardOf(
        rows =
            listOfNotNull(
                rowIf(SettingsRow.Reliability in rows) {
                    NavRow(
                        label = stringResource(Res.string.settings_reliability),
                        onClick = { onIntent(SettingsIntent.ReliabilityClicked) },
                    )
                },
            ),
    )
}

private fun PpsThemeMode.label(): StringResource =
    when (this) {
        PpsThemeMode.System -> Res.string.settings_theme_system
        PpsThemeMode.Light -> Res.string.settings_theme_light
        PpsThemeMode.Dark -> Res.string.settings_theme_dark
    }

/** One Settings sub-screen: a back arrow with the row's title, then the card that sets it. */
@Composable
private fun SettingsSubScreen(
    pane: SettingsPane,
    state: SettingsUiState,
    is24Hour: Boolean,
    onIntent: (SettingsIntent) -> Unit,
) {
    SubScreen(
        title = stringResource(pane.title()),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = { onIntent(SettingsIntent.Back) },
    ) {
        when (pane) {
            SettingsPane.Main -> Unit
            SettingsPane.BaseFee -> BaseFeePane(state = state, is24Hour = is24Hour, onIntent = onIntent)
            SettingsPane.MaxSnoozes -> MaxSnoozesPane(state = state, is24Hour = is24Hour, onIntent = onIntent)
            SettingsPane.SnoozeLength -> SnoozeLengthPane(state = state, onIntent = onIntent)
            SettingsPane.QuietTime -> QuietTimePane(state = state, onIntent = onIntent)
        }
    }
}

private fun SettingsPane.title(): StringResource =
    when (this) {
        SettingsPane.Main -> Res.string.nav_settings
        SettingsPane.BaseFee -> Res.string.settings_base_fee
        SettingsPane.MaxSnoozes -> Res.string.settings_max_snoozes
        SettingsPane.SnoozeLength -> Res.string.settings_default_snooze_length
        SettingsPane.QuietTime -> Res.string.settings_default_quiet_time
    }

/** Default snooze length: 5 / 9 / 10 / 15 min. */
@Composable
private fun SnoozeLengthPane(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
) {
    GroupCard {
        EditorForm.SNOOZE_OPTIONS.forEachIndexed { index, minutes ->
            if (index > 0) GroupDivider()
            RadioRow(
                label = stringResource(Res.string.editor_snooze_minutes, minutes),
                selected = state.defaultSnoozeMinutes == minutes,
                onSelect = { onIntent(SettingsIntent.SnoozeLengthSelected(minutes)) },
            )
        }
    }
}

/** Default quiet time: 15 to 30 s, for new alarms. */
@Composable
private fun QuietTimePane(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
) {
    GroupCard {
        StepSlider(
            title = stringResource(Res.string.settings_default_quiet_time),
            valueText = stringResource(Res.string.editor_grace_seconds, state.defaultQuietSeconds),
            value = state.defaultQuietSeconds,
            range = Alarm.GRACE_SECONDS_RANGE,
            step = 1,
            onValueChange = { onIntent(SettingsIntent.QuietTimeChanged(it)) },
            modifier = Modifier.padding(horizontal = PpsTheme.spacing.cardPadding, vertical = PpsTheme.spacing.space3),
        )
    }
    NoteInline(
        text = stringResource(Res.string.editor_quiet_time_note),
        modifier = Modifier.padding(horizontal = PpsTheme.spacing.cardPadding),
    )
}
