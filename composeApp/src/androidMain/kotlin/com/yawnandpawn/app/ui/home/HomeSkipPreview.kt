package com.yawnandpawn.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

// Story 7.11's Home states: a card whose next ring is skipped (with "Skipped. No charge.") and the skip dialog.

private val sevenOClock = LocalTime(hour = 7, minute = 0)

private val card = AlarmCard("1", sevenOClock, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), "Gym", emptyList(), enabled = true)

private val skipped = HomeUiState(nextAlarm = Countdown.DaysHours(days = 1, hours = 7), alarms = listOf(card.copy(skipped = true)))

private val dialog =
    HomeUiState(nextAlarm = Countdown.Minutes(minutes = 45), alarms = listOf(card), skipDialog = SkipDialog("1", sevenOClock))

@Composable
private fun SkipPreview(
    mode: PpsThemeMode,
    state: HomeUiState,
) {
    PpsTheme(mode = mode) {
        AppShell(selected = AppTab.Alarms, onSelect = {}) {
            HomeScreen(state = state, is24Hour = false, onIntent = {})
        }
    }
}

@Preview(name = "Home next ring skipped · Light")
@Composable
private fun SkippedLightPreview() = SkipPreview(PpsThemeMode.Light, skipped.copy(skippedNoCharge = true))

@Preview(name = "Home next ring skipped · Dark")
@Composable
private fun SkippedDarkPreview() = SkipPreview(PpsThemeMode.Dark, skipped)

@Preview(name = "Home skip dialog · Light")
@Composable
private fun SkipDialogLightPreview() = SkipPreview(PpsThemeMode.Light, dialog)

@Preview(name = "Home skip dialog · Dark")
@Composable
private fun SkipDialogDarkPreview() = SkipPreview(PpsThemeMode.Dark, dialog)
