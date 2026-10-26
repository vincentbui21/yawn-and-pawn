package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.settings.BuiltSettingsRows
import com.yawnandpawn.app.ui.settings.SettingsIntent
import com.yawnandpawn.app.ui.settings.SettingsPane
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

/**
 * Story 5.1 with TalkBack in mind, at 200 % font on a small phone: the Default snooze length and Default quiet time rows
 * are 56 dp buttons with their values, the "Vibrate during quiet time" switch row, the theme segments, the radio rows
 * and Back are at least 48 dp, the segments and radio rows are selectable with their labels, and the slider exposes its
 * range and its value text. The "Bright wake screen" switch row (Story 5.2), the usage stats switch (Story 5.6) and the
 * weekly summary switch (Story 6.8) show.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 2.0f)
class DefaultsSettingsSemanticsTest {
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
    fun `the default rows are 56 dp buttons with their values and open their sub-screens`() {
        val intents = mutableListOf<SettingsIntent>()
        show(DefaultsSettingsSamples.main, onIntent = { intents += it }) {
            val snooze = composeRule.onNode(hasText("Default snooze length", substring = true) and hasClickAction())
            val quiet = composeRule.onNode(hasText("Default quiet time", substring = true) and hasClickAction())
            listOf(snooze, quiet).forEach { it.performScrollTo().assertHeightIsAtLeast(56.dp) }
            composeRule.onNode(hasText("9 min", substring = true) and hasClickAction()).assertExists()
            composeRule.onNode(hasText("20 seconds", substring = true) and hasClickAction()).assertExists()

            snooze.performClick()
            quiet.performClick()
            assertEquals(
                listOf<SettingsIntent>(
                    SettingsIntent.OpenPane(SettingsPane.SnoozeLength),
                    SettingsIntent.OpenPane(SettingsPane.QuietTime),
                ),
                intents,
            )
        }
    }

    @Test
    fun `Vibrate during quiet time is a 48 dp switch row that applies at once`() {
        val intents = mutableListOf<SettingsIntent>()
        show(DefaultsSettingsSamples.main, onIntent = { intents += it }) {
            val switch = composeRule.onNode(hasText("Vibrate during quiet time") and isToggleable())
            switch.performScrollTo().assertHeightIsAtLeast(48.dp).assertIsOn()

            switch.performClick()

            assertEquals(listOf<SettingsIntent>(SettingsIntent.VibrateDuringQuietTimeToggled(false)), intents)
        }
    }

    @Test
    fun `the theme segments are 48 dp, selectable with their labels, and one is selected`() {
        val intents = mutableListOf<SettingsIntent>()
        show(DefaultsSettingsSamples.main, onIntent = { intents += it }) {
            fun segment(label: String) = composeRule.onNode(hasText(label) and isSelectable())
            listOf("System", "Light", "Dark").forEach { segment(it).performScrollTo().assertHeightIsAtLeast(48.dp) }
            segment("System").assertIsSelected()
            segment("Dark").assertIsNotSelected()

            segment("Dark").performClick()

            assertEquals(listOf<SettingsIntent>(SettingsIntent.ThemeSelected(PpsThemeMode.Dark)), intents)
        }
    }

    @Test
    fun `Bright wake screen is a 48 dp switch row, on by default with its caption, that applies at once (Story 5_2)`() {
        val intents = mutableListOf<SettingsIntent>()
        show(DefaultsSettingsSamples.main, onIntent = { intents += it }) {
            val switch =
                composeRule.onNode(
                    hasText("Bright wake screen") and hasText("Raises screen brightness while the alarm rings.") and isToggleable(),
                )
            // TalkBack reads the label, the caption and the On/Off state of one switch.
            switch.performScrollTo().assertHeightIsAtLeast(48.dp).assertIsOn()
            switch.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

            switch.performClick()

            assertEquals(listOf<SettingsIntent>(SettingsIntent.BrightWakeScreenToggled(false)), intents)
        }
    }

    @Test
    fun `Weekly summary is a 48 dp switch row, on by default, that applies at once (Story 6_8)`() {
        val intents = mutableListOf<SettingsIntent>()
        show(DefaultsSettingsSamples.main, onIntent = { intents += it }) {
            val switch = composeRule.onNode(hasText("Weekly summary") and isToggleable())
            // TalkBack reads "Weekly summary, switch, on".
            switch.performScrollTo().assertHeightIsAtLeast(48.dp).assertIsOn()
            switch.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

            switch.performClick()

            assertEquals(listOf<SettingsIntent>(SettingsIntent.WeeklySummaryToggled(false)), intents)
        }
    }

    @Test
    fun `weekly summary and usage stats show, and the checklist row is a 48 dp button`() =
        show(DefaultsSettingsSamples.main) {
            composeRule.onNode(hasText("Weekly summary")).assertExists()
            // Story 5.6.
            composeRule.onNode(hasText("Share anonymous usage stats")).assertExists()
            // Story 5.4: the row opens the checklist.
            composeRule
                .onNode(hasText("Reliability checklist") and hasClickAction())
                .performScrollTo()
                .assertHeightIsAtLeast(48.dp)
        }

    @Test
    fun `the snooze length radio rows are 48 dp and selectable, the chosen one selected, and Back is 48 dp`() {
        val intents = mutableListOf<SettingsIntent>()
        show(DefaultsSettingsSamples.snoozeLength, onIntent = { intents += it }) {
            fun row(label: String) = composeRule.onNode(hasText(label) and isSelectable())
            listOf("5 min", "9 min", "10 min", "15 min").forEach { row(it).performScrollTo().assertHeightIsAtLeast(48.dp) }
            row("10 min").assertIsSelected()
            row("9 min").assertIsNotSelected()
            composeRule.onNodeWithContentDescription("Back").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)

            row("15 min").performClick()

            assertEquals(listOf<SettingsIntent>(SettingsIntent.SnoozeLengthSelected(15)), intents)
        }
    }

    @Test
    fun `the quiet time slider exposes its range and announces its value`() =
        show(DefaultsSettingsSamples.quietTime) {
            composeRule
                .onNode(
                    hasContentDescription("Default quiet time") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "25 seconds"))
                .assert(
                    SemanticsMatcher("range 15 to 30 at 25") { node ->
                        val info = node.config[SemanticsProperties.ProgressBarRangeInfo]
                        info.current == 25f && info.range == 15f..30f
                    },
                ).assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithContentDescription("Back").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
}
