package com.yawnandpawn.app.ui

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 1.8 and 1.9 screenshots of the Alarm editor (new alarm, edit alarm, one-time "Rings tomorrow" note, discard
 * dialog, and for a stored alarm the overflow button, its menu and the delete dialog), each in Light, Dark and Light at
 * 200% font scale. Home is in HomeScreenshotTest. The screens are
 * tall enough that the whole scrolling editor is in the picture, so clipping at 200% shows up in the baseline.
 * 12-hour clock (the Robolectric default locale is en-US), so the AM/PM selector is in the picture too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1400dp-mdpi")
class AlarmScreensScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun editor(
        name: String,
        state: EditorUiState,
        mode: PpsThemeMode,
    ) = withScreen(mode, content = { AlarmEditorScreen(state = state, is24Hour = false, onIntent = {}) }) {
        if (state.pane == EditorPane.Main) composeRule.onNodeWithText("Save").assertExists()
        if (state.showDiscardDialog || state.deleteDialogTime != null) {
            composeRule.onNode(isDialog()).assertExists()
            capture(name, allWindows = true)
        } else {
            capture(name)
        }
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
    fun `label error in Light`() = editor("alarm_editor_label_error_light", EditorSamples.labelError, PpsThemeMode.Light)

    @Test
    fun `label error in Dark`() = editor("alarm_editor_label_error_dark", EditorSamples.labelError, PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `label error in Light at 200 percent`() =
        editor("alarm_editor_label_error_light_font200", EditorSamples.labelError, PpsThemeMode.Light)

    @Test
    fun `Sound sub-screen in Light`() = editor("alarm_editor_sound_light", EditorSamples.soundPane, PpsThemeMode.Light)

    @Test
    fun `Sound sub-screen in Dark`() = editor("alarm_editor_sound_dark", EditorSamples.soundPane, PpsThemeMode.Dark)

    @Test
    fun `Snooze sub-screen in Light`() = editor("alarm_editor_snooze_light", EditorSamples.snoozePane, PpsThemeMode.Light)

    @Test
    fun `Snooze sub-screen in Dark`() = editor("alarm_editor_snooze_dark", EditorSamples.snoozePane, PpsThemeMode.Dark)

    /** The narrowest supported phone width: the seven day chips stay on one line. */
    @Test
    @Config(qualifiers = "+w360dp")
    fun `new alarm in Light on a 360 dp screen`() = editor("alarm_editor_new_light_w360", EditorSamples.editAlarm, PpsThemeMode.Light)

    @Test
    fun `stored alarm with the overflow button in Light`() =
        editor("alarm_editor_stored_light", EditorSamples.editStored, PpsThemeMode.Light)

    @Test
    fun `stored alarm with the overflow button in Dark`() = editor("alarm_editor_stored_dark", EditorSamples.editStored, PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `stored alarm with the overflow button in Light at 200 percent`() =
        editor("alarm_editor_stored_light_font200", EditorSamples.editStored, PpsThemeMode.Light)

    private fun overflowMenu(
        name: String,
        mode: PpsThemeMode,
    ) = withScreen(mode, content = { AlarmEditorScreen(state = EditorSamples.editStored, is24Hour = false, onIntent = {}) }) {
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Duplicate").assertExists()
        capture(name, allWindows = true)
    }

    @Test
    fun `overflow menu in Light`() = overflowMenu("alarm_editor_overflow_menu_light", PpsThemeMode.Light)

    @Test
    fun `overflow menu in Dark`() = overflowMenu("alarm_editor_overflow_menu_dark", PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `overflow menu in Light at 200 percent`() = overflowMenu("alarm_editor_overflow_menu_light_font200", PpsThemeMode.Light)

    @Test
    fun `delete dialog in the editor in Light`() = editor("alarm_editor_delete_light", EditorSamples.editDeleteDialog, PpsThemeMode.Light)

    @Test
    fun `delete dialog in the editor in Dark`() = editor("alarm_editor_delete_dark", EditorSamples.editDeleteDialog, PpsThemeMode.Dark)
}
