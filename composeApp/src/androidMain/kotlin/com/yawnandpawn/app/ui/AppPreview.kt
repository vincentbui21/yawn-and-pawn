package com.yawnandpawn.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.yawnandpawn.app.ui.alarms.AlarmRow
import com.yawnandpawn.app.ui.alarms.AlarmsScreen
import com.yawnandpawn.app.ui.alarms.AlarmsUiState
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

// Previews render the stateless screens (App() needs Koin). Editor previews live in editor/AlarmEditorPreview.kt.

private val sampleAlarms =
    AlarmsUiState(
        isLoading = false,
        alarms =
            listOf(
                AlarmRow("1", LocalTime(6, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), null),
                AlarmRow("2", LocalTime(7, 15), emptySet(), "Stand-up"),
            ),
    )

@Composable
private fun AlarmsPreview(
    mode: PpsThemeMode,
    state: AlarmsUiState,
) {
    PpsTheme(mode = mode) {
        AlarmsScreen(state = state, is24Hour = false, onAddAlarm = {}, onEditAlarm = {})
    }
}

@Preview(name = "Alarms empty · Light")
@Composable
private fun AlarmsEmptyLightPreview() = AlarmsPreview(PpsThemeMode.Light, AlarmsUiState(isLoading = false))

@Preview(name = "Alarms empty · Dark")
@Composable
private fun AlarmsEmptyDarkPreview() = AlarmsPreview(PpsThemeMode.Dark, AlarmsUiState(isLoading = false))

@Preview(name = "Alarms list · Light")
@Composable
private fun AlarmsListLightPreview() = AlarmsPreview(PpsThemeMode.Light, sampleAlarms)

@Preview(name = "Alarms list · Dark")
@Composable
private fun AlarmsListDarkPreview() = AlarmsPreview(PpsThemeMode.Dark, sampleAlarms)
