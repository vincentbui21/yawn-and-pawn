package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
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
 * Story 2.6 screenshots of the session lock as the app shows it (`Route.SessionInProgress`): the approved `home-session`
 * screen, the Home header and `panel-session-in-progress` with no nav capsule, in Light, Dark and Light at 200%.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class SessionInProgressScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun lock(
        name: String,
        mode: PpsThemeMode,
    ) = withScreen(
        mode,
        content = {
            AppShell(selected = AppTab.Alarms, onSelect = {}, showNavBar = false) {
                HomeScreen(state = HomeUiState(sessionInProgress = true), is24Hour = false, onIntent = {})
            }
        },
    ) {
        composeRule.onNodeWithText("Alarm in progress").assertExists()
        composeRule.onNodeWithText("Back to alarm").assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `session lock in Light`() = lock("session_in_progress_light", PpsThemeMode.Light)

    @Test
    fun `session lock in Dark`() = lock("session_in_progress_dark", PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `session lock in Light at 200 percent`() = lock("session_in_progress_light_font200", PpsThemeMode.Light)
}
