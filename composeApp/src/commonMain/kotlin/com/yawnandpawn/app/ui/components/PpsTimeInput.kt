package com.yawnandpawn.app.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.datetime.LocalTime

/**
 * `time-picker`: the Material 3 time input (keyboard first, so it works with TalkBack), 12 or 24 hour per
 * [is24Hour], digits in `display` with tabular figures (the Material `displayMedium` slot is `display`).
 * [initialTime] is read when the input is created (again after a 12/24-hour change); every change is reported
 * through [onTimeChange].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PpsTimeInput(
    initialTime: LocalTime,
    is24Hour: Boolean,
    onTimeChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the 12/24-hour setting: a change rebuilds the input from the time it shows now ([initialTime] is the
    // current form time, so nothing is lost).
    val state =
        key(is24Hour) {
            rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute, is24Hour = is24Hour)
        }
    val latestOnTimeChange by rememberUpdatedState(onTimeChange)
    LaunchedEffect(state) {
        snapshotFlow { LocalTime(state.hour, state.minute) }.collect { latestOnTimeChange(it) }
    }
    val colors = PpsTheme.colors
    TimeInput(
        state = state,
        modifier = modifier,
        colors =
            TimePickerDefaults.colors(
                containerColor = colors.bg,
                periodSelectorBorderColor = colors.outline,
                periodSelectorSelectedContainerColor = colors.accent,
                periodSelectorUnselectedContainerColor = colors.bg,
                periodSelectorSelectedContentColor = colors.onAccent,
                periodSelectorUnselectedContentColor = colors.textSecondary,
                timeSelectorSelectedContainerColor = colors.accent,
                timeSelectorUnselectedContainerColor = colors.surfaceVariant,
                timeSelectorSelectedContentColor = colors.onAccent,
                timeSelectorUnselectedContentColor = colors.text,
            ),
    )
}
