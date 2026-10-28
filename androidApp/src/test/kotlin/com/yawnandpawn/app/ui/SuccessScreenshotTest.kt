package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
 * Story 3.3 screenshots of the Success screen as `WakeActivity` shows it: on time, after a snooze and a test, and the
 * Story 6.4 streak (the count-up's start and calm end, the same day, the first day, no snapshot) and the Story 7.3
 * message card (playing, and played to its end), always Sunrise, at 100% and 200% font scale.
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

    // --- Story 6.4: the streak ---

    /** The count-up at its start: "11" before it turns to 12, the confetti just leaving the number. */
    private fun growStart(name: String) =
        withScreen(PpsThemeMode.Light, content = { SuccessScreen(state = SuccessSamples.streakGrew, onIntent = {}, basic = true) }) {
            composeRule.mainClock.autoAdvance = false
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(FRAME)
            composeRule.onNodeWithText("Done").assertExists()
            composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
        }

    @Test
    fun `streak grows, count-up start`() = growStart("wake_success_streak_grow_start_sunrise")

    @Test
    @Config(fontScale = 2.0f)
    fun `streak grows, count-up start at 200 percent`() = growStart("wake_success_streak_grow_start_sunrise_font200")

    @Test
    fun `streak grows, calm end`() = success("wake_success_streak_grow_end_sunrise", SuccessSamples.streakGrew)

    @Test
    @Config(fontScale = 2.0f)
    fun `streak grows, calm end at 200 percent`() = success("wake_success_streak_grow_end_sunrise_font200", SuccessSamples.streakGrew)

    @Test
    fun `streak on a day already good`() = success("wake_success_streak_same_day_sunrise", SuccessSamples.streakSameDay)

    @Test
    @Config(fontScale = 2.0f)
    fun `streak on a day already good at 200 percent`() =
        success("wake_success_streak_same_day_sunrise_font200", SuccessSamples.streakSameDay)

    @Test
    fun `first streak day`() = success("wake_success_streak_first_sunrise", SuccessSamples.streakFirst)

    @Test
    @Config(fontScale = 2.0f)
    fun `first streak day at 200 percent`() = success("wake_success_streak_first_sunrise_font200", SuccessSamples.streakFirst)

    @Test
    fun `snapshot missing`() = success("wake_success_snapshot_missing_sunrise", SuccessSamples.snapshotMissing)

    @Test
    @Config(fontScale = 2.0f)
    fun `snapshot missing at 200 percent`() = success("wake_success_snapshot_missing_sunrise_font200", SuccessSamples.snapshotMissing)

    // --- Story 7.3: the message card ---

    @Test
    fun `streak with the message playing`() = success("wake_success_message_streak_sunrise", SuccessSamples.streakWithMessage)

    @Test
    @Config(fontScale = 2.0f)
    fun `streak with the message playing at 200 percent`() =
        success("wake_success_message_streak_sunrise_font200", SuccessSamples.streakWithMessage)

    @Test
    fun `after a snooze with the message played to its end`() =
        success("wake_success_message_after_snooze_sunrise", SuccessSamples.afterSnoozeWithMessage)

    @Test
    @Config(fontScale = 2.0f)
    fun `after a snooze with the message played to its end at 200 percent`() =
        success("wake_success_message_after_snooze_sunrise_font200", SuccessSamples.afterSnoozeWithMessage)

    private companion object {
        const val FRAME = 32L
    }
}
