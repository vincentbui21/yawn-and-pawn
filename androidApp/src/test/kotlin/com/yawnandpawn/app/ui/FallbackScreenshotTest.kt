package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.FallbackPickerScreen
import com.yawnandpawn.app.ui.wake.GraceState
import com.yawnandpawn.app.ui.wake.SnoozeOffer
import com.yawnandpawn.app.ui.wake.SnoozeUnavailableReason
import com.yawnandpawn.app.ui.wake.fallbackPickerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Story 3.9 screenshots: a camera check (the `FakeCameraCheck` stand-in shows the approved QR/Barcode screen with the
 * camera unavailable) with "Can't do this check?", and the Fallback check picker as production builds it (Math only until
 * Stories 3.7 and 3.8), in Sunrise at 100% and 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class FallbackScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val cameraCheck =
        CheckUiState(
            grace = GraceState.Running(secondsLeft = 14, totalSeconds = 20),
            content = CheckContent.QrBarcode(cameraAvailable = false),
            snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded),
            showFallbackLink = true,
        )

    private fun linkShot(name: String) =
        withScreen(PpsThemeMode.Light, content = { CheckScreen(state = cameraCheck, onIntent = {}) }) {
            composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
            val link = composeRule.onNode(hasText("Can't do this check?") and hasClickAction())
            link.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }

    private fun pickerShot(name: String) =
        withScreen(PpsThemeMode.Light, content = { FallbackPickerScreen(state = fallbackPickerUiState(), onIntent = {}) }) {
            composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
            composeRule.onNodeWithText("Pick a fallback check").assertExists()
            composeRule.onNode(hasContentDescription("Back to check")).assertExists()
            // The pickable checks without the camera, Math first (Story 3.5's types), each one button.
            listOf("Math", "Word Unscramble", "Memory Sequence").forEach { name ->
                val card = composeRule.onAllNodes(hasClickAction() and hasText(name)).fetchSemanticsNodes().single()
                assertEquals(Role.Button, card.config.getOrNull(SemanticsProperties.Role), name)
            }
        }

    @Test
    fun `camera check with the fallback link`() = linkShot("wake_fallback_link_sunrise")

    @Test
    @Config(fontScale = 2.0f)
    fun `camera check with the fallback link at 200 percent`() = linkShot("wake_fallback_link_sunrise_font200")

    @Test
    fun `fallback check picker`() = pickerShot("wake_fallback_picker_sunrise")

    @Test
    @Config(fontScale = 2.0f)
    fun `fallback check picker at 200 percent`() = pickerShot("wake_fallback_picker_sunrise_font200")
}
