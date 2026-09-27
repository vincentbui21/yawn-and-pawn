package com.yawnandpawn.app.debug.preview

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.SuccessScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The on-time celebration (feedback item 20) at fixed moments of its 1.5 s: count-up, then confetti, then calm. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class SuccessCelebrationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `the number counts up from 11 to 12 under the confetti, then the screen is calm`() {
        ActivityScenario.launch(PreviewActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PreviewFrame(
                        PpsThemeMode.Light,
                        largeFont = false,
                    ) { SuccessScreen(state = PreviewSamples.successOnTime, onIntent = {}) }
                }
            }
            composeRule.mainClock.autoAdvance = false
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(FRAME)
            // TalkBack reads the final number only; the drawn "11" is in the picture.
            composeRule.onNodeWithContentDescription("12").assertExists()
            composeRule.onRoot().captureRoboImage(
                "src/test/screenshots/preview/success_on_time_count_start_sunrise.png",
                roborazziOptions = screenshotOptions,
            )

            composeRule.mainClock.advanceTimeBy(MID_BURST)
            composeRule.onNodeWithContentDescription("12").assertExists()
            composeRule.onRoot().captureRoboImage(
                "src/test/screenshots/preview/success_on_time_confetti_sunrise.png",
                roborazziOptions = screenshotOptions,
            )

            composeRule.mainClock.advanceTimeBy(AFTER_BURST)
            composeRule.onNodeWithContentDescription("12").assertExists()
            composeRule.onNodeWithText("days in a row").assertExists()
        }
    }

    private companion object {
        const val FRAME = 32L
        const val MID_BURST = 500L
        const val AFTER_BURST = 1_500L
    }
}
