package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.onboarding.OnboardingScreen
import com.yawnandpawn.app.ui.onboarding.OnboardingUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 5.10: onboarding steps 1 to 3 as the app wires them (the round 3 layout with string prices): the mission, the
 * disclosure, the base fee with Play's prices and never online (USD with the approximate note), in Light, Dark and Light
 * at 200 % font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class OnboardingScreenshotTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun screenshot(
        name: String,
        state: () -> OnboardingUiState,
        mode: PpsThemeMode,
    ) = withScreen(mode, content = { OnboardingScreen(state = state(), is24Hour = false, onIntent = {}) }) {
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `mission in Light`() = screenshot("onboarding_app_mission_light", { OnboardingSamples.mission }, PpsThemeMode.Light)

    @Test
    fun `mission in Dark`() = screenshot("onboarding_app_mission_dark", { OnboardingSamples.mission }, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `mission in Light at 200 percent`() =
        screenshot("onboarding_app_mission_light_font200", { OnboardingSamples.mission }, PpsThemeMode.Light)

    @Test
    fun `disclosure in Light`() = screenshot("onboarding_app_disclosure_light", { OnboardingSamples.disclosure }, PpsThemeMode.Light)

    @Test
    fun `disclosure in Dark`() = screenshot("onboarding_app_disclosure_dark", { OnboardingSamples.disclosure }, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `disclosure in Light at 200 percent`() =
        screenshot("onboarding_app_disclosure_light_font200", { OnboardingSamples.disclosure }, PpsThemeMode.Light)

    @Test
    fun `base fee loaded in Light`() = screenshot("onboarding_app_base_fee_light", { OnboardingSamples.baseFee }, PpsThemeMode.Light)

    @Test
    fun `base fee loaded in Dark`() = screenshot("onboarding_app_base_fee_dark", { OnboardingSamples.baseFee }, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `base fee loaded in Light at 200 percent`() =
        screenshot("onboarding_app_base_fee_light_font200", { OnboardingSamples.baseFee }, PpsThemeMode.Light)

    @Test
    fun `base fee approximate in Light`() =
        screenshot("onboarding_app_base_fee_approximate_light", { OnboardingSamples.baseFeeApproximate }, PpsThemeMode.Light)

    @Test
    fun `base fee approximate in Dark`() =
        screenshot("onboarding_app_base_fee_approximate_dark", { OnboardingSamples.baseFeeApproximate }, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `base fee approximate in Light at 200 percent`() =
        screenshot("onboarding_app_base_fee_approximate_light_font200", { OnboardingSamples.baseFeeApproximate }, PpsThemeMode.Light)
}
