package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.settings.BuiltSettingsRows
import com.yawnandpawn.app.ui.settings.SettingsIntent
import com.yawnandpawn.app.ui.settings.SettingsScreen
import com.yawnandpawn.app.ui.settings.SettingsUiState
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
 * Story 4.5 with TalkBack in mind, at 200 % font: the Snooze rows are 56 dp, the stepper buttons and Back 48 dp and
 * named, the value is announced politely with its localized price, the ends of the ranges are disabled, and a held
 * button repeats without an extra step on release.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 2.0f)
class SnoozeSettingsSemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun show(
        state: SettingsUiState,
        onIntent: (SettingsIntent) -> Unit = {},
        block: () -> Unit,
    ) = withScreen(
        PpsThemeMode.Light,
        content = { SettingsScreen(state = state, is24Hour = false, onIntent = onIntent, rows = BuiltSettingsRows) },
        block = block,
    )

    @Test
    fun `the Snooze rows are 56 dp buttons with their values`() =
        show(SnoozeSettingsSamples.main) {
            listOf("Base fee", "Max snoozes per session").forEach { label ->
                composeRule.onNode(hasText(label, substring = true) and hasClickAction()).assertHeightIsAtLeast(56.dp)
            }
            composeRule.onNode(hasText("$1.00", substring = true) and hasClickAction()).assertExists()
        }

    @Test
    fun `the base fee stepper is named, 48 dp, announces its price, and is disabled at tier 1`() =
        show(SnoozeSettingsSamples.baseFee) {
            val lower = composeRule.onNodeWithContentDescription("Lower Base fee")
            val raise = composeRule.onNodeWithContentDescription("Raise Base fee")
            listOf(lower, raise).forEach { it.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp) }
            lower.assertIsNotEnabled()
            raise.assertIsEnabled()
            composeRule
                .onNodeWithContentDescription("Base fee, $1.00")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            composeRule.onNodeWithContentDescription("Back").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }

    @Test
    fun `+ is disabled at tier 10 and max snoozes ends at 1 and 5`() {
        show(SnoozeSettingsSamples.baseFee.copy(baseFeeTier = 10, baseFee = "$10.00")) {
            composeRule.onNodeWithContentDescription("Raise Base fee").assertIsNotEnabled()
        }
        show(SnoozeSettingsSamples.maxSnoozesOne) {
            composeRule.onNodeWithContentDescription("Lower Max snoozes per session").assertIsNotEnabled()
            composeRule.onNodeWithContentDescription("Max snoozes per session, 1").assertExists()
        }
        show(SnoozeSettingsSamples.maxSnoozesFive) {
            composeRule.onNodeWithContentDescription("Raise Max snoozes per session").assertIsNotEnabled()
        }
    }

    @Test
    fun `a tap steps once, and holding the button repeats with no extra step on release`() {
        val intents = mutableListOf<SettingsIntent>()
        show(SnoozeSettingsSamples.baseFee.copy(baseFeeTier = 5), onIntent = { intents += it }) {
            val raise = composeRule.onNodeWithContentDescription("Raise Base fee")
            raise.performClick()
            assertEquals(listOf<SettingsIntent>(SettingsIntent.RaiseBaseFee), intents)

            intents.clear()
            composeRule.mainClock.autoAdvance = false
            raise.performTouchInput { down(center) }
            composeRule.mainClock.advanceTimeBy(HELD_MILLIS)
            val held = intents.size
            raise.performTouchInput { up() }
            composeRule.mainClock.advanceTimeBy(AFTER_RELEASE_MILLIS)
            composeRule.mainClock.autoAdvance = true

            // 400 ms, then every 100 ms: about 4 steps in a 750 ms hold.
            assertTrue(held >= MIN_REPEATS, "held for $HELD_MILLIS ms: $held steps")
            assertTrue(intents.all { it == SettingsIntent.RaiseBaseFee })
            assertEquals(held, intents.size, "the release adds no step, and the repeat stops")
        }
    }

    private companion object {
        const val HELD_MILLIS = 750L
        const val AFTER_RELEASE_MILLIS = 500L
        const val MIN_REPEATS = 3
    }
}
