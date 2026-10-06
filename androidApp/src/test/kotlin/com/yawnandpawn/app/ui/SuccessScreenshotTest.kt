package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.SuccessScreen
import com.yawnandpawn.app.ui.wake.SuccessUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 3.3 screenshots of the basic Success screen as `WakeActivity` shows it (no celebration until Epic 6, no paid
 * line until Epic 4): on time, after a snooze and a test, always Sunrise, at 100% and 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class SuccessScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun success(
        name: String,
        state: SuccessUiState,
    ) = withScreen(PpsThemeMode.Light, content = { SuccessScreen(state = state, onIntent = {}, basic = true) }) {
        composeRule.onNodeWithText("Done").assertExists()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `on time`() = success("wake_success_on_time_sunrise", SuccessSamples.onTime)

    @Test
    @Config(fontScale = 2.0f)
    fun `on time at 200 percent`() = success("wake_success_on_time_sunrise_font200", SuccessSamples.onTime)

    @Test
    fun `after a snooze`() = success("wake_success_after_snooze_sunrise", SuccessSamples.afterSnooze)

    @Test
    @Config(fontScale = 2.0f)
    fun `after a snooze at 200 percent`() = success("wake_success_after_snooze_sunrise_font200", SuccessSamples.afterSnooze)

    @Test
    fun `test session`() = success("wake_success_test_sunrise", SuccessSamples.test)

    @Test
    @Config(fontScale = 2.0f)
    fun `test session at 200 percent`() = success("wake_success_test_sunrise_font200", SuccessSamples.test)
}
