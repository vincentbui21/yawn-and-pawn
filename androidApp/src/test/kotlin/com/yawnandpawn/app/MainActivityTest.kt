package com.yawnandpawn.app

import android.Manifest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.reliability.NotificationPermission
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The real app: Koin, Room `app.db`, Navigation 3, the app shell and the Story 1.8 and 1.9 screens together. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MainActivityTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) = composeRule.onNode(hasContentDescription(label) and hasClickAction())

    private fun pressBack() = composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

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
    fun `MainActivity opens on Home inside the nav capsule with the empty state and no FAB`() {
        composeRule.onNodeWithText("Yawn & Pawn").assertExists()
        waitForText("No alarms yet.")
        composeRule.onNodeWithText("Add your first alarm").assertExists()
        // The capsule's "+" is the only "Add alarm": there is no FAB.
        composeRule.onAllNodes(hasContentDescription("Add alarm")).assertCountEquals(1)
        tab("Alarms").assertIsSelected()
        listOf("Progress", "Settings", "You").forEach { tab(it).assertIsNotSelected() }
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

        composeRule.onNode(hasText("Custom") and hasClickAction()).performClick()
        composeRule.onNodeWithContentDescription("Monday").performClick()
        composeRule.onNode(hasSetTextAction() and hasContentDescription("Alarm name")).performTextReplacement("Stand-up")
        composeRule.onNode(hasText("Vibration") and hasClickAction()).performScrollTo().performClick()
        // Snooze is a row that opens its sub-screen; the sub-screen's back arrow returns with the change kept.
        composeRule.onNode(hasText("Snooze") and hasClickAction()).performScrollTo().performClick()
        composeRule.onNode(hasText("15 min") and hasClickAction()).performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("Save")
        composeRule.onNodeWithText("Save").performClick()

        waitForGone("New alarm")
        // The card caption joins the repeat summary and the label.
        waitForText("Mon · Stand-up")
        composeRule.onNodeWithText("No alarms yet.").assertDoesNotExist()
        composeRule.onNodeWithText("Mon · Stand-up").performClick()

        waitForText("Edit alarm")
        composeRule.onNodeWithContentDescription("Monday").assertIsOn()
        composeRule.onNodeWithText("Stand-up").assertExists()
        composeRule.onNode(hasText("Vibration") and hasClickAction()).assertIsOff()
        composeRule.onNode(hasText("Snooze") and hasClickAction()).performScrollTo().performClick()
        composeRule.onNode(hasText("15 min") and hasClickAction()).assertIsSelected()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForText("Cancel")

        // No change: Cancel closes at once.
        composeRule.onNodeWithText("Cancel").performClick()
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
        composeRule.onNode(hasText("Weekdays") and hasClickAction()).performClick()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        waitForText("Discard changes?")
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.onNodeWithText("Discard changes?").assertDoesNotExist()
        composeRule.onNodeWithText("Edit alarm").assertExists()

        composeRule.onNodeWithText("Cancel").performClick()
        waitForText("Discard changes?")
        composeRule.onNodeWithText("Discard").performClick()

        waitForGone("Edit alarm")
        composeRule.onNodeWithText("Once").assertExists()
    }

    @Test
    fun `a time set on the wheels is saved and shown in the list and the editor`() {
        waitForText("No alarms yet.")
        composeRule.onNodeWithContentDescription("Add alarm").performClick()
        waitForText("New alarm")

        // TalkBack's swipe up / down on a wheel is a set-progress action; the keyboard never opens.
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        composeRule.onNode(hasContentDescription("Hour")).performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        composeRule.onNode(hasContentDescription("Minute")).performSemanticsAction(SemanticsActions.SetProgress) { it(45f) }
        composeRule.onNode(hasContentDescription("AM or PM")).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        composeRule.onNodeWithText("Save").performClick()

        waitForGone("New alarm")
        waitForText("8:45 PM")
        composeRule.onNodeWithText("8:45 PM").performClick()

        waitForText("Edit alarm")
        composeRule.onNode(hasContentDescription("Hour")).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "8"))
        composeRule.onNode(hasContentDescription("Minute")).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "45"))
        composeRule
            .onNode(
                hasContentDescription("AM or PM"),
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "PM"))
    }

    @Test
    fun `recreating the activity keeps the open editor and its unsaved change`() {
        waitForText("No alarms yet.")
        composeRule.onNodeWithContentDescription("Add alarm").performClick()
        waitForText("New alarm")
        composeRule.onNode(hasText("Custom") and hasClickAction()).performClick()
        composeRule.onNodeWithContentDescription("Monday").performClick()

        composeRule.activityRule.scenario.recreate()

        waitForText("New alarm")
        composeRule.onNodeWithContentDescription("Monday").assertIsOn()
        composeRule.onNodeWithText("Cancel").performClick()
        waitForText("Discard changes?")
    }

    // Story 1.9: the shell, its tabs and Home's card actions on the real database.

    /** Adds a 7:00 AM one-time alarm through the editor and waits for its card. */
    private fun addDefaultAlarm() {
        composeRule.onNodeWithContentDescription("Add alarm").performClick()
        waitForText("New alarm")
        composeRule.onNodeWithText("Save").performClick()
        waitForGone("New alarm")
        waitForText("7:00 AM")
    }

    @Test
    fun `Back on Progress, Settings or You returns to Alarms, and Back on Alarms leaves the app`() {
        waitForText("No alarms yet.")
        listOf("Progress", "Settings", "You").forEach { label ->
            tab(label).performClick()
            tab(label).assertIsSelected()
            composeRule.onNodeWithText("No alarms yet.").assertDoesNotExist()

            pressBack()

            waitForText("No alarms yet.")
            tab("Alarms").assertIsSelected()
        }
        pressBack()
        composeRule.activityRule.scenario.onActivity { assertTrue(it.isFinishing) }
    }

    @Test
    fun `plus on another tab opens a new alarm, and Save lands on Home with the card and its countdown`() {
        waitForText("No alarms yet.")
        tab("Settings").performClick()
        composeRule.onNodeWithContentDescription("Add alarm").performClick()
        waitForText("New alarm")
        // The editor is pushed over the shell: no capsule.
        composeRule.onAllNodes(hasContentDescription("Add alarm")).assertCountEquals(0)

        composeRule.onNodeWithText("Save").performClick()

        waitForText("7:00 AM")
        tab("Alarms").assertIsSelected()
        composeRule.onNode(hasText("Rings in", substring = true)).assertExists()
    }

    @Test
    fun `Cancel on a new alarm opened from You lands on Home`() {
        waitForText("No alarms yet.")
        tab("You").performClick()
        composeRule.onNodeWithContentDescription("Add alarm").performClick()
        waitForText("New alarm")

        composeRule.onNodeWithText("Cancel").performClick()

        waitForText("No alarms yet.")
        tab("Alarms").assertIsSelected()
    }

    @Test
    fun `the unbuilt tabs show their empty or default state with no row that leads nowhere`() {
        waitForText("No alarms yet.")
        tab("Progress").performClick()
        waitForText("Your first morning shows up here.")
        composeRule.onNodeWithText("Purchase history").assertDoesNotExist()

        tab("Settings").performClick()
        waitForText("Settings")
        composeRule.onNodeWithText("Base fee").assertDoesNotExist()
        composeRule.onNodeWithText("Reliability checklist").assertDoesNotExist()

        tab("You").performClick()
        waitForText("You")
        composeRule.onNodeWithText("About").assertDoesNotExist()
        composeRule.onNodeWithText("Delete all data").assertDoesNotExist()
    }

    @Test
    fun `switching a card off stores it and hides the countdown`() {
        waitForText("No alarms yet.")
        addDefaultAlarm()

        composeRule.onNodeWithContentDescription("7:00 AM alarm").assertIsOn().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Rings in", substring = true)).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("7:00 AM alarm").assertIsOff()
    }

    @Test
    fun `long-press Delete asks first, then the card goes`() {
        waitForText("No alarms yet.")
        addDefaultAlarm()

        composeRule.onNode(hasText("7:00 AM") and hasClickAction()).performTouchInput { longClick() }
        composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()
        waitForText("Delete your 7:00 AM alarm? This is logged.")
        composeRule.onNodeWithText("Keep it").performClick()
        composeRule.onNodeWithText("7:00 AM").assertExists()

        composeRule.onNode(hasText("7:00 AM") and hasClickAction()).performTouchInput { longClick() }
        composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()
        waitForText("Delete your 7:00 AM alarm? This is logged.")
        composeRule.onNodeWithText("Delete").performClick()

        waitForText("No alarms yet.")
    }

    @Test
    fun `long-press Duplicate opens the copy in the editor, and Home then has both`() {
        waitForText("No alarms yet.")
        addDefaultAlarm()

        composeRule.onNode(hasText("7:00 AM") and hasClickAction()).performTouchInput { longClick() }
        composeRule.onNode(hasText("Duplicate") and hasClickAction()).performClick()

        waitForText("Edit alarm")
        composeRule.onNodeWithText("Cancel").performClick()
        waitForGone("Edit alarm")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("7:00 AM") and hasClickAction()).fetchSemanticsNodes().size == 2
        }
    }

    @Test
    fun `the editor's overflow Duplicate opens the copy, and Home then has both`() {
        waitForText("No alarms yet.")
        addDefaultAlarm()
        composeRule.onNode(hasText("7:00 AM") and hasClickAction()).performClick()
        waitForText("Edit alarm")

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNode(hasText("Duplicate") and hasClickAction()).performClick()

        // The original's editor keeps Save disabled once the copy is stored; the editor on the copy replaces it (one
        // editor, not two stacked) and has Save enabled again once it has loaded.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Save") and isEnabled()).fetchSemanticsNodes().size == 1 &&
                composeRule.onAllNodes(hasText("Edit alarm")).fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("Cancel").performClick()
        waitForGone("Edit alarm")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("7:00 AM") and hasClickAction()).fetchSemanticsNodes().size == 2
        }
    }

    @Test
    fun `the editor's overflow Delete deletes the alarm and closes the editor`() {
        waitForText("No alarms yet.")
        addDefaultAlarm()
        composeRule.onNode(hasText("7:00 AM") and hasClickAction()).performClick()
        waitForText("Edit alarm")

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()
        waitForText("Delete your 7:00 AM alarm? This is logged.")
        composeRule.onNodeWithText("Delete").performClick()

        waitForGone("Edit alarm")
        waitForText("No alarms yet.")
    }

    /** Story 1.19: the first save asks for notifications from the screen in front, once, and never once it stopped. */
    @Test
    @Config(sdk = [33])
    fun `the first save asks for POST_NOTIFICATIONS once, and never after the activity stopped`() {
        waitForText("No alarms yet.")
        addDefaultAlarm()
        composeRule.waitForIdle()
        lateinit var first: Any
        composeRule.activityRule.scenario.onActivity { activity ->
            val request = assertNotNull(shadowOf(activity).lastRequestedPermission, "asked after the first save")
            assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
            first = request
        }

        addDefaultAlarm()
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.onActivity { assertSame(first, shadowOf(it).lastRequestedPermission, "asked once") }

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        GlobalContext.get().get<NotificationPermission>().request()
        composeRule.activityRule.scenario.onActivity { assertSame(first, shadowOf(it).lastRequestedPermission, "not once stopped") }
    }
}
