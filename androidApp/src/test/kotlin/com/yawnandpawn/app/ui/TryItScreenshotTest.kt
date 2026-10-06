package com.yawnandpawn.app.ui

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.checks.CheckTrial
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.MathTrial
import com.yawnandpawn.app.ui.checksetup.CheckPreviewScreen
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.WakeIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType
import com.yawnandpawn.app.core.checks.Difficulty as CoreDifficulty

/**
 * Story 3.6 screenshots of the Math "Try it" as the editor shows it (Medium, one problem from a fixed seed): running and
 * solved, always Sunrise, at 100% and 200% font scale; and its targets.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class TryItScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val running: CheckTrial = MathTrial.start(Difficulty.Medium, seed = 7L).onIntent(WakeIntent.DigitTapped(4))

    private val solved: CheckTrial =
        MathTrial.start(Difficulty.Medium, seed = 7L).let { trial ->
            val answer = (CoreCheckType.Math.generate(7L, CoreDifficulty.Medium, count = 1) as Puzzle.Math).problems.single().answer
            answer
                .toString()
                .fold<CheckTrial>(
                    trial,
                ) { t, c -> t.onIntent(WakeIntent.DigitTapped(c.digitToInt())) }
                .onIntent(WakeIntent.SubmitAnswer)
        }

    private fun tryIt(
        name: String,
        trial: CheckTrial,
        expected: String,
    ) = withScreen(PpsThemeMode.Light, content = { CheckPreviewScreen(state = trial.state, onIntent = {}, onClose = {}) }) {
        composeRule.onNodeWithText(expected).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `Math running`() = tryIt("try_it_math_running_sunrise", running, "Problem 1 of 1")

    @Test
    @Config(fontScale = 2.0f)
    fun `Math running at 200 percent`() = tryIt("try_it_math_running_sunrise_font200", running, "Problem 1 of 1")

    @Test
    fun `Math solved`() = tryIt("try_it_math_done_sunrise", solved, "Nice. That's how it works.")

    @Test
    @Config(fontScale = 2.0f)
    fun `Math solved at 200 percent`() = tryIt("try_it_math_done_sunrise_font200", solved, "Nice. That's how it works.")

    @Test
    fun `Back is a 48 dp target, the pad keys and Done 64 dp`() {
        withScreen(PpsThemeMode.Light, content = { CheckPreviewScreen(state = running.state, onIntent = {}, onClose = {}) }) {
            val back = composeRule.onNodeWithContentDescription("Back").getBoundsInRoot()
            assertTrue(back.bottom - back.top >= 48.dp && back.right - back.left >= 48.dp, "Back $back")
            val key = composeRule.onNodeWithText("Check").getBoundsInRoot()
            assertTrue(key.bottom - key.top >= 64.dp, "Check $key")
        }
        withScreen(PpsThemeMode.Light, content = { CheckPreviewScreen(state = solved.state, onIntent = {}, onClose = {}) }) {
            val done = composeRule.onNodeWithText("Done").getBoundsInRoot()
            assertTrue(done.bottom - done.top >= 64.dp, "Done $done")
        }
    }
}
