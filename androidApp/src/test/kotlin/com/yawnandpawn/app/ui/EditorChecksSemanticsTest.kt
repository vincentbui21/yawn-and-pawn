package com.yawnandpawn.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** Story 3.5: the editor's check screens have 48 dp targets, and All mode's order is also TalkBack custom actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1400dp-mdpi")
class EditorChecksSemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val intents = mutableListOf<EditorIntent>()

    private fun editor(
        state: EditorUiState,
        block: () -> Unit,
    ) = withScreen(
        PpsThemeMode.Light,
        content = { AlarmEditorScreen(state = state, is24Hour = false, onIntent = { intents += it }) },
        block,
    )

    /** Every clickable node is at least 48 by 48 dp (mdpi: 1 px = 1 dp). */
    private fun assertTargets() {
        val small =
            composeRule
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()
                .filter { it.boundsInRoot.height < MIN_TARGET || it.boundsInRoot.width < MIN_TARGET }
                .map { it.config.getOrNull(SemanticsProperties.ContentDescription) ?: it.config.getOrNull(SemanticsProperties.Text) }
        assertEquals(emptyList(), small, "targets under 48 dp")
    }

    @Test
    fun `the Wake-up check sub-screen and Check setup have 48 dp targets`() {
        editor(EditorCheckSamples.several) { assertTargets() }
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `Check setup has 48 dp targets at 200 percent`() {
        editor(EditorCheckSamples.mathSetup) { assertTargets() }
    }

    /** The "Your checks" rows that have TalkBack custom actions, by check name, with their action labels. */
    private fun moveActions(): Map<String, List<CustomAccessibilityAction>> =
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNodes()
            .associate { it.config[SemanticsProperties.Text].first().text to it.config[SemanticsActions.CustomActions] }

    @Test
    fun `in All mode each check offers the moves it can make as TalkBack actions, which move it`() {
        editor(EditorCheckSamples.several) {
            val actions = moveActions()
            assertEquals(listOf("Move down"), actions.getValue("Math").map { it.label })
            assertEquals(listOf("Move up"), actions.getValue("Word Unscramble").map { it.label })

            composeRule.runOnUiThread { actions.getValue("Word Unscramble").single().action() }

            assertEquals(listOf<EditorIntent>(EditorIntent.CheckMoved(CheckType.WordUnscramble, up = true)), intents)
        }
    }

    @Test
    fun `in Random mode a check has no move actions`() {
        editor(EditorCheckSamples.oneCheck) {
            composeRule.onNodeWithText("Medium · 3 problems").assertExists()
            assertEquals(emptyMap(), moveActions())
        }
    }

    @Test
    fun `the stepper stops at Math's 10 problems`() {
        val tenProblems = listOf(CheckChip(CheckType.Math, Difficulty.Hard, 10))
        val ten = EditorCheckSamples.mathSetup.copy(form = EditorCheckSamples.mathSetup.form.copy(checks = tenProblems))
        editor(ten) {
            composeRule.onNodeWithContentDescription("Raise Problems").assertIsNotEnabled()
            composeRule.onNodeWithContentDescription("Lower Problems").assertIsEnabled()
        }
    }

    @Test
    fun `Math's stepper goes past 5 problems, its own range is 1 to 10 (review fix)`() {
        listOf(5, 9).forEach { count ->
            val checks = listOf(CheckChip(CheckType.Math, Difficulty.Hard, count))
            val state = EditorCheckSamples.mathSetup.copy(form = EditorCheckSamples.mathSetup.form.copy(checks = checks))
            editor(state) { composeRule.onNodeWithContentDescription("Raise Problems").assertIsEnabled() }
        }
    }

    @Test
    fun `Check setup keeps its content while it slides out after Back (review fix)`() {
        var state by mutableStateOf(EditorCheckSamples.mathSetup)
        withScreen(
            PpsThemeMode.Light,
            content = { AlarmEditorScreen(state = state, is24Hour = false, onIntent = { intents += it }) },
        ) {
            composeRule.onNodeWithContentDescription("Raise Problems").assertExists()
            composeRule.mainClock.autoAdvance = false
            // As the ViewModel leaves Check setup: the pane changes and the setup type is no longer set.
            composeRule.runOnUiThread { state = state.copy(pane = EditorPane.WakeCheck, setupType = null) }
            composeRule.mainClock.advanceTimeBy(HALF_TRANSITION_MILLIS)

            composeRule.onNodeWithContentDescription("Raise Problems").assertExists()

            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Raise Problems").assertDoesNotExist()
        }
    }

    @Test
    fun `the Math check is a ticked checkbox`() {
        editor(EditorCheckSamples.oneCheck) {
            composeRule.onNode(isToggleable() and hasText("Math", substring = true)).assertIsOn()
        }
    }

    private companion object {
        const val MIN_TARGET = 48f
        const val HALF_TRANSITION_MILLIS = 120L
    }
}
