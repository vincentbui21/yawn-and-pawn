package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.TalkBackRules.spokenOrder
import com.yawnandpawn.app.ui.purchases.PURCHASE_HISTORY_SKELETON_TAG
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryIntent
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryScreen
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 4.16: what TalkBack reads on Purchase history and its targets. Each row is one item (date and alarm, snooze
 * number or status, price), 64 dp tall; each month title is a heading with its total; the skeleton is skipped and shows
 * only after 300 ms; "Try again" is a 48 dp button that reads again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class PurchaseHistorySemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val intents = mutableListOf<PurchaseHistoryIntent>()

    private fun show(
        state: PurchaseHistoryUiState,
        showProblemWithCharge: Boolean = false,
        block: () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = {
        PurchaseHistoryScreen(
            state = state,
            is24Hour = false,
            onBack = {},
            onProblemWithCharge = {},
            onIntent = { intents += it },
            showProblemWithCharge = showProblemWithCharge,
        )
    }, block = block)

    /** The merged node whose texts are exactly [texts], in order: one TalkBack item. */
    private fun item(vararg texts: String) =
        SemanticsMatcher("texts are ${texts.toList()}") { node ->
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.map { it.text } == texts.toList()
        }

    @Test
    fun `each row is one item with its date, alarm, snooze number or status and price, 64 dp tall`() =
        show(PurchaseHistorySamples.mixed) {
            val rows =
                listOf(
                    item("Wed, Sep 23 · Gym", "Snooze 2", "$2.00"),
                    item("Tue, Sep 22 · 7:30 AM", "Snooze 1"),
                    item("Sun, Sep 6 · 7:30 AM", "Not used, refunded automatically by Google", "$1.00"),
                    item("Thu, Aug 27", "Not used, refunded automatically by Google"),
                )
            rows.forEach { matcher ->
                val bounds = composeRule.onNode(matcher).assertExists().getBoundsInRoot()
                assertTrue(bounds.bottom - bounds.top >= 64.dp, "$matcher is ${bounds.bottom - bounds.top} tall")
            }
            // Read-only: no row can be tapped.
            composeRule.onAllNodes(hasClickAction() and hasText("Snooze", substring = true)).assertCountEquals(0)
        }

    @Test
    fun `each month title is a heading with what was paid that month, one total per currency`() {
        show(PurchaseHistorySamples.mixed) {
            composeRule.onNode(isHeading() and item("September 2026", "$5.00")).assertExists()
            // August had only a payment that was not used: no total.
            composeRule.onNode(isHeading() and item("August 2026")).assertExists()
        }
    }

    @Test
    fun `two currencies are never summed together`() =
        show(PurchaseHistorySamples.twoCurrencies) {
            composeRule.onNode(isHeading() and item("September 2026", "€3.57 + $3.00")).assertExists()
            composeRule.onNode(item("Wed, Sep 23 · 7:30 AM", "Snooze 2", "€2.38")).assertExists()
        }

    @Test
    fun `no records reads the empty line, and Problem with a charge shows only when asked`() {
        show(PurchaseHistorySamples.empty) {
            composeRule.onNodeWithText("No snoozes paid. Keep it that way.").assertExists()
            composeRule.onNodeWithText("Problem with a charge?").assertDoesNotExist()
        }
        show(PurchaseHistorySamples.empty, showProblemWithCharge = true) {
            composeRule.onNodeWithText("Problem with a charge?").assertExists()
        }
    }

    @Test
    fun `loading shows nothing for 300 ms, then a skeleton TalkBack skips, and never the empty line`() =
        show(PurchaseHistorySamples.loading) {
            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(200)
            composeRule.onAllNodesWithTag(PURCHASE_HISTORY_SKELETON_TAG, useUnmergedTree = true).assertCountEquals(0)

            composeRule.mainClock.advanceTimeBy(200)
            composeRule.onAllNodesWithTag(PURCHASE_HISTORY_SKELETON_TAG, useUnmergedTree = true).assertCountEquals(1)
            composeRule.mainClock.autoAdvance = true

            composeRule.onNodeWithText("No snoozes paid. Keep it that way.").assertDoesNotExist()
            assertEquals(listOf("Back", "Purchase history"), composeRule.spokenOrder())
        }

    @Test
    fun `a read failure says so with a 48 dp Try again that reads again, never the empty line`() =
        show(PurchaseHistorySamples.loadFailed) {
            composeRule.onNodeWithText("Couldn't load your purchases.").assertExists()
            composeRule.onNodeWithText("No snoozes paid. Keep it that way.").assertDoesNotExist()
            val retry = composeRule.onNode(hasText("Try again") and hasClickAction())
            val bounds = retry.getBoundsInRoot()
            assertTrue(bounds.bottom - bounds.top >= 48.dp, "Try again is ${bounds.bottom - bounds.top} tall")

            retry.performClick()

            assertEquals(listOf<PurchaseHistoryIntent>(PurchaseHistoryIntent.RetryLoad), intents)
        }
}
