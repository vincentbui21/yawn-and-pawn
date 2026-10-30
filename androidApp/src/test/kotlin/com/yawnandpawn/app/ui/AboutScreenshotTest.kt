package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.about.AboutScreen
import com.yawnandpawn.app.ui.about.AboutUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.1.7 polish screenshots of About (the app icon, name and version over the Website, Contact support, Privacy policy
 * and Terms rows) in Light, Dark and Light at 200 % font scale, and with the no-email-app note.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class AboutScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val about = AboutUiState(version = "0.1.7", website = "yawnandpawn.com", supportEmail = "support@yawnandpawn.com")

    private fun capture(
        name: String,
        state: AboutUiState = about,
        mode: PpsThemeMode = PpsThemeMode.Light,
    ) = withScreen(mode, content = { AboutScreen(state = state, onIntent = {}, onBack = {}) }) {
        composeRule.onNodeWithText("Version 0.1.7", useUnmergedTree = true).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `About in Light`() = capture("about_light")

    @Test
    fun `About in Dark`() = capture("about_dark", mode = PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `About in Light at 200 percent`() = capture("about_light_font200")

    @Test
    fun `About with no email app in Light`() =
        capture("about_no_email_app_light", about.copy(noEmailAppAddress = "support@yawnandpawn.com"))
}
