package com.yawnandpawn.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertNotNull

/** The real app: Koin, Room `app.db`, Navigation 3 and the Story 1.8 screens together. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        stopKoin()
    }

    /** Room reads and writes run off the main thread, so wait for their results to reach the screen. */
    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForGone(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `MainActivity opens on the Alarms route with the app name and the empty state`() {
        composeRule.onNodeWithText("Yawn & Pawn").assertExists()
        waitForText("No alarms yet.")
        composeRule.onNodeWithText("Add your first alarm").assertExists()
        composeRule.onNodeWithContentDescription("Add alarm").assertExists()
    }

    @Test
    fun `the application starts Koin`() {
        assertNotNull(GlobalContext.getOrNull())
    }

    @Test
    fun `the empty screen matches the screenshot baseline`() {
        waitForText("No alarms yet.")
        composeRule.onRoot().captureRoboImage("src/test/screenshots/empty_screen.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `an alarm added, saved and reopened keeps its fields`() {
        waitForText("No alarms yet.")
        composeRule.onNodeWithContentDescription("Add alarm").performClick()
        waitForText("New alarm")

        composeRule.onNodeWithContentDescription("Monday").performClick()
        composeRule.onNode(hasSetTextAction() and hasText("Label")).performTextReplacement("Stand-up")
        composeRule.onNode(hasText("15 min") and hasClickAction()).performClick()
        composeRule.onNode(hasText("Vibration") and hasClickAction()).performClick()
        composeRule.onNodeWithText("Save").performClick()

        waitForGone("New alarm")
        waitForText("Stand-up")
        composeRule.onNodeWithText("No alarms yet.").assertDoesNotExist()
        composeRule.onNodeWithText("Stand-up").performClick()

        waitForText("Edit alarm")
        composeRule.onNodeWithContentDescription("Monday").assertIsOn()
        composeRule.onNodeWithText("Stand-up").assertExists()
        composeRule.onNode(hasText("15 min") and hasClickAction()).assertIsSelected()
        composeRule.onNode(hasText("Vibration") and hasClickAction()).assertIsOff()

        // No change: Back closes at once.
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForGone("Edit alarm")
        composeRule.onNodeWithText("Yawn & Pawn").assertExists()
    }

    @Test
    fun `Back after a change asks to discard, and Discard keeps the stored alarm`() {
        waitForText("No alarms yet.")
        composeRule.onNodeWithText("Add your first alarm").performClick()
        waitForText("New alarm")
        composeRule.onNodeWithText("Save").performClick()
        waitForGone("New alarm")
        waitForText("Once")

        composeRule.onNodeWithText("Once").performClick()
        waitForText("Edit alarm")
        composeRule.onNodeWithContentDescription("Sunday").performClick()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        waitForText("Discard changes?")
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.onNodeWithText("Discard changes?").assertDoesNotExist()
        composeRule.onNodeWithText("Edit alarm").assertExists()

        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("Discard changes?")
        composeRule.onNodeWithText("Discard").performClick()

        waitForGone("Edit alarm")
        composeRule.onNodeWithText("Once").assertExists()
    }

    @Test
    fun `a time typed into the time input is saved and shown in the list and the editor`() {
        waitForText("No alarms yet.")
        composeRule.onNodeWithContentDescription("Add alarm").performClick()
        waitForText("New alarm")

        // The time input's hour and minute fields come first, before the label field.
        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("8")
        fields[1].performTextReplacement("45")
        composeRule.onNode(hasText("PM") and hasClickAction()).performClick()
        composeRule.onNodeWithText("Save").performClick()

        waitForGone("New alarm")
        waitForText("8:45 PM")
        composeRule.onNodeWithText("8:45 PM").performClick()

        waitForText("Edit alarm")
        composeRule.onAllNodes(hasSetTextAction())[0].assert(hasText("08"))
        composeRule.onAllNodes(hasSetTextAction())[1].assert(hasText("45"))
        composeRule.onNode(hasText("PM") and hasClickAction()).assertIsSelected()
    }

    @Test
    fun `recreating the activity keeps the open editor and its unsaved change`() {
        waitForText("No alarms yet.")
        composeRule.onNodeWithContentDescription("Add alarm").performClick()
        waitForText("New alarm")
        composeRule.onNodeWithContentDescription("Monday").performClick()

        composeRule.activityRule.scenario.recreate()

        waitForText("New alarm")
        composeRule.onNodeWithContentDescription("Monday").assertIsOn()
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("Discard changes?")
    }
}
