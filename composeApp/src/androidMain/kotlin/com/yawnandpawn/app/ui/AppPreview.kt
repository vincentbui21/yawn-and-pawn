package com.yawnandpawn.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.home.AlarmCard
import com.yawnandpawn.app.ui.home.DeleteAlarmDialog
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

// Previews render the stateless screens (App() needs Koin). Editor previews live in editor/AlarmEditorPreview.kt.

private val sampleHome =
    HomeUiState(
        nextAlarm = Countdown.HoursMinutes(7, 12),
        alarms =
            listOf(
                AlarmCard("1", LocalTime(6, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), null, emptyList(), true),
                AlarmCard("2", LocalTime(7, 15), emptySet(), "Stand-up", emptyList(), false),
            ),
    )

@Composable
private fun HomePreview(
    mode: PpsThemeMode,
    state: HomeUiState,
) {
    PpsTheme(mode = mode) {
        AppShell(selected = AppTab.Alarms, onSelect = {}) {
            HomeScreen(state = state, is24Hour = false, onIntent = {})
        }
    }
}

@Preview(name = "Home empty · Light")
@Composable
private fun HomeEmptyLightPreview() = HomePreview(PpsThemeMode.Light, HomeUiState())

@Preview(name = "Home empty · Dark")
@Composable
private fun HomeEmptyDarkPreview() = HomePreview(PpsThemeMode.Dark, HomeUiState())

@Preview(name = "Home list · Light")
@Composable
private fun HomeListLightPreview() = HomePreview(PpsThemeMode.Light, sampleHome)

@Preview(name = "Home list · Dark")
@Composable
private fun HomeListDarkPreview() = HomePreview(PpsThemeMode.Dark, sampleHome)

@Preview(name = "Home load failed · Light")
@Composable
private fun HomeFailedLightPreview() = HomePreview(PpsThemeMode.Light, HomeUiState(loadFailed = true))

@Preview(name = "Home load failed · Dark")
@Composable
private fun HomeFailedDarkPreview() = HomePreview(PpsThemeMode.Dark, HomeUiState(loadFailed = true))

@Preview(name = "Home delete dialog · Light")
@Composable
private fun HomeDeleteLightPreview() =
    HomePreview(PpsThemeMode.Light, sampleHome.alarms.first().let { sampleHome.copy(deleteDialog = DeleteAlarmDialog(it.id, it.time)) })

@Preview(name = "Home open failed · Light")
@Composable
private fun HomeOpenFailedLightPreview() = HomePreview(PpsThemeMode.Light, sampleHome.copy(openFailed = true))

@Preview(name = "Home save failed · Light")
@Composable
private fun HomeSaveFailedLightPreview() = HomePreview(PpsThemeMode.Light, sampleHome.copy(saveFailed = true))
