package com.yawnandpawn.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 3.2 screenshots of the Math Check screen as `WakeActivity` maps it ([CheckSamples]), always Sunrise, at 100% and
 * 200% font scale: Grace, Loud after grace, a wrong answer and the last problem. On a 360 × 640 dp phone at 200% the
 * problem scrolls, but "Check" and the snooze control stay on screen, and every key is at least 64 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class CheckScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun check(
        name: String,
        state: CheckUiState,
        assertions: () -> Unit = {},
    ) = withScreen(PpsThemeMode.Light, content = { CheckScreen(state = state, onIntent = {}) }) {
        val math = state.content as CheckContent.Math
        composeRule.onNodeWithText("Problem ${math.problemNumber} of ${math.problemCount}").assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
        assertions()
    }

    private fun key(label: String) = composeRule.onNode(hasText(label) and hasClickAction())

    private fun keysAndSnoozeOnScreen() {
        key("Check").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Snooze unavailable, prices not loaded yet")).assertIsDisplayed()
        (listOf("Check") + (0..9).map { it.toString() }).forEach { label ->
            key(label).assertHeightIsAtLeast(64.dp).assertWidthIsAtLeast(64.dp)
        }
        composeRule.onNode(hasContentDescription("Delete digit")).assertIsDisplayed().assertHeightIsAtLeast(64.dp)
    }

    @Test
    fun `Math in grace`() =
        check("wake_check_math_grace_sunrise", CheckSamples.grace) {
            composeRule.onNodeWithText("Quiet for 14s. Finish before it rings again.").assertExists()
            composeRule.onNode(hasContentDescription("Answer 8")).assertExists()
        }

    @Test
    @Config(fontScale = 2.0f)
    fun `Math in grace at 200 percent`() = check("wake_check_math_grace_sunrise_font200", CheckSamples.grace)

    @Test
    fun `Math loud after grace`() =
        check("wake_check_math_loud_sunrise", CheckSamples.loud) {
            composeRule.onNodeWithText("Time's up. Alarm's back on until you finish.").assertExists()
        }

    @Test
    @Config(fontScale = 2.0f)
    fun `Math loud after grace at 200 percent`() = check("wake_check_math_loud_sunrise_font200", CheckSamples.loud)

    @Test
    fun `Math wrong answer`() =
        check("wake_check_math_wrong_sunrise", CheckSamples.wrong) {
            composeRule.onNodeWithText("Not quite. Try again.").assertExists()
        }

    @Test
    @Config(fontScale = 2.0f)
    fun `Math wrong answer at 200 percent`() = check("wake_check_math_wrong_sunrise_font200", CheckSamples.wrong)

    @Test
    fun `Math last problem`() = check("wake_check_math_last_sunrise", CheckSamples.lastProblem)

    @Test
    @Config(fontScale = 2.0f)
    fun `Math last problem at 200 percent`() = check("wake_check_math_last_sunrise_font200", CheckSamples.lastProblem)

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2.0f)
    fun `on a 360 x 640 phone at 200 percent the keys and snooze stay on screen`() =
        check("wake_check_math_hard_sunrise_w360_h640_font200", CheckSamples.hard) { keysAndSnoozeOnScreen() }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2.0f)
    fun `the wrong answer on a 360 x 640 phone at 200 percent keeps the keys and snooze on screen`() =
        check("wake_check_math_wrong_sunrise_w360_h640_font200", CheckSamples.wrong) { keysAndSnoozeOnScreen() }
}
