package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.reliability.ReliabilityScreen
import com.yawnandpawn.app.ui.reliability.ReliabilityUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 5.4 screenshots of the reliability checklist as the app shows it: all OK, several rows to fix, battery revoked,
 * the camera row and the API 31 exact alarms row, each in Light, Dark and Light at 200 % font scale, plus the test
 * alarm snackbar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class ReliabilityScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun capture(
        name: String,
        state: ReliabilityUiState,
        mode: PpsThemeMode = PpsThemeMode.Light,
        expected: String = "Make sure alarms ring",
    ) = withScreen(mode, content = { ReliabilityScreen(state = state, onIntent = {}) }) {
        composeRule.onNodeWithText(expected, useUnmergedTree = true).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    private val revoked = "Alarm may not ring: battery optimization turned back on"
    private val camera = "Needed for your QR/Barcode check."
    private val exact = "Lets the alarm ring on time."

    @Test
    fun `all OK in Light`() = capture("reliability_all_ok_light", ReliabilitySamples.allOk)

    @Test
    fun `all OK in Dark`() = capture("reliability_all_ok_dark", ReliabilitySamples.allOk, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `all OK in Light at 200 percent`() = capture("reliability_all_ok_light_font200", ReliabilitySamples.allOk)

    @Test
    fun `several to fix in Light`() = capture("reliability_needs_fix_light", ReliabilitySamples.severalNeedFix)

    @Test
    fun `several to fix in Dark`() = capture("reliability_needs_fix_dark", ReliabilitySamples.severalNeedFix, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `several to fix in Light at 200 percent`() = capture("reliability_needs_fix_light_font200", ReliabilitySamples.severalNeedFix)

    @Test
    fun `battery revoked in Light`() = capture("reliability_revoked_battery_light", ReliabilitySamples.revokedBattery, expected = revoked)

    @Test
    fun `battery revoked in Dark`() =
        capture("reliability_revoked_battery_dark", ReliabilitySamples.revokedBattery, PpsThemeMode.Dark, expected = revoked)

    @Test
    @Config(fontScale = 2.0f)
    fun `battery revoked in Light at 200 percent`() =
        capture("reliability_revoked_battery_light_font200", ReliabilitySamples.revokedBattery, expected = revoked)

    @Test
    fun `camera row in Light`() = capture("reliability_camera_light", ReliabilitySamples.cameraVisible, expected = camera)

    @Test
    fun `camera row in Dark`() = capture("reliability_camera_dark", ReliabilitySamples.cameraVisible, PpsThemeMode.Dark, expected = camera)

    @Test
    @Config(fontScale = 2.0f)
    fun `camera row in Light at 200 percent`() =
        capture("reliability_camera_light_font200", ReliabilitySamples.cameraVisible, expected = camera)

    @Test
    fun `API 31 exact alarms in Light`() = capture("reliability_api31_exact_light", ReliabilitySamples.api31Exact, expected = exact)

    @Test
    fun `API 31 exact alarms in Dark`() =
        capture("reliability_api31_exact_dark", ReliabilitySamples.api31Exact, PpsThemeMode.Dark, expected = exact)

    @Test
    @Config(fontScale = 2.0f)
    fun `API 31 exact alarms in Light at 200 percent`() =
        capture("reliability_api31_exact_light_font200", ReliabilitySamples.api31Exact, expected = exact)

    @Test
    fun `test alarm snackbar in Light`() =
        capture(
            "reliability_test_scheduled_light",
            ReliabilitySamples.testScheduled,
            expected = "Lock your phone. We'll ring in 10 seconds.",
        )
}
