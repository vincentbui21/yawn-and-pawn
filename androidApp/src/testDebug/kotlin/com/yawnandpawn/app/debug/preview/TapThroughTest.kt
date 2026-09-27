package com.yawnandpawn.app.debug.preview

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/** The design preview's tap-through: the wake flow sequence and the confirm sheet's 500 ms input lock. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class TapThroughTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun tapThrough(
        startInSession: Boolean,
        block: () -> Unit,
    ) = ActivityScenario.launch(PreviewActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                PreviewFrame(PpsThemeMode.Light, largeFont = false) {
                    TapThrough(is24Hour = false, onExit = {}, startInSession = startInSession)
                }
            }
        }
        block()
    }

    private fun tapDigits(digits: String) = digits.forEach { composeRule.onNodeWithText(it.toString()).performClick() }

    @Test
    fun `Back to alarm, I'm up, two Math problems and Done return to Home`() =
        tapThrough(startInSession = true) {
            composeRule.onNodeWithText("Alarm in progress").assertExists()
            composeRule.onNodeWithText("Back to alarm").performClick()
            composeRule.onNodeWithText("I'm up").performClick()

            composeRule.onNodeWithText("Quiet for 20s. Finish before it rings again.").assertExists()
            tapDigits("85")
            composeRule.onNodeWithText("Check").performClick()
            composeRule.onNodeWithText("Problem 2 of 2").assertExists()
            tapDigits("41")
            composeRule.onNodeWithText("Check").performClick()
            composeRule.onNodeWithText("Not quite. Try again.").assertExists()
            tapDigits("42")
            composeRule.onNodeWithText("Check").performClick()

            composeRule.onNodeWithText("Up on time. 12 days in a row.").assertExists()
            composeRule.onNodeWithText("Done").performClick()
            composeRule.onNodeWithText("Alarm in progress").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Add alarm").assertExists()
        }

    @Test
    fun `the snooze sheet ignores taps for 500 ms, then Pay snoozes and the alarm re-rings at the next price`() =
        tapThrough(startInSession = true) {
            composeRule.onNodeWithText("Back to alarm").performClick()
            composeRule.onNodeWithText("I'm up").assertExists()
            // Effect delays run on the test clock: the 500 ms lock only runs out when the test advances it.
            composeRule.onNode(hasText("Snooze · ", substring = true)).performClick()
            idleFor(FRAMES_MILLIS)

            val pay = composeRule.onNode(hasText("Pay ", substring = true))
            pay.performClick()
            idleFor(FRAMES_MILLIS)
            composeRule.onNodeWithText("I'll get up").assertExists()

            composeRule.mainClock.advanceTimeBy(600)
            pay.performClick()
            idleFor(FRAMES_MILLIS)
            composeRule.onNode(hasText("Snoozed. Next ring at 7:39 AM.")).assertExists()

            composeRule.mainClock.advanceTimeBy(3_100)
            composeRule.onNode(hasText("Snooze 1 of 5 · ", substring = true)).assertExists()
        }

    @Test
    fun `the editor opens from the FAB and Test alarm rings with no charge`() =
        tapThrough(startInSession = false) {
            composeRule.onNodeWithContentDescription("Add alarm").performClick()
            composeRule.onNodeWithText("New alarm").assertExists()
            // "Test alarm" may scroll in under the floating pill, so click it through its semantics action.
            composeRule.onNodeWithText("Test alarm").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithContentDescription("Snooze unavailable, Test · no charge").assertExists()
        }

    @Test
    fun `a row opens its sub-screen, Back returns to the editor, and Back on a wake screen leaves the tap-through`() {
        var exited = false
        ActivityScenario.launch(PreviewActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PreviewFrame(PpsThemeMode.Light, largeFont = false) {
                        TapThrough(is24Hour = false, onExit = { exited = true }, startInSession = false)
                    }
                }
            }
            composeRule.onNodeWithContentDescription("Add alarm").performClick()
            composeRule.onNode(hasText("Quiet time") and hasClickAction()).performScrollTo().performClick()
            composeRule.onNodeWithText("Vibrate during quiet time").assertExists()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithText("New alarm").assertExists()

            // "Test alarm" may scroll in under the floating pill, so click it through its semantics action.
            composeRule.onNodeWithText("Test alarm").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithText("I'm up").assertExists()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
            kotlin.test.assertTrue(exited, "Back on a wake screen returns to the preview menu")
        }
    }

    @Test
    fun `Back on a wake screen from the menu returns to the menu, even with the confirm sheet open`() {
        ActivityScenario.launch(PreviewActivity::class.java).use { scenario ->
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Confirm (Sunrise)"))
            composeRule.onNodeWithText("Confirm (Sunrise)").performClick()
            composeRule.onNodeWithText("I'll get up").assertExists()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            composeRule.onNodeWithText("Yawn & Pawn Preview").assertExists()
        }
    }
}

private const val FRAMES_MILLIS = 48L

/** Lets [millis] of main-looper time pass (delays and frames due in that time run). */
private fun idleFor(millis: Long) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
