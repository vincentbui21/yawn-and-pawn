package com.yawnandpawn.app.debug.preview

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Design preview round 2: Progress and Settings tap through like the finished app, with fake state only. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class Round2TapThroughTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun tapThrough(
        startTab: AppTab,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(PreviewActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PreviewFrame(PpsThemeMode.Light, largeFont = false) {
                        TapThrough(is24Hour = false, onExit = {}, startTab = startTab)
                    }
                }
            }
            block()
        }
    }

    @Test
    fun `a calendar day shows its chip, a second tap opens Day detail and Back returns to Progress`() =
        tapThrough(AppTab.Progress) {
            // The calendar's day is the button (the ring's dot reads the same but is not a separate button).
            val day = hasContentDescription("Thursday 10, Snoozed") and hasClickAction()
            // The first tap shows the label chip, the second opens the day.
            composeRule.onNode(day).performScrollTo().performClick()
            composeRule.onNodeWithText("Thu 10 · Snoozed").assertExists()
            composeRule.onAllNodes(day)[0].performClick() // [1] is the chip, which reads the same
            composeRule.onNodeWithText("Thu, Sep 10").assertExists()
            composeRule.onNodeWithText("Snoozed").assertExists()
            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.onNodeWithText("Current streak").assertExists()
        }

    @Test
    fun `a dot on the ring shows its chip, and the chip opens that morning's Day detail`() =
        tapThrough(AppTab.Progress) {
            // The ring comes first; the calendar below reads the same day the same way.
            composeRule.onAllNodesWithContentDescription("Wednesday 23, Snoozed")[0].performTouchInput { click() }
            // The chip pops under the ring; tapping it opens the day.
            composeRule.onNodeWithText("Wed 23 · Snoozed").performClick()
            composeRule.onNodeWithText("Wed, Sep 23").assertExists()
            composeRule.onNodeWithText("Snoozed").assertExists()
            // The hero, tiles and timeline agree: two snoozes (B x 1, B x 2), three rings, 22 min from first ring to solved.
            composeRule.onNodeWithText("22 min").assertExists()
            composeRule.onNodeWithText("3").assertExists()
            composeRule.onNodeWithText("Snoozed 9 min · ${formatMoney(PreviewSamples.price(1))}", useUnmergedTree = true).assertExists()
            composeRule.onNodeWithText("Snoozed 9 min · ${formatMoney(PreviewSamples.price(2))}", useUnmergedTree = true).assertExists()
            composeRule.onNodeWithText(formatMoney(PreviewSamples.price(3)), useUnmergedTree = true).assertExists()
            composeRule.onNodeWithText("Math solved first try", useUnmergedTree = true).assertExists()
        }

    @Test
    fun `Previous month shows August and Purchase history opens`() =
        tapThrough(AppTab.Progress) {
            composeRule.onNodeWithContentDescription("Previous month").performScrollTo().performClick()
            composeRule.onNodeWithText("August 2026").assertExists()
            // Scrolled into view it may still sit under the floating nav bar, so act on it directly.
            composeRule.onNodeWithText("Purchase history").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNode(hasText("Not used, refunded automatically by Google")).assertExists()
        }

    @Test
    fun `Settings sub-screens set their row and lowering the fee shows the lock note`() =
        tapThrough(AppTab.Settings) {
            composeRule.onNodeWithText("Default snooze length").performClick()
            composeRule.onNodeWithText("15 min").performClick()
            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.onNodeWithText("15 min").assertExists()

            composeRule.onNodeWithText("Base fee").performClick()
            composeRule.onNodeWithContentDescription("Raise Base fee").performClick()
            composeRule.onNodeWithContentDescription("Lower Base fee").performClick()
            composeRule.onNodeWithText("Saved. Takes effect after tomorrow's 7:30 AM alarm.").assertExists()
        }

    @Test
    fun `Fix on the checklist turns the row OK`() =
        tapThrough(AppTab.Settings) {
            composeRule.onNodeWithText("Reliability checklist").performScrollTo().performClick()
            composeRule.onNodeWithText("Alarm may not ring: battery optimization turned back on").assertExists()
            composeRule.onAllNodes(hasText("Fix"))[1].performClick()
            composeRule.onNodeWithText("Alarm may not ring: battery optimization turned back on").assertDoesNotExist()
        }

    @Test
    fun `the + opens a new alarm from any tab, and the You tab holds the money and data rows`() =
        tapThrough(AppTab.Progress) {
            // Settings keeps app behaviour only (owner decision 2026-10-01).
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.onNodeWithText("Delete all data").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Add alarm").performClick()
            composeRule.onNodeWithText("New alarm").assertExists()
            composeRule.onNodeWithText("Cancel").performClick()
            composeRule.onNodeWithContentDescription("You").performClick()
            composeRule.onNodeWithText("Purchase history").assertExists()
            composeRule.onNodeWithText("Delete all data").performScrollTo().performClick()
            composeRule.onNodeWithText("Delete all data?").assertExists()
            composeRule.onNodeWithText("Keep it").performClick()
            composeRule.onNodeWithText("Delete all data?").assertDoesNotExist()
        }
}
