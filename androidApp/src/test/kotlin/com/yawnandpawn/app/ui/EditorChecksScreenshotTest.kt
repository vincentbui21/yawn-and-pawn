package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 3.5 screenshots of the editor's checks: the main screen's Wake-up check row (and its error), the Wake-up check
 * sub-screen (the Check picker) with one check, several in All mode and none with the error, and Math's Check setup, in
 * Light, Dark and Light at 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1400dp-mdpi")
class EditorChecksScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun editor(
        name: String,
        state: EditorUiState,
        mode: PpsThemeMode,
        expected: String,
    ) = withScreen(mode, content = { AlarmEditorScreen(state = state, is24Hour = false, onIntent = {}) }) {
        composeRule.onNodeWithText(expected).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `main screen with the Wake-up check row in Light`() =
        editor("editor_checks_main_light", EditorCheckSamples.newAlarm, PpsThemeMode.Light, "Wake-up check")

    @Test
    fun `main screen with the Wake-up check row in Dark`() =
        editor("editor_checks_main_dark", EditorCheckSamples.newAlarm, PpsThemeMode.Dark, "Wake-up check")

    @Test
    @Config(fontScale = 2.0f)
    fun `main screen with no check and its error in Light at 200 percent`() =
        editor("editor_checks_main_none_light_font200", EditorCheckSamples.mainNoCheck, PpsThemeMode.Light, "Pick at least one check.")

    @Test
    fun `main screen with no check and its error in Light`() =
        editor("editor_checks_main_none_light", EditorCheckSamples.mainNoCheck, PpsThemeMode.Light, "Pick at least one check.")

    @Test
    fun `one check in Light`() = editor("editor_checks_one_light", EditorCheckSamples.oneCheck, PpsThemeMode.Light, "Your checks")

    @Test
    fun `one check in Dark`() = editor("editor_checks_one_dark", EditorCheckSamples.oneCheck, PpsThemeMode.Dark, "Your checks")

    @Test
    @Config(fontScale = 2.0f)
    fun `one check in Light at 200 percent`() =
        editor("editor_checks_one_light_font200", EditorCheckSamples.oneCheck, PpsThemeMode.Light, "Your checks")

    @Test
    fun `several checks in All mode in Light`() = editor("editor_checks_all_light", EditorCheckSamples.several, PpsThemeMode.Light, "Mode")

    @Test
    fun `several checks in All mode in Dark`() = editor("editor_checks_all_dark", EditorCheckSamples.several, PpsThemeMode.Dark, "Mode")

    @Test
    @Config(fontScale = 2.0f)
    fun `several checks in All mode in Light at 200 percent`() =
        editor("editor_checks_all_light_font200", EditorCheckSamples.several, PpsThemeMode.Light, "Mode")

    @Test
    fun `no check with the error in Light`() =
        editor("editor_checks_none_light", EditorCheckSamples.none, PpsThemeMode.Light, "Pick at least one check.")

    @Test
    fun `no check with the error in Dark`() =
        editor("editor_checks_none_dark", EditorCheckSamples.none, PpsThemeMode.Dark, "Pick at least one check.")

    @Test
    @Config(fontScale = 2.0f)
    fun `no check with the error in Light at 200 percent`() =
        editor("editor_checks_none_light_font200", EditorCheckSamples.none, PpsThemeMode.Light, "Pick at least one check.")

    @Test
    fun `Math check setup in Light`() =
        editor("editor_check_setup_math_light", EditorCheckSamples.mathSetup, PpsThemeMode.Light, "Problems")

    @Test
    fun `Math check setup in Dark`() = editor("editor_check_setup_math_dark", EditorCheckSamples.mathSetup, PpsThemeMode.Dark, "Problems")

    @Test
    @Config(fontScale = 2.0f)
    fun `Math check setup in Light at 200 percent`() =
        editor("editor_check_setup_math_light_font200", EditorCheckSamples.mathSetup, PpsThemeMode.Light, "Problems")
}
