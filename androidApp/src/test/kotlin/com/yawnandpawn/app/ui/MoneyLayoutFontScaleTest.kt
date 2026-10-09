package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.purchases.Purchase
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryScreen
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.SnoozeSheet
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 4.2 review: "$1.00" is wider than "$1", and at 200% font that must not break the layouts around it.
 * - Purchase history: the date and alarm line stays on one line (the price moves down beside the caption instead).
 * - The snooze confirm sheet: it never grows over Ringing's clock; its buttons stay whole on screen, "Pay … and snooze"
 *   on at most 2 lines, and its text scrolls: every line of it can be scrolled fully into view above the buttons (the
 *   cut edge fades out, see sheet_confirm_sunrise_font200.png; owner check 2026-10-08).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi", fontScale = 2.0f)
class MoneyLayoutFontScaleTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val purchases =
        listOf(
            Purchase(LocalDate(2026, 9, 23), LocalTime(7, 30), snoozeNumber = 2, price = Money.of(2, "USD")),
            Purchase(LocalDate(2026, 8, 27), LocalTime(7, 30), snoozeNumber = 1, price = Money.of(1, "USD"), stranded = true),
            Purchase(LocalDate(2026, 8, 12), LocalTime(5, 45), snoozeNumber = 3, price = Money(12_990_000, "EUR")),
        )

    private fun SemanticsNodeInteraction.lineCount(): Int {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().lineCount
    }

    @Test
    fun `purchase history keeps each date and alarm on one line, clear of the price`() {
        withScreen(PpsThemeMode.Light, content = {
            PurchaseHistoryScreen(PurchaseHistoryUiState(purchases), is24Hour = false, onBack = {}, onProblemWithCharge = {})
        }) {
            listOf(
                "Wed, Sep 23 · 7:30 AM" to "$2.00",
                "Thu, Aug 27 · 7:30 AM" to "$1.00",
                "Wed, Aug 12 · 5:45 AM" to "€12.99",
            ).forEach { (title, price) ->
                val titleNode = composeRule.onNodeWithText(title, useUnmergedTree = true)
                assertEquals(1, titleNode.lineCount(), title)
                val titleBounds = titleNode.getBoundsInRoot()
                // The row's price, not the month total above the card (a heading), which can read the same.
                val rowPrice = hasText(price) and !hasAnyAncestor(isHeading()) and !isHeading()
                val priceBounds = composeRule.onNode(rowPrice, useUnmergedTree = true).getBoundsInRoot()
                val beside = priceBounds.left >= titleBounds.right
                val below = priceBounds.top >= titleBounds.bottom
                assertTrue(beside || below, "$price overlaps $title: $priceBounds vs $titleBounds")
                assertTrue(priceBounds.right <= composeRule.onRoot().getBoundsInRoot().right, "$price is cut off")
            }
        }
    }

    @Test
    fun `the confirm sheet stays below the clock with whole buttons`() = assertConfirmSheet()

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun `on the smallest phone the confirm sheet keeps whole buttons`() = assertConfirmSheet(belowClock = false, wholeTexts = false)

    private fun assertConfirmSheet(
        belowClock: Boolean = true,
        wholeTexts: Boolean = true,
    ) {
        val state =
            RingingSamples.firstRing.copy(
                sheet = SnoozeSheet.Confirm(minutes = 9, price = Money.of(1, "USD"), nextPrice = Money.of(2, "USD")),
            )
        withScreen(PpsThemeMode.Light, content = { RingingScreen(state = state, is24Hour = false, onIntent = {}) }) {
            composeRule.waitForIdle()
            val root = composeRule.onRoot().getBoundsInRoot()
            val pay = composeRule.onNodeWithText("Pay $1.00 and snooze", useUnmergedTree = true)
            assertTrue(pay.lineCount() <= 2, "the Pay label wraps to ${pay.lineCount()} lines")
            listOf(pay, composeRule.onNodeWithText("I'll get up", useUnmergedTree = true)).forEach { button ->
                val bounds = button.getBoundsInRoot()
                assertTrue(bounds.top >= root.top && bounds.bottom <= root.bottom, "button cut off: $bounds in $root")
            }
            if (belowClock) {
                val clock = composeRule.onNodeWithContentDescription(formatClockTime(state.time, false)).getBoundsInRoot()
                val title = composeRule.onNodeWithText("Snooze for 9 min?", useUnmergedTree = true).getBoundsInRoot()
                assertTrue(title.top >= clock.bottom, "the sheet covers the clock: title at ${title.top}, clock ends at ${clock.bottom}")
            }
            // After the clock check (scrolling moves the title). Where the sheet has room, each text scrolls fully into
            // view, whole and above the Pay button. On the smallest phone the text window is shorter than one paragraph,
            // so there it only has to scroll to its end.
            val payTop = pay.fetchSemanticsNode().boundsInRoot.top
            val body = "This one costs $1.00. The next one costs $2.00."
            val nudge = "Is 9 more minutes worth $1.00? You've got this."
            if (wholeTexts) {
                listOf(body, nudge).forEach { text ->
                    val node = composeRule.onNodeWithText(text, useUnmergedTree = true).performScrollTo()
                    composeRule.waitForIdle()
                    val semantics = node.fetchSemanticsNode()
                    val visible = semantics.boundsInRoot
                    val height = semantics.size.height.toFloat()
                    assertEquals(height, visible.height, 1f, "\"$text\" is clipped after scrolling to it: $visible")
                    assertTrue(visible.bottom <= payTop, "\"$text\" ends at ${visible.bottom}, below the Pay button at $payTop")
                }
            }
            // Scrolled to the end, the last line is whole (no fade, no cut) above the Pay button.
            composeRule
                .onNode(hasScrollAction() and hasAnyDescendant(hasText(nudge)), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, SCROLL_TO_END) }
            composeRule.waitForIdle()
            val last = composeRule.onNodeWithText(nudge, useUnmergedTree = true).fetchSemanticsNode()
            val lastBottom = last.positionInRoot.y + last.size.height
            assertEquals(lastBottom, last.boundsInRoot.bottom, 1f, "the end of \"$nudge\" stays cut off: ${last.boundsInRoot}")
            assertTrue(lastBottom <= payTop, "\"$nudge\" ends at $lastBottom, below the Pay button at $payTop")
        }
    }
}

/** Far past the end of the sheet's text; a scroll stops at its end. */
private const val SCROLL_TO_END = 10_000f
