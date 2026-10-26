package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.home.HomeHero
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * Story 6.3: TalkBack reads `card-hero` as one element, "{streak} days on time. {money line}", with no button role and no
 * click action; the compact streak chip adds no text of its own; a session shows no hero. Robolectric's locale is en-US.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class HomeHeroSemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun show(
        state: HomeUiState,
        block: () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = { HomeScreen(state = state, is24Hour = false, onIntent = {}) }, block = block)

    private val noRole = SemanticsMatcher.keyNotDefined(SemanticsProperties.Role)
    private val noClick = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)

    /** Exactly one node reads [description], and it is neither a button nor clickable. */
    private fun assertOneHero(description: String) {
        composeRule.onAllNodes(hasContentDescription(description)).assertCountEquals(1)
        composeRule.onAllNodes(hasContentDescription(description) and noRole and noClick).assertCountEquals(1)
    }

    @Test
    fun `the hero is one element with the streak and nothing paid, no role, no click`() =
        show(HomeSamples.one.copy(hero = HomeHero(12))) {
            assertOneHero("12 days on time. Nothing paid this week. Keep it that way.")
            // What TalkBack reads (the merged tree): the card's own texts are cleared into it, and the compact chip has none.
            composeRule.onAllNodes(hasText("days on time", substring = true)).assertCountEquals(0)
            composeRule.onAllNodes(hasText("Nothing paid", substring = true)).assertCountEquals(0)
        }

    @Test
    fun `one day reads day on time, with one currency paid`() =
        show(HomeSamples.one.copy(hero = HomeHero(1, listOf(Money(3_000_000, "EUR"))))) {
            assertOneHero("1 day on time. €3.00 paid this week")
        }

    @Test
    fun `two currencies read one total each, joined with plus`() =
        show(HomeSamples.heroPaidTwo) {
            assertOneHero("12 days on time. €8.98 + $1.00 paid this week")
        }

    @Test
    fun `history without alarms reads the hero over the empty state`() =
        show(HomeSamples.heroNoAlarms) {
            assertOneHero("3 days on time. Nothing paid this week. Keep it that way.")
            composeRule.onNodeWithText("No alarms yet.").assertExists()
        }

    @Test
    fun `the hero is above the reliability banner and the missed note`() =
        show(HomeSamples.heroReliability) {
            val hero =
                composeRule.onNode(hasContentDescription("12 days on time", substring = true)).getBoundsInRoot()
            val banner = composeRule.onNodeWithText("Alarms may not ring. Fix settings", substring = true).getBoundsInRoot()
            val note = composeRule.onNodeWithText("Logged as missed.", substring = true).getBoundsInRoot()
            assertTrue(hero.bottom <= banner.top, "hero $hero above banner $banner")
            assertTrue(banner.bottom <= note.top, "banner $banner above note $note")
        }

    @Test
    fun `a session shows no hero`() =
        show(HomeSamples.heroZero.copy(sessionInProgress = true)) {
            composeRule.onAllNodes(hasContentDescription("days on time", substring = true)).assertCountEquals(0)
        }
}
