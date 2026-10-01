package com.yawnandpawn.app.debug.preview

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Design preview round 3: onboarding, the checks (picker, setup, "Try it", QR and House Hunt registration) and
 * Recordings tap through like the finished app, with fake state only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class Round3TapThroughTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun tapThrough(
        start: FlowStart,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(PreviewActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PreviewFrame(PpsThemeMode.Light, largeFont = false) {
                        TapThrough(is24Hour = false, onExit = {}, start = start)
                    }
                }
            }
            block()
        }
    }

    /** Scrolls to it when it is in a scrolling column (the bottom actions and pad keys are not). */
    private fun SemanticsNodeInteraction.scrollAndClick() {
        runCatching { performScrollTo() }
        performClick()
    }

    private fun tap(text: String) = composeRule.onNode(hasText(text) and hasClickAction()).scrollAndClick()

    private fun tapDescription(description: String) = composeRule.onNodeWithContentDescription(description).scrollAndClick()

    private fun sees(text: String) = composeRule.onNodeWithText(text).assertExists()

    /** The Math check of the test alarm: 47 + 38, then 6 x 7. */
    private fun solveMath() {
        listOf("8", "5").forEach { tap(it) }
        tap("Check")
        listOf("4", "2").forEach { tap(it) }
        tap("Check")
    }

    @Test
    fun `onboarding walks all eight steps, registers a QR code, fixes the checklist and rings the test alarm`() =
        tapThrough(FlowStart.Onboarding) {
            sees("This app makes money only when you snooze. We hope you never pay us.")
            tap("Let's set it up")
            sees("How your alarm works")
            tap("I understand")
            // The base fee steps up a tier and the fee ladder follows (the phone's currency, never a fixed symbol).
            tapDescription("Raise Base fee")
            sees(
                "Snooze 1: ${formatMoney(
                    PreviewSamples.price(2),
                )} · 2: ${formatMoney(PreviewSamples.price(4))} · 3: ${formatMoney(PreviewSamples.price(6))}",
            )
            tap("Continue")
            sees("When should it ring?")
            tap("Continue")
            sees("How will you prove you're up?")
            // A fresh user has no code yet: the QR/Barcode row opens its setup, "Your code" opens registration.
            tap("Scan a code to use this check.")
            tap("Your code")
            // The preview "camera" finds a code after 1.5 s.
            composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithText("Use this code").fetchSemanticsNodes().isNotEmpty() }
            tap("Use this code")
            sees("Code saved")
            composeRule.onNodeWithContentDescription("Back").performClick()
            tap("Continue")
            sees("Make sure it rings")
            composeRule.onAllNodesWithText("Fix")[0].scrollAndClick()
            tap("Continue")
            sees("Share anonymous usage stats?")
            tap("No thanks")
            sees("Lock your phone. We'll ring in 10 seconds.")
            // The phone in the hand is not locked the first time.
            tap("Ring a test alarm")
            sees("Your phone wasn't locked. Try again with it locked.")
            tap("Ring a test alarm")
            // The disabled snooze reads its reason as one TalkBack label; its visible text is in the unmerged tree.
            composeRule.onNodeWithText("Test · no charge", useUnmergedTree = true).assertExists()
            tap("I'm up")
            solveMath()
            sees("Test finished. Your alarm works.")
            tap("Done")
            // Home with the alarm onboarding set up, and no test note.
            sees("Rings in 7 h 50 min")
            composeRule.onNodeWithText("Ring a test alarm with your phone locked to check it works.").assertDoesNotExist()
        }

    @Test
    fun `skipping the test alarm lands on Home with the note recommending it`() =
        tapThrough(FlowStart.Onboarding) {
            listOf("Let's set it up", "I understand", "Continue", "Continue", "Continue", "Continue", "No thanks").forEach { tap(it) }
            tap("Skip for now")
            sees("Ring a test alarm with your phone locked to check it works.")
        }

    @Test
    fun `Back steps back through onboarding`() =
        tapThrough(FlowStart.Onboarding) {
            tap("Let's set it up")
            tap("I understand")
            composeRule.onNodeWithContentDescription("Back").performClick()
            sees("How your alarm works")
            composeRule.onNodeWithContentDescription("Back").performClick()
            sees("Let's set it up")
        }

    @Test
    fun `a check's setup changes its row, and Try it is solved with the right answer`() =
        tapThrough(FlowStart.CheckPicker) {
            tap("Medium · 2 problems")
            sees("Solve a few quick sums.")
            tapDescription("Raise Problems")
            tap("Try it")
            listOf("8", "4").forEach { tap(it) }
            tap("Check")
            sees("Not quite. Try again.")
            listOf("8", "5").forEach { tap(it) }
            tap("Check")
            sees("Nice. That's how it works.")
            tap("Done")
            composeRule.onNodeWithContentDescription("Back").performClick()
            sees("Medium · 3 problems")
        }

    @Test
    fun `House Hunt photos are taken, tested and saved into its setup`() =
        tapThrough(FlowStart.CheckPicker) {
            tap("House Hunt")
            tap("2 photos")
            tap("House Hunt photos")
            tapDescription("Take photo")
            tap("Test match")
            sees("Matched")
            tap("Save")
            sees("3 photos")
        }

    @Test
    fun `printing a QR code over a saved one asks first`() =
        tapThrough(FlowStart.CheckPicker) {
            tap("Code saved")
            tap("Make a printable QR")
            composeRule.onNodeWithContentDescription("QR code for your alarm").assertExists()
            tap("Print or save as PDF")
            sees("Replace your QR code? The old one stops working.")
            tap("Replace")
            sees("Your code")
        }

    @Test
    fun `a recorded message is saved, one is deleted, and the editor lists what is left`() =
        tapThrough(FlowStart.Recordings) {
            tapDescription("Start recording")
            tapDescription("Stop recording")
            tap("Save")
            sees("Message 3")
            tapDescription("Delete Message 1")
            sees("Delete this message? Alarms using it will play no message.")
            tap("Delete")
            composeRule.onNodeWithText("Message 1").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Back").performClick()
            // Back on the editor's Motivation sub-screen, which lists the messages that are left.
            sees("Message 2")
            sees("Message 3")
            composeRule.onNodeWithText("Message 1").assertDoesNotExist()
        }

    @Test
    fun `a round 3 deep link opens its state`() {
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), PreviewActivity::class.java)
                .putExtra(PreviewLaunch.EXTRA_STATE, "onboarding-test-not-locked")
        ActivityScenario.launch<PreviewActivity>(intent).use {
            sees("Your phone wasn't locked. Try again with it locked.")
        }
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
