package com.yawnandpawn.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.LocalWakeClock
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.SnoozeSheet
import com.yawnandpawn.app.ui.wake.WakeIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Story 4.13: the confirm sheet ignores every input for 500 ms on the monotonic clock after it opens, after its state
 * changes and after its displayed price changes (a tap at 499 ms is ignored, one at 500 ms counts); swipe down and
 * TalkBack's dismiss action are the "I'll get up" path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class SnoozeSheetGuardTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private var now = 10_000L
    private val intents = mutableListOf<WakeIntent>()
    private var sheet by mutableStateOf<SnoozeSheet>(SnoozeSheetSamples.confirm)

    private fun onSheet(block: () -> Unit) =
        withScreen(PpsThemeMode.Light, content = {
            CompositionLocalProvider(LocalWakeClock provides { now }) {
                RingingScreen(SnoozeSheetSamples.overRinging(sheet), is24Hour = false, onIntent = { intents += it })
            }
        }) {
            composeRule.waitForIdle()
            block()
        }

    /** Moves the monotonic clock on by [millis]. */
    private fun after(millis: Long) {
        now += millis
    }

    @Test
    fun `Pay at 499 ms is ignored and at 500 ms accepted, and again after a state change`() =
        onSheet {
            after(499)
            composeRule.onNodeWithText("Pay $1.00 and snooze").performClick()
            composeRule.onNodeWithText("I'll get up").performClick()
            assertEquals(emptyList(), intents, "499 ms")
            after(1)
            composeRule.onNodeWithText("Pay $1.00 and snooze").performClick()
            assertEquals(listOf<WakeIntent>(WakeIntent.SheetUpperClicked), intents, "500 ms")

            sheet = SnoozeSheetSamples.unlocking
            composeRule.waitForIdle()
            after(499)
            composeRule.onNodeWithText("Cancel").performClick()
            assertEquals(1, intents.size, "499 ms after the state change")
            after(1)
            composeRule.onNodeWithText("Cancel").performClick()
            assertEquals(listOf(WakeIntent.SheetUpperClicked, WakeIntent.SheetDismissed), intents)
        }

    @Test
    fun `a new displayed price starts the 500 ms again`() =
        onSheet {
            after(600)
            sheet = (SnoozeSheetSamples.confirm as SnoozeSheet.Confirm).copy(price = Money(1_500_000, "USD"))
            composeRule.waitForIdle()
            after(499)
            composeRule.onNodeWithText("Pay $1.50 and snooze").performClick()
            assertEquals(emptyList(), intents, "499 ms after the price changed")
            after(1)
            composeRule.onNodeWithText("Pay $1.50 and snooze").performClick()
            assertEquals(listOf<WakeIntent>(WakeIntent.SheetUpperClicked), intents)
        }

    @Test
    fun `a swipe down and TalkBack's dismiss close it like I'll get up, a swipe up does not`() =
        onSheet {
            after(500)
            val title = composeRule.onNodeWithText("Snooze for 9 min?")
            title.performTouchInput { swipeUp(startY = centerY, endY = centerY - SWIPE.toPx()) }
            title.performTouchInput { swipeDown(startY = centerY, endY = centerY + SHORT_SWIPE.toPx()) }
            composeRule.waitForIdle()
            assertEquals(emptyList(), intents, "a swipe up, or one shorter than 64 dp")
            title.performTouchInput { swipeDown(startY = centerY, endY = centerY + SWIPE.toPx()) }
            composeRule.waitForIdle()
            assertEquals(listOf<WakeIntent>(WakeIntent.SheetDismissed), intents, "swipe down")
            composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
            assertEquals(listOf<WakeIntent>(WakeIntent.SheetDismissed, WakeIntent.SheetDismissed), intents, "TalkBack dismiss")
        }

    private companion object {
        val SWIPE = 160.dp
        val SHORT_SWIPE = 32.dp
    }
}
