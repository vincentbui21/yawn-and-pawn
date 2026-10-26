package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.TalkBackRules.assertControlsLabelledWithRoles
import com.yawnandpawn.app.ui.TalkBackRules.assertPolite
import com.yawnandpawn.app.ui.TalkBackRules.readingOrder
import com.yawnandpawn.app.ui.TalkBackRules.spokenOrder
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.progress.ProgressIntent
import com.yawnandpawn.app.ui.progress.ProgressScreen
import com.yawnandpawn.app.ui.progress.ProgressUiState
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
 * Story 6.5: what TalkBack reads on Progress and its targets. Each tile is one stop, "{label}, {value}"; Money paid reads
 * its title, one amount per currency and "This month"; the ring centre reads the final streak, never a number on its way
 * up; the empty prompt is read; the skeleton is one polite stop; every control is labelled, has a role and is at least
 * 48 dp; at 200% no text is cut off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class ProgressSemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val intents = mutableListOf<ProgressIntent>()

    private fun show(
        state: ProgressUiState,
        block: () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = { ProgressScreen(state = state, onIntent = { intents += it }) }) {
        composeRule.waitForIdle()
        block()
    }

    private fun reads(label: String) = composeRule.onNode(hasContentDescription(label) or hasText(label))

    @Test
    fun `each tile is one stop reading its label, then its value`() =
        show(ProgressSamples.typical) {
            val spoken = composeRule.spokenOrder()
            listOf("on time, 86%", "to get up, 3 min", "snoozes, 4").forEach { tile ->
                assertTrue(tile in spoken, "'$tile' in $spoken")
            }
        }

    @Test
    fun `a tile with no mornings reads No mornings yet, never a zero`() =
        show(ProgressSamples.testOnly) {
            val spoken = composeRule.spokenOrder()
            listOf("on time, No mornings yet", "to get up, No mornings yet", "snoozes, 0").forEach { tile ->
                assertTrue(tile in spoken, "'$tile' in $spoken")
            }
            assertTrue(spoken.none { it.contains("0%") || it.contains("0 min") }, "$spoken")
        }

    @Test
    fun `Money paid is one stop with one amount per currency, and nothing paid says so`() {
        show(ProgressSamples.twoCurrencies) {
            val money = "Money paid, ${formatMoney(Money(8_980_000, "EUR"))}, ${formatMoney(Money(1_000_000, "USD"))}, This month"
            val merged = composeRule.readingOrder().map(::mergedTexts)
            assertTrue(money in merged, "'$money' in $merged")
        }
        show(ProgressSamples.testOnly) {
            val merged = composeRule.readingOrder().map(::mergedTexts)
            assertTrue("Money paid, Nothing paid, This month" in merged, "$merged")
        }
    }

    @Test
    fun `the ring centre reads the final streak, also while it counts up`() =
        withScreen(PpsThemeMode.Light, content = { ProgressScreen(state = ProgressSamples.typical, onIntent = {}) }) {
            composeRule.mainClock.autoAdvance = false
            // Part way through the entry count-up, TalkBack already hears the final number.
            composeRule.mainClock.advanceTimeBy(COUNT_UP_PART_MILLIS)
            composeRule.onNode(hasContentDescription("12")).assertExists()
            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
            composeRule.onNode(hasContentDescription("12")).assertExists()
        }

    @Test
    fun `the empty state reads its prompt and offers Purchase history, with no tiles or money`() =
        show(ProgressSamples.empty) {
            reads("Your first morning shows up here.").assertExists()
            composeRule.onNode(hasText("Purchase history") and hasClickAction()).performScrollTo().performClick()
            assertEquals(listOf<ProgressIntent>(ProgressIntent.PurchaseHistoryClicked), intents)
            val spoken = composeRule.spokenOrder()
            assertTrue(spoken.none { it.startsWith("on time") || it.contains("Money paid") }, "$spoken")
        }

    @Test
    fun `every control is labelled, has a role and is at least 48 dp, the chip and the link included`() =
        show(ProgressSamples.dotChip) {
            composeRule.assertControlsLabelledWithRoles()
            composeRule.onNode(hasText("Purchase history") and hasClickAction()).performScrollTo().performClick()
            assertEquals(listOf<ProgressIntent>(ProgressIntent.PurchaseHistoryClicked), intents)
        }

    @Test
    fun `the skeleton is one polite stop after 300 ms, and the failure offers Try again`() {
        show(ProgressSamples.loading) {
            assertTrue(composeRule.spokenOrder().none { it.contains("Loading") })
            composeRule.mainClock.advanceTimeBy(SKELETON_WAIT_MILLIS)
            composeRule.waitForIdle()
            composeRule.assertPolite("Loading your progress")
            composeRule.mainClock.advanceTimeBy(SLOW_WAIT_MILLIS)
            composeRule.waitForIdle()
            composeRule.assertPolite("Still loading your progress.")
        }
        show(ProgressSamples.loadFailed) {
            reads("Couldn't load your progress.").assertExists()
            composeRule.onNode(hasText("Try again") and hasClickAction()).performClick()
            assertEquals(listOf<ProgressIntent>(ProgressIntent.RetryLoad), intents)
        }
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `at 200 percent no text is cut off`() =
        listOf(ProgressSamples.empty, ProgressSamples.testOnly, ProgressSamples.typical, ProgressSamples.twoCurrencies).forEach { state ->
            show(state) {
                val texts =
                    composeRule
                        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                        .fetchSemanticsNodes()
                assertTrue(texts.isNotEmpty())
                val cut =
                    texts.flatMap { node ->
                        val results = mutableListOf<TextLayoutResult>()
                        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                        // Cut off: taller than its room, or as wide as its room with text left over. A centred one-line
                        // text (the calendar's day numbers) reports a width overflow while it fits with room to spare.
                        results
                            .filter {
                                it.didOverflowHeight ||
                                    (it.didOverflowWidth && it.size.width >= it.layoutInput.constraints.maxWidth)
                            }.map {
                                "'${it.layoutInput.text}' ${it.size} in ${it.layoutInput.constraints}"
                            }
                    }
                assertEquals(emptyList(), cut, "cut off in ${state.stats}")
            }
        }

    /** A merged node's texts joined as TalkBack reads them. */
    private fun mergedTexts(node: SemanticsNode): String =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString(", ") { it.text }

    private companion object {
        const val SKELETON_WAIT_MILLIS = 400L
        const val SLOW_WAIT_MILLIS = 3_000L

        /** Inside the streak's count-up on entry. */
        const val COUNT_UP_PART_MILLIS = 200L
    }
}
