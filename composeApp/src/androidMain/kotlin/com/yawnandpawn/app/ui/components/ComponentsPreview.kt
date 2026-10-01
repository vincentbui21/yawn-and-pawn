package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.ui.editor.EditorForm
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.DayOfWeek

// Previews of the Story 1.8 components in every state they have (sample text only; the screens use resources).

@Composable
private fun ComponentGallery(mode: PpsThemeMode) {
    PpsTheme(mode = mode) {
        Column(
            modifier = Modifier.background(PpsTheme.colors.bg).padding(PpsTheme.spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space4),
        ) {
            PpsTopAppBar(title = "New alarm", backContentDescription = "Back", onBack = {})
            PpsWheelTimePicker(time = EditorForm.DEFAULT_TIME, is24Hour = false, onTimeChange = {})
            NoteInline(text = "Rings tomorrow at 7:00 AM.")
            DayChipRow(selectedDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), onToggle = {})
            PpsTextField(value = "", onValueChange = {}, label = "Label")
            PpsTextField(value = "x".repeat(Alarm.MAX_LABEL_LENGTH + 1), onValueChange = {
            }, label = "Label", errorText = "Keep the label under 40 characters.")
            PpsSegmentedControl(options = EditorForm.SNOOZE_OPTIONS, selected = Alarm.DEFAULT_SNOOZE_LENGTH_MINUTES, label = {
                "$it min"
            }, onSelect = {})
            ValueRow(label = "Sound", value = "Sunrise")
            PercentSlider(title = "Volume", valueText = "80%", percent = Alarm.DEFAULT_VOLUME_PERCENT, onPercentChange = {})
            SwitchRow(label = "Gradually increase volume", checked = true, onCheckedChange = {})
            SwitchRow(label = "Vibration", checked = false, onCheckedChange = {})
            PpsFilledButton(text = "Save", onClick = {})
            PpsFilledButton(text = "Save", onClick = {}, enabled = false)
            PpsTextButton(text = "Keep editing", onClick = {})
            PpsFab(contentDescription = "Add alarm", onClick = {})
        }
    }
}

@Preview(name = "Components · Light", heightDp = 1400)
@Composable
private fun ComponentsLightPreview() = ComponentGallery(PpsThemeMode.Light)

@Preview(name = "Components · Dark", heightDp = 1400)
@Composable
private fun ComponentsDarkPreview() = ComponentGallery(PpsThemeMode.Dark)

@Preview(name = "Dialog · Light")
@Composable
private fun DialogLightPreview() {
    PpsTheme(mode = PpsThemeMode.Light) {
        ConfirmDialog(
            title = "Discard changes?",
            confirmText = "Discard",
            safeText = "Keep editing",
            onConfirm = {},
            onSafe = {},
            destructive = true,
        )
    }
}
