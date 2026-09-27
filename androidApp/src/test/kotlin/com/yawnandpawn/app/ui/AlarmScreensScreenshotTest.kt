package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.alarms.AlarmsScreen
import com.yawnandpawn.app.ui.alarms.AlarmsUiState
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 1.8 screenshots: the Alarm editor (new alarm, edit alarm, one-time "Rings tomorrow" note, discard dialog)
 * and the Alarms route (empty, interim list), each in Light, Dark and Light at 200% font scale. The screens are
 * tall enough that the whole scrolling editor is in the picture, so clipping at 200% shows up in the baseline.
 * 12-hour clock (the Robolectric default locale is en-US), so the AM/PM selector is in the picture too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1400dp-mdpi")
class AlarmScreensScreenshotTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun editor(
        name: String,
        state: EditorUiState,
        mode: PpsThemeMode,
    ) = withScreen(mode, content = { AlarmEditorScreen(state = state, is24Hour = false, onIntent = {}) }) {
        composeRule.onNodeWithText("Save").assertExists()
        if (state.showDiscardDialog) {
            composeRule.onNodeWithText("Discard changes?").assertExists()
            capture(name, allWindows = true)
        } else {
            capture(name)
        }
    }

    private fun alarms(
        name: String,
        state: AlarmsUiState,
        mode: PpsThemeMode,
    ) = withScreen(mode, content = { AlarmsScreen(state = state, is24Hour = false, onAddAlarm = {}, onEditAlarm = {}) }) {
        composeRule.onNodeWithText("Yawn & Pawn").assertExists()
        capture(name)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(
        name: String,
        allWindows: Boolean = false,
    ) {
        val path = "src/test/screenshots/$name.png"
        // A dialog is its own window: capture the whole screen so it is in the picture.
        if (allWindows) {
            captureScreenRoboImage(path, roborazziOptions = screenshotOptions)
        } else {
            composeRule.onRoot().captureRoboImage(path, roborazziOptions = screenshotOptions)
        }
    }

    @Test
    fun `new alarm in Light`() = editor("alarm_editor_new_light", EditorSamples.newAlarm, PpsThemeMode.Light)

    @Test
    fun `new alarm in Dark`() = editor("alarm_editor_new_dark", EditorSamples.newAlarm, PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `new alarm in Light at 200 percent`() = editor("alarm_editor_new_light_font200", EditorSamples.newAlarm, PpsThemeMode.Light)

    @Test
    fun `edit alarm in Light`() = editor("alarm_editor_edit_light", EditorSamples.editAlarm, PpsThemeMode.Light)

    @Test
    fun `edit alarm in Dark`() = editor("alarm_editor_edit_dark", EditorSamples.editAlarm, PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `edit alarm in Light at 200 percent`() = editor("alarm_editor_edit_light_font200", EditorSamples.editAlarm, PpsThemeMode.Light)

    @Test
    fun `rings tomorrow note in Light`() = editor("alarm_editor_tomorrow_light", EditorSamples.ringsTomorrow, PpsThemeMode.Light)

    @Test
    fun `rings tomorrow note in Dark`() = editor("alarm_editor_tomorrow_dark", EditorSamples.ringsTomorrow, PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `rings tomorrow note in Light at 200 percent`() =
        editor("alarm_editor_tomorrow_light_font200", EditorSamples.ringsTomorrow, PpsThemeMode.Light)

    @Test
    fun `discard dialog in Light`() = editor("alarm_editor_discard_light", EditorSamples.discardDialog, PpsThemeMode.Light)

    @Test
    fun `discard dialog in Dark`() = editor("alarm_editor_discard_dark", EditorSamples.discardDialog, PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `discard dialog in Light at 200 percent`() =
        editor("alarm_editor_discard_light_font200", EditorSamples.discardDialog, PpsThemeMode.Light)

    @Test
    fun `empty Alarms in Light`() = alarms("alarms_empty_light", EditorSamples.emptyAlarms, PpsThemeMode.Light)

    @Test
    fun `empty Alarms in Dark`() = alarms("alarms_empty_dark", EditorSamples.emptyAlarms, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `empty Alarms in Light at 200 percent`() = alarms("alarms_empty_light_font200", EditorSamples.emptyAlarms, PpsThemeMode.Light)

    @Test
    fun `Alarms list in Light`() = alarms("alarms_list_light", EditorSamples.someAlarms, PpsThemeMode.Light)

    @Test
    fun `Alarms list in Dark`() = alarms("alarms_list_dark", EditorSamples.someAlarms, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `Alarms list in Light at 200 percent`() = alarms("alarms_list_light_font200", EditorSamples.someAlarms, PpsThemeMode.Light)

    @Test
    fun `label error in Light`() = editor("alarm_editor_label_error_light", EditorSamples.labelError, PpsThemeMode.Light)

    @Test
    fun `label error in Dark`() = editor("alarm_editor_label_error_dark", EditorSamples.labelError, PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `label error in Light at 200 percent`() =
        editor("alarm_editor_label_error_light_font200", EditorSamples.labelError, PpsThemeMode.Light)

    /** The narrowest supported phone width: the seven day chips stay on one line. */
    @Test
    @Config(qualifiers = "+w360dp")
    fun `new alarm in Light on a 360 dp screen`() = editor("alarm_editor_new_light_w360", EditorSamples.newAlarm, PpsThemeMode.Light)
}
