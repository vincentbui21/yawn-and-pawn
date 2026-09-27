package com.yawnandpawn.app.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

@Composable
private fun EditorPreview(
    mode: PpsThemeMode,
    state: EditorUiState,
) {
    PpsTheme(mode = mode) {
        AlarmEditorScreen(state = state, is24Hour = false, onIntent = {})
    }
}

private val newAlarm = EditorUiState()

private val editAlarm =
    EditorUiState(
        isNew = false,
        form =
            EditorForm(
                time = LocalTime(6, 30),
                repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                label = "Gym",
                snoozeLengthMinutes = 5,
            ),
    )

private val tomorrow = EditorUiState(ringsTomorrowAt = EditorForm.DEFAULT_TIME)

private val labelError =
    EditorUiState(form = EditorForm(label = "A label that is far too long for any alarm"), fieldError = AlarmField.Label)

@Preview(name = "New alarm · Light", heightDp = 1200)
@Composable
private fun NewLightPreview() = EditorPreview(PpsThemeMode.Light, newAlarm)

@Preview(name = "New alarm · Dark", heightDp = 1200)
@Composable
private fun NewDarkPreview() = EditorPreview(PpsThemeMode.Dark, newAlarm)

@Preview(name = "Edit alarm · Light", heightDp = 1200)
@Composable
private fun EditLightPreview() = EditorPreview(PpsThemeMode.Light, editAlarm)

@Preview(name = "Edit alarm · Dark", heightDp = 1200)
@Composable
private fun EditDarkPreview() = EditorPreview(PpsThemeMode.Dark, editAlarm)

@Preview(name = "Rings tomorrow · Light", heightDp = 1200)
@Composable
private fun TomorrowLightPreview() = EditorPreview(PpsThemeMode.Light, tomorrow)

@Preview(name = "Label error · Dark", heightDp = 1200)
@Composable
private fun LabelErrorDarkPreview() = EditorPreview(PpsThemeMode.Dark, labelError)

@Preview(name = "Discard dialog · Light", heightDp = 1200)
@Composable
private fun DiscardLightPreview() = EditorPreview(PpsThemeMode.Light, editAlarm.copy(showDiscardDialog = true))

@Preview(name = "Discard dialog · Dark", heightDp = 1200)
@Composable
private fun DiscardDarkPreview() = EditorPreview(PpsThemeMode.Dark, editAlarm.copy(showDiscardDialog = true))
