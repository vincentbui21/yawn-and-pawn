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
 * Stories 5.10 to 5.12: onboarding steps 1 to 8 as the app wires them (the round 3 layout): the mission, the disclosure,
 * the base fee with Play's prices and never online (USD with the approximate note), the first alarm (rings tomorrow), the
 * checks with QR/Barcode selected and with none, the checklist with two "Fix" rows, the usage stats choice and the test
 * alarm (ready, waiting, not locked), in Light, Dark and Light at 200 % font scale.
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

    @Test
    fun `first alarm in Light`() = screenshot("onboarding_app_first_alarm_light", { OnboardingSamples.firstAlarm }, PpsThemeMode.Light)

    @Test
    fun `first alarm in Dark`() = screenshot("onboarding_app_first_alarm_dark", { OnboardingSamples.firstAlarm }, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `first alarm in Light at 200 percent`() =
        screenshot("onboarding_app_first_alarm_light_font200", { OnboardingSamples.firstAlarm }, PpsThemeMode.Light)

    @Test
    fun `checks with QR selected in Light`() =
        screenshot("onboarding_app_checks_qr_light", { OnboardingSamples.checksQr }, PpsThemeMode.Light)

    @Test
    fun `checks with QR selected in Dark`() = screenshot("onboarding_app_checks_qr_dark", { OnboardingSamples.checksQr }, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `checks with QR selected in Light at 200 percent`() =
        screenshot("onboarding_app_checks_qr_light_font200", { OnboardingSamples.checksQr }, PpsThemeMode.Light)

    @Test
    fun `checks none in Light`() = screenshot("onboarding_app_checks_none_light", { OnboardingSamples.checksNone }, PpsThemeMode.Light)

    @Test
    fun `checks none in Dark`() = screenshot("onboarding_app_checks_none_dark", { OnboardingSamples.checksNone }, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `checks none in Light at 200 percent`() =
        screenshot("onboarding_app_checks_none_light_font200", { OnboardingSamples.checksNone }, PpsThemeMode.Light)

    @Test
    fun `checklist with two Fix rows in Light`() =
        screenshot("onboarding_app_checklist_light", {
            OnboardingSamples.checklist
        }, PpsThemeMode.Light)

    @Test
    fun `checklist with two Fix rows in Dark`() =
        screenshot("onboarding_app_checklist_dark", {
            OnboardingSamples.checklist
        }, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `checklist with two Fix rows in Light at 200 percent`() =
        screenshot("onboarding_app_checklist_light_font200", { OnboardingSamples.checklist }, PpsThemeMode.Light)

    @Test
    fun `usage stats in Light`() = screenshot("onboarding_app_usage_stats_light", { OnboardingSamples.usageStats }, PpsThemeMode.Light)

    @Test
    fun `usage stats in Dark`() = screenshot("onboarding_app_usage_stats_dark", { OnboardingSamples.usageStats }, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `usage stats in Light at 200 percent`() =
        screenshot("onboarding_app_usage_stats_light_font200", { OnboardingSamples.usageStats }, PpsThemeMode.Light)

    // Story 5.12: step 8, the test alarm.

    @Test
    fun `test alarm ready in Light`() = screenshot("onboarding_app_test_ready_light", { OnboardingSamples.testReady }, PpsThemeMode.Light)

    @Test
    fun `test alarm ready in Dark`() = screenshot("onboarding_app_test_ready_dark", { OnboardingSamples.testReady }, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `test alarm ready in Light at 200 percent`() =
        screenshot("onboarding_app_test_ready_light_font200", { OnboardingSamples.testReady }, PpsThemeMode.Light)

    @Test
    fun `test alarm waiting in Light`() =
        screenshot("onboarding_app_test_waiting_light", { OnboardingSamples.testWaiting }, PpsThemeMode.Light)

    @Test
    fun `test alarm waiting in Dark`() =
        screenshot("onboarding_app_test_waiting_dark", { OnboardingSamples.testWaiting }, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `test alarm waiting in Light at 200 percent`() =
        screenshot("onboarding_app_test_waiting_light_font200", { OnboardingSamples.testWaiting }, PpsThemeMode.Light)

    @Test
    fun `test alarm not locked in Light`() =
        screenshot("onboarding_app_test_not_locked_light", { OnboardingSamples.testNotLocked }, PpsThemeMode.Light)

    @Test
    fun `test alarm not locked in Dark`() =
        screenshot("onboarding_app_test_not_locked_dark", { OnboardingSamples.testNotLocked }, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `test alarm not locked in Light at 200 percent`() =
        screenshot("onboarding_app_test_not_locked_light_font200", { OnboardingSamples.testNotLocked }, PpsThemeMode.Light)
}
