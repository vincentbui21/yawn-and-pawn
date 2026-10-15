package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.payments.ProblemWithChargeFlow
import com.yawnandpawn.app.ui.payments.ProblemWithChargeUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 4.17 screenshots of "Problem with a charge?" as the app shows it: the screen with and without charges (the same
 * screen: charges show only in the picker), the "Email support" picker, and the no-browser and no-email-app snackbars,
 * in Light and Dark, the primary states also at 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class ProblemWithChargeScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun capture(
        name: String,
        state: ProblemWithChargeUiState,
        mode: PpsThemeMode = PpsThemeMode.Light,
    ) = withScreen(mode, content = { ProblemWithChargeFlow(state = state, is24Hour = false, onIntent = {}) }) {
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `with charges light`() = capture("problem_with_charge_light", ProblemWithChargeSamples.withCharges)

    @Test
    fun `with charges dark`() = capture("problem_with_charge_dark", ProblemWithChargeSamples.withCharges, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `with charges at 200 percent`() = capture("problem_with_charge_light_font200", ProblemWithChargeSamples.withCharges)

    @Test
    fun `no charges light`() = capture("problem_with_charge_no_charges_light", ProblemWithChargeSamples.noCharges)

    @Test
    fun `no charges dark`() = capture("problem_with_charge_no_charges_dark", ProblemWithChargeSamples.noCharges, PpsThemeMode.Dark)

    @Test
    fun `no charges with no email app light`() =
        capture("problem_with_charge_no_email_app_light", ProblemWithChargeSamples.noChargesNoEmailApp)

    @Test
    fun `no charges with no email app dark`() =
        capture("problem_with_charge_no_email_app_dark", ProblemWithChargeSamples.noChargesNoEmailApp, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `no charges with no email app at 200 percent`() =
        capture("problem_with_charge_no_email_app_light_font200", ProblemWithChargeSamples.noChargesNoEmailApp)

    @Test
    fun `no browser light`() = capture("problem_with_charge_no_browser_light", ProblemWithChargeSamples.noBrowser)

    @Test
    fun `picker light`() = capture("problem_with_charge_picker_light", ProblemWithChargeSamples.picker)

    @Test
    fun `picker dark`() = capture("problem_with_charge_picker_dark", ProblemWithChargeSamples.picker, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `picker at 200 percent`() = capture("problem_with_charge_picker_light_font200", ProblemWithChargeSamples.picker)

    @Test
    fun `picker with no email app light`() =
        capture("problem_with_charge_picker_no_email_app_light", ProblemWithChargeSamples.pickerNoEmailApp)

    @Test
    fun `picker with no email app dark`() =
        capture("problem_with_charge_picker_no_email_app_dark", ProblemWithChargeSamples.pickerNoEmailApp, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `picker with no email app at 200 percent`() =
        capture("problem_with_charge_picker_no_email_app_light_font200", ProblemWithChargeSamples.pickerNoEmailApp)
}
