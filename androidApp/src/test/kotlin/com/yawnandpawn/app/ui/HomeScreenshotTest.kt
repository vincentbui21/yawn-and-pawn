package com.yawnandpawn.app.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 1.9 screenshots of Home in the app shell (the glass nav capsule): empty, one alarm, many, all off, load failure,
 * the delete dialog and the long-press menu, each in Light, Dark and Light at 200% font scale. 12-hour clock (the
 * Robolectric default locale is en-US).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class HomeScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun home(
        name: String,
        state: HomeUiState,
        mode: PpsThemeMode,
        longPressTime: String? = null,
    ) = withScreen(
        mode,
        content = { AppShell(selected = AppTab.Alarms, onSelect = {}) { HomeScreen(state = state, is24Hour = false, onIntent = {}) } },
    ) {
        composeRule.onNodeWithText("Yawn & Pawn").assertExists()
        // Story 1.16: the missed note, verbatim from EXPERIENCE.md (12-hour clock), with its Dismiss action.
        if (state.missedAlarmAt != null) {
            composeRule.onNodeWithText("Your 6:00 AM alarm stopped after 30 minutes. Logged as missed.").assertExists()
            composeRule.onNode(hasContentDescription("Dismiss") and hasClickAction()).assertExists()
        }
        longPressTime?.let { time ->
            composeRule.onNode(hasText(time) and hasClickAction()).performTouchInput { longClick() }
            composeRule.onNodeWithText("Duplicate").assertExists()
        }
        if (state.deleteDialog != null) composeRule.onNode(isDialog()).assertExists()
        capture(name, allWindows = longPressTime != null || state.deleteDialog != null)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(
        name: String,
        allWindows: Boolean,
    ) {
        val path = "src/test/screenshots/$name.png"
        // A dialog or a menu is its own window: capture the whole screen so it is in the picture.
        if (allWindows) {
            captureScreenRoboImage(path, roborazziOptions = screenshotOptions)
        } else {
            composeRule.onRoot().captureRoboImage(path, roborazziOptions = screenshotOptions)
        }
    }

    @Test
    fun `empty Home in Light`() = home("home_empty_light", HomeSamples.empty, PpsThemeMode.Light)

    @Test
    fun `empty Home in Dark`() = home("home_empty_dark", HomeSamples.empty, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `empty Home in Light at 200 percent`() = home("home_empty_light_font200", HomeSamples.empty, PpsThemeMode.Light)

    @Test
    fun `one alarm in Light`() = home("home_one_light", HomeSamples.one, PpsThemeMode.Light)

    @Test
    fun `one alarm in Dark`() = home("home_one_dark", HomeSamples.one, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `one alarm in Light at 200 percent`() = home("home_one_light_font200", HomeSamples.one, PpsThemeMode.Light)

    @Test
    fun `many alarms in Light`() = home("home_many_light", HomeSamples.many, PpsThemeMode.Light)

    @Test
    fun `many alarms in Dark`() = home("home_many_dark", HomeSamples.many, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `many alarms in Light at 200 percent`() = home("home_many_light_font200", HomeSamples.many, PpsThemeMode.Light)

    @Test
    fun `all alarms off in Light`() = home("home_all_off_light", HomeSamples.allDisabled, PpsThemeMode.Light)

    @Test
    fun `all alarms off in Dark`() = home("home_all_off_dark", HomeSamples.allDisabled, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `all alarms off in Light at 200 percent`() = home("home_all_off_light_font200", HomeSamples.allDisabled, PpsThemeMode.Light)

    @Test
    fun `load failure in Light`() = home("home_load_failed_light", HomeSamples.loadFailed, PpsThemeMode.Light)

    @Test
    fun `load failure in Dark`() = home("home_load_failed_dark", HomeSamples.loadFailed, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `load failure in Light at 200 percent`() = home("home_load_failed_light_font200", HomeSamples.loadFailed, PpsThemeMode.Light)

    @Test
    fun `delete dialog in Light`() = home("home_delete_dialog_light", HomeSamples.deleteDialog, PpsThemeMode.Light)

    @Test
    fun `delete dialog in Dark`() = home("home_delete_dialog_dark", HomeSamples.deleteDialog, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `delete dialog in Light at 200 percent`() = home("home_delete_dialog_light_font200", HomeSamples.deleteDialog, PpsThemeMode.Light)

    @Test
    fun `long-press menu in Light`() = home("home_card_menu_light", HomeSamples.many, PpsThemeMode.Light, longPressTime = "6:30 AM")

    @Test
    fun `long-press menu in Dark`() = home("home_card_menu_dark", HomeSamples.many, PpsThemeMode.Dark, longPressTime = "6:30 AM")

    @Test
    @Config(fontScale = 2.0f)
    fun `long-press menu in Light at 200 percent`() =
        home("home_card_menu_light_font200", HomeSamples.many, PpsThemeMode.Light, longPressTime = "6:30 AM")

    @Test
    fun `open failed snackbar in Light`() = home("home_open_failed_light", HomeSamples.openFailed, PpsThemeMode.Light)

    @Test
    fun `missed note in Light`() = home("home_missed_note_light", HomeSamples.missedNote, PpsThemeMode.Light)

    @Test
    fun `missed note in Dark`() = home("home_missed_note_dark", HomeSamples.missedNote, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `missed note in Light at 200 percent`() = home("home_missed_note_light_font200", HomeSamples.missedNote, PpsThemeMode.Light)
}
