package com.yawnandpawn.app.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.components.PROGRESS_SKELETON_TAG
import com.yawnandpawn.app.ui.progress.ProgressScreen
import com.yawnandpawn.app.ui.progress.ProgressUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 6.5 screenshots of Progress as the app shows it: empty, Test only, the F9 month, two currencies, a tapped dot, a
 * streak over 30, the skeleton (after 300 ms), the slow-loading text (after 3 s) and the load failure, each in Light,
 * Dark and Light at 200% font scale, on a window tall enough for the whole screen (Money paid included). The entry
 * animation has finished (the test clock runs it out) before each capture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1500dp-mdpi")
class ProgressScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun capture(
        name: String,
        state: ProgressUiState,
        mode: PpsThemeMode = PpsThemeMode.Light,
        loadingFor: Long = 0,
    ) = withScreen(mode, content = { ProgressScreen(state = state, onIntent = {}) }) {
        composeRule.waitForIdle()
        if (loadingFor > 0) {
            composeRule.mainClock.advanceTimeBy(loadingFor)
            composeRule.waitForIdle()
            if (loadingFor < SLOW_WAIT_MILLIS) {
                composeRule.onNodeWithTag(PROGRESS_SKELETON_TAG, useUnmergedTree = true).assertExists()
            } else {
                composeRule.onNode(hasText("Still loading your progress."), useUnmergedTree = true).assertExists()
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    private fun all(
        name: String,
        state: ProgressUiState,
        loadingFor: Long = 0,
    ) {
        capture("${name}_light", state, loadingFor = loadingFor)
        capture("${name}_dark", state, PpsThemeMode.Dark, loadingFor = loadingFor)
    }

    @Test
    fun `empty light and dark`() = all("progress_empty", ProgressSamples.empty)

    @Test
    @Config(qualifiers = "w411dp-h2800dp-mdpi", fontScale = 2.0f)
    fun `empty at 200 percent`() = capture("progress_empty_light_font200", ProgressSamples.empty)

    @Test
    fun `test only light and dark`() = all("progress_test_only", ProgressSamples.testOnly)

    @Test
    @Config(qualifiers = "w411dp-h2800dp-mdpi", fontScale = 2.0f)
    fun `test only at 200 percent`() = capture("progress_test_only_light_font200", ProgressSamples.testOnly)

    @Test
    fun `typical F9 month light and dark`() = all("progress_typical_f9", ProgressSamples.typical)

    @Test
    @Config(qualifiers = "w411dp-h2800dp-mdpi", fontScale = 2.0f)
    fun `typical F9 month at 200 percent`() = capture("progress_typical_f9_light_font200", ProgressSamples.typical)

    @Test
    fun `two currencies light and dark`() = all("progress_two_currencies", ProgressSamples.twoCurrencies)

    @Test
    @Config(qualifiers = "w411dp-h2800dp-mdpi", fontScale = 2.0f)
    fun `two currencies at 200 percent`() = capture("progress_two_currencies_light_font200", ProgressSamples.twoCurrencies)

    @Test
    fun `a tapped dot with its chip light and dark`() = all("progress_dot_chip", ProgressSamples.dotChip)

    @Test
    @Config(qualifiers = "w411dp-h2800dp-mdpi", fontScale = 2.0f)
    fun `a tapped dot with its chip at 200 percent`() = capture("progress_dot_chip_light_font200", ProgressSamples.dotChip)

    @Test
    fun `a streak over 30 light and dark`() = all("progress_streak_over_30", ProgressSamples.streakOver30)

    @Test
    @Config(qualifiers = "w411dp-h2800dp-mdpi", fontScale = 2.0f)
    fun `a streak over 30 at 200 percent`() = capture("progress_streak_over_30_light_font200", ProgressSamples.streakOver30)

    @Test
    fun `the skeleton light and dark`() = all("progress_skeleton", ProgressSamples.loading, loadingFor = SKELETON_WAIT_MILLIS)

    @Test
    @Config(qualifiers = "w411dp-h2800dp-mdpi", fontScale = 2.0f)
    fun `the skeleton at 200 percent`() =
        capture("progress_skeleton_light_font200", ProgressSamples.loading, loadingFor = SKELETON_WAIT_MILLIS)

    @Test
    fun `still loading light and dark`() = all("progress_loading_slow", ProgressSamples.loading, loadingFor = SLOW_WAIT_MILLIS)

    @Test
    @Config(qualifiers = "w411dp-h2800dp-mdpi", fontScale = 2.0f)
    fun `still loading at 200 percent`() =
        capture("progress_loading_slow_light_font200", ProgressSamples.loading, loadingFor = SLOW_WAIT_MILLIS)

    @Test
    fun `the load failure light and dark`() = all("progress_load_failed", ProgressSamples.loadFailed)

    @Test
    @Config(qualifiers = "w411dp-h2800dp-mdpi", fontScale = 2.0f)
    fun `the load failure at 200 percent`() = capture("progress_load_failed_light_font200", ProgressSamples.loadFailed)

    private companion object {
        /** Past the 300 ms skeleton delay. */
        const val SKELETON_WAIT_MILLIS = 400L

        /** Past the 3 s slow-loading text. */
        const val SLOW_WAIT_MILLIS = 3_100L
    }
}
