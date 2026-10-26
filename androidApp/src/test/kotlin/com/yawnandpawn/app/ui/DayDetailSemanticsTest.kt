package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.TalkBackRules.assertControlsLabelledWithRoles
import com.yawnandpawn.app.ui.TalkBackRules.assertPolite
import com.yawnandpawn.app.ui.TalkBackRules.headings
import com.yawnandpawn.app.ui.TalkBackRules.readingOrder
import com.yawnandpawn.app.ui.TalkBackRules.spokenOrder
import com.yawnandpawn.app.ui.daydetail.DayDetailIntent
import com.yawnandpawn.app.ui.daydetail.DayDetailScreen
import com.yawnandpawn.app.ui.daydetail.DayDetailUiState
import com.yawnandpawn.app.ui.format.formatMoney
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
 * Story 6.7: what TalkBack reads on Day detail. The date is the one heading; the hero is one stop with the outcome as a
 * word; each tile reads "{label}, {value}"; each timeline row reads "{time}, {text}" (a row with no stored time, its text
 * only); a refunded payment reads "{note}, {price} refunded"; nothing reads "null" or "0 min"; Back is a labelled 48 dp
 * control; the skeleton is one polite stop and the failure offers Try again; at 200% no text is cut off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class DayDetailSemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val intents = mutableListOf<DayDetailIntent>()

    private fun show(
        state: DayDetailUiState,
        block: () -> Unit,
    ) = withScreen(
        PpsThemeMode.Light,
        content = { DayDetailScreen(state = state, is24Hour = false, onBack = {}, onIntent = { intents += it }) },
    ) {
        composeRule.waitForIdle()
        block()
    }

    /** Every merged stop's texts (or content description) as TalkBack reads them, top to bottom. */
    private fun stops(): List<String> = composeRule.readingOrder().map(::mergedTexts).filter { it.isNotEmpty() }

    private fun mergedTexts(node: SemanticsNode): String {
        val described = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        if (described.isNotEmpty()) return described.joinToString(", ")
        return node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
    }

    @Test
    fun `the date is the heading, and the hero is one stop with the outcome as a word`() =
        show(DayDetailSamples.snoozedTwoCurrenciesStranded) {
            assertEquals(listOf("Wed, Mar 3"), composeRule.headings())
            val stops = stops()
            assertTrue("Snoozed, 6:30 AM · Work, 18 min, to get up" in stops, "$stops")
        }

    @Test
    fun `tiles read label then value, money per currency, and nothing paid says No charge`() {
        show(DayDetailSamples.snoozedTwoCurrenciesStranded) {
            val spoken = composeRule.spokenOrder()
            val paid = formatMoney(listOf(Money(990_000, "EUR"), Money(1_000_000, "USD")))
            listOf("rings, 3", "snoozes, 2", "paid, $paid").forEach { tile -> assertTrue(tile in spoken, "'$tile' in $spoken") }
        }
        show(DayDetailSamples.onTime) {
            val spoken = composeRule.spokenOrder()
            listOf("rings, 1", "snoozes, 0", "paid, No charge").forEach { tile -> assertTrue(tile in spoken, "'$tile' in $spoken") }
        }
        // A snooze with no known amount (an estimate) never claims "No charge": the paid tile is left out.
        show(DayDetailSamples.missed) {
            val spoken = composeRule.spokenOrder()
            assertTrue(spoken.none { it.startsWith("paid") || it.contains("No charge") }, "$spoken")
            assertTrue("snoozes, 1" in spoken, "$spoken")
        }
    }

    @Test
    fun `timeline rows read time then text, a refunded payment reads its amount refunded`() {
        show(DayDetailSamples.snoozedTwoCurrenciesStranded) {
            val stops = stops()
            val eur = formatMoney(Money(990_000, "EUR"))
            listOf(
                "6:30 AM, Alarm rang",
                "6:31 AM, Snoozed · $eur",
                "6:48 AM, Math, Word Unscramble solved",
                "Not used, refunded automatically by Google, $eur refunded",
            ).forEach { stop -> assertTrue(stop in stops, "'$stop' in $stops") }
        }
        show(DayDetailSamples.missed) {
            val stops = stops()
            listOf("6:30 AM, Alarm rang", "Snoozed", "7:10 AM, Stopped after 30 minutes").forEach { stop ->
                assertTrue(stop in stops, "'$stop' in $stops")
            }
            assertTrue("Missed, 6:30 AM, Stopped after 30 minutes" in stops, "time only without a label: $stops")
        }
        show(DayDetailSamples.fallbackDirectBoot) {
            val stops = stops()
            listOf("6:30 AM, Rang before your first unlock", "Fallback check: Math", "On time, 6:30 AM · Work, Under 1 min, to get up")
                .forEach { stop -> assertTrue(stop in stops, "'$stop' in $stops") }
        }
    }

    @Test
    fun `missing facts never read as null or 0 min`() =
        listOf(DayDetailSamples.missed, DayDetailSamples.test, DayDetailSamples.skipped, DayDetailSamples.fallbackDirectBoot).forEach { state ->
            show(state) {
                val spoken = composeRule.spokenOrder()
                assertTrue(spoken.none { it.contains("null") || ZERO_MINUTES.containsMatchIn(it) || it == "0" }, "$spoken")
            }
        }

    @Test
    fun `every control is labelled, has a role and is at least 48 dp`() =
        show(DayDetailSamples.snoozedTwoCurrenciesStranded) { composeRule.assertControlsLabelledWithRoles() }

    @Test
    fun `the skeleton is one polite stop after 300 ms, and the failure offers Try again`() {
        show(DayDetailSamples.loading) {
            composeRule.mainClock.advanceTimeBy(SKELETON_WAIT_MILLIS)
            composeRule.waitForIdle()
            composeRule.assertPolite("Loading your progress")
        }
        show(DayDetailSamples.loadFailed) {
            composeRule.onNode(hasText("Couldn't load your progress.")).assertExists()
            composeRule.onNode(hasText("Try again") and hasClickAction()).performClick()
            assertEquals(listOf<DayDetailIntent>(DayDetailIntent.RetryLoad), intents)
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1800dp-mdpi", fontScale = 2.0f)
    fun `at 200 percent no text is cut off`() =
        listOf(DayDetailSamples.snoozedTwoCurrenciesStranded, DayDetailSamples.fallbackDirectBoot, DayDetailSamples.turnedOffNote).forEach {
            show(it) { assertEquals(emptyList(), composeRule.cutOffTexts()) }
        }

    private companion object {
        const val SKELETON_WAIT_MILLIS = 400L

        /** "0 min" as a whole figure, not the end of "30 minutes". */
        val ZERO_MINUTES = Regex("""(^|\D)0 min\b""")
    }
}
