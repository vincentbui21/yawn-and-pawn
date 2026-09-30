package com.yawnandpawn.app.debug.preview

import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Design preview round 2: Progress and Settings tap through like the finished app, with fake state only. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class Round2TapThroughTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

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
    fun `a calendar day opens Day detail and Back returns to Progress`() =
        tapThrough(AppTab.Progress) {
            composeRule.onNodeWithContentDescription("Thursday 10, Snoozed").performScrollTo().performClick()
            composeRule.onNodeWithText("Thursday, September 10").assertExists()
            composeRule.onNodeWithText("Snoozed").assertExists()
            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.onNodeWithText("Current streak").assertExists()
        }

    @Test
    fun `Previous month shows August and Purchase history opens`() =
        tapThrough(AppTab.Progress) {
            composeRule.onNodeWithContentDescription("Previous month").performScrollTo().performClick()
            composeRule.onNodeWithText("August 2026").assertExists()
            composeRule.onNodeWithText("Purchase history").performScrollTo().performClick()
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
    fun `Fix on the checklist turns the row OK and Delete all data asks first`() =
        tapThrough(AppTab.Settings) {
            composeRule.onNodeWithText("Reliability checklist").performScrollTo().performClick()
            composeRule.onNodeWithText("Alarm may not ring: battery optimization turned back on").assertExists()
            composeRule.onAllNodes(hasText("Fix"))[1].performClick()
            composeRule.onNodeWithText("Alarm may not ring: battery optimization turned back on").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Back").performClick()

            composeRule.onNodeWithText("Delete all data").performScrollTo().performClick()
            composeRule.onNodeWithText("Delete all data?").assertExists()
            composeRule.onNodeWithText("Keep it").performClick()
            composeRule.onNodeWithText("Delete all data?").assertDoesNotExist()
        }
}
