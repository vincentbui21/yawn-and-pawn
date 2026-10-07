package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.CheckInput
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.mathCheckUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertNotNull

/**
 * Story 3.11 screenshots: a ring before the first unlock whose QR/Barcode check became Math shows the Sunrise
 * `note-inline` "Your phone restarted, so today's check is Math." on the Ringing screen (with the lock snooze) and above
 * the Math problem, mapped from the session as `WakeActivity` maps it, at 100% and 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class DirectBootNoteScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val note = "Your phone restarted, so today's check is Math."

    private val check: CheckUiState =
        assertNotNull(
            mathCheckUiState(
                SessionState.Loud(RingingSamples.directBootSession),
                RingingSamples.directBootAvailability,
                TimeSnapshot(wallMillis = 1_800_000_000_000, elapsedMillis = 1_000_000, bootCount = 1),
                CheckInput(),
            ),
        )

    private fun ringing(name: String) =
        withScreen(PpsThemeMode.Light, content = { RingingScreen(state = RingingSamples.directBoot, is24Hour = false, onIntent = {}) }) {
            composeRule.onNodeWithText(note).assertExists()
            composeRule.onNodeWithContentDescription("Snooze unavailable, Unlock your phone to snooze").assertExists()
            composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
        }

    private fun math(name: String) =
        withScreen(PpsThemeMode.Light, content = { CheckScreen(state = check, onIntent = {}) }) {
            composeRule.onNodeWithText(note).assertExists()
            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
        }

    @Test
    fun `ringing before the first unlock with the Direct Boot note`() = ringing("wake_ringing_direct_boot_sunrise")

    @Test
    @Config(fontScale = 2.0f)
    fun `ringing before the first unlock with the Direct Boot note at 200 percent`() = ringing("wake_ringing_direct_boot_sunrise_font200")

    @Test
    fun `the Math check with the Direct Boot note`() = math("wake_check_math_direct_boot_sunrise")

    @Test
    @Config(fontScale = 2.0f)
    fun `the Math check with the Direct Boot note at 200 percent`() = math("wake_check_math_direct_boot_sunrise_font200")
}
