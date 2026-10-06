package com.yawnandpawn.app.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
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
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    @Config(fontScale = 2.0f)
    fun `Quiet time sub-screen in Light at 200 percent`() = quietTime("alarm_editor_quiet_time_light_font200", PpsThemeMode.Light)

    @Test
    @Config(fontScale = 2.0f)
    fun `Quiet time sub-screen in Dark at 200 percent`() = quietTime("alarm_editor_quiet_time_dark_font200", PpsThemeMode.Dark)

    /** Story 3.4 review fix: the production editor (no full sections) has the Quiet time row with the form's value. */
    @Test
    fun `the production editor's Quiet time row shows the form's seconds and opens its sub-screen`() {
        val intents = mutableListOf<EditorIntent>()
        val state = EditorSamples.newAlarm.copy(form = EditorSamples.newAlarm.form.copy(graceSeconds = 27))
        assertNull(state.full, "the production editor")
        withScreen(PpsThemeMode.Light, content = { AlarmEditorScreen(state = state, is24Hour = false, onIntent = { intents += it }) }) {
            composeRule
                .onNode(hasText("Quiet time") and hasText("27 seconds") and hasClickAction())
                .performClick()
            assertEquals(listOf<EditorIntent>(EditorIntent.PaneOpened(EditorPane.QuietTime)), intents)
        }
    }

    /** Story 3.4: the slider says its value as "{seconds} seconds", and the switch is on by default. */
    private fun quietTime(
        name: String,
        mode: PpsThemeMode,
    ) = withScreen(mode, content = { AlarmEditorScreen(state = EditorSamples.quietTimePane, is24Hour = false, onIntent = {}) }) {
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "25 seconds")).assertExists()
        composeRule.onNode(hasText("Vibrate during quiet time")).assertExists()
        composeRule.onNode(isToggleable()).assertIsOn()
        capture(name)
    }

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

    /** Story 1.18: "Test alarm" under the cards and its snackbar over the editor, above the pill. */
    private fun testSnackbar(
        name: String,
        mode: PpsThemeMode,
    ) = withScreen(
        mode,
        content = {
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { snackbar.showSnackbar(TEST_SNACKBAR, duration = SnackbarDuration.Indefinite) }
            AlarmEditorScreen(state = EditorSamples.newAlarm, is24Hour = false, onIntent = {}, snackbarHostState = snackbar)
        },
    ) {
        composeRule.onNode(hasText("Test alarm") and hasClickAction()).assertExists()
        composeRule.onNodeWithText(TEST_SNACKBAR).assertExists()
        capture(name)
    }

    @Test
    fun `test alarm snackbar in Light`() = testSnackbar("alarm_editor_test_snackbar_light", PpsThemeMode.Light)

    @Test
    fun `test alarm snackbar in Dark`() = testSnackbar("alarm_editor_test_snackbar_dark", PpsThemeMode.Dark)

    @Test
    @Config(qualifiers = "+h2400dp", fontScale = 2.0f)
    fun `test alarm snackbar in Light at 200 percent`() = testSnackbar("alarm_editor_test_snackbar_light_font200", PpsThemeMode.Light)

    private companion object {
        /** EXPERIENCE.md, verbatim. */
        const val TEST_SNACKBAR = "Lock your phone. We'll ring in 10 seconds."
    }
}
