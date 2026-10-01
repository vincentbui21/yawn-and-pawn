package com.yawnandpawn.app.debug.preview

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Accessibility floor for every design-preview state (also at 200% for the primary ones), on a phone-sized window:
 * every control TalkBack can act on is at least 48 dp, has a label and a role (or is a text field or an adjustable
 * control that announces its value), toggles and radio buttons expose their state; on wake screens every action is at
 * least 64 dp ("I'm up" 72 dp) and fully on screen, and a disabled snooze reads its reason.
 */
abstract class PreviewSemanticsChecks(
    protected val case: PreviewCase,
) {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    private val actionable =
        hasClickAction() or SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) or hasSetTextAction()

    private fun SemanticsNode.label(): String =
        listOfNotNull(
            config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
            config.getOrNull(SemanticsProperties.Text)?.joinToString(),
            config.getOrNull(SemanticsProperties.EditableText)?.text,
        ).joinToString(" ").trim()

    private fun SemanticsNode.describe(): String = "${case.name}: ${config.getOrNull(SemanticsProperties.Role)} '${label()}'"

    private fun SemanticsNode.heightDp(): Dp = with(layoutInfo.density) { touchBoundsInRoot.height.toDp() }

    private fun SemanticsNode.widthDp(): Dp = with(layoutInfo.density) { touchBoundsInRoot.width.toDp() }

    @Test
    fun `every control meets the accessibility floor`() =
        withPreview(case) {
            composeRule.waitForIdle()
            // A control scrolled fully out of the viewport (Home scrolled under its header) has empty bounds; skip it.
            val nodes = composeRule.onAllNodes(actionable).fetchSemanticsNodes().filterNot { it.touchBoundsInRoot.isEmpty }
            nodes.forEach { node ->
                assertTrue(
                    node.widthDp() >= 48.dp && node.heightDp() >= 48.dp,
                    "under 48 dp (${node.widthDp()} x ${node.heightDp()}): ${node.describe()}",
                )
                assertTrue(node.label().isNotEmpty(), "no TalkBack label: ${node.describe()}")
                val config = node.config
                val role = config.getOrNull(SemanticsProperties.Role)
                val isTextField = SemanticsActions.SetText in config
                val isAdjustable = SemanticsProperties.ProgressBarRangeInfo in config
                assertTrue(role != null || isTextField || isAdjustable, "no role: ${node.describe()}")
                when (role) {
                    Role.Switch, Role.Checkbox -> {
                        assertTrue(
                            SemanticsProperties.ToggleableState in config,
                            "no on/off state: ${node.describe()}",
                        )
                    }

                    Role.RadioButton, Role.Tab -> {
                        assertTrue(
                            SemanticsProperties.Selected in config,
                            "no selected state: ${node.describe()}",
                        )
                    }

                    else -> {
                        Unit
                    }
                }
                if (isAdjustable) assertTrue(SemanticsProperties.StateDescription in config, "value not announced: ${node.describe()}")
            }
        }

    @Test
    fun `wake actions are at least 64 dp and on screen`() =
        withPreview(case) {
            if (!case.item.wake) return@withPreview
            composeRule.waitForIdle()
            val rootHeight =
                composeRule
                    .onRoot()
                    .fetchSemanticsNode()
                    .boundsInRoot.bottom
            val wakeActions =
                composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
                    val label = node.label()
                    wakeLabels.any { label.startsWith(it) }
                } +
                    composeRule
                        .onAllNodes(
                            SemanticsMatcher("disabled snooze") { it.label().startsWith("Snooze unavailable") },
                        ).fetchSemanticsNodes()
            wakeActions.forEach { node ->
                val min = if (node.label() == "I'm up") 72.dp else 64.dp
                assertTrue(node.heightDp() >= min, "wake action under $min (${node.heightDp()}): ${node.describe()}")
                assertTrue(node.boundsInRoot.bottom <= rootHeight + 1f, "wake action off screen: ${node.describe()}")
            }
        }

    private companion object {
        /** Labels of the wake-screen actions (EXPERIENCE.md key strings). */
        val wakeLabels = listOf("I'm up", "Snooze · ", "Pay ", "I'll get up", "Use it", "Not now", "Cancel", "Done")
    }
}

/** Every design-preview state except the full editor, on a phone-sized window (so wake actions must fit on screen). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class PreviewSemanticsTest(
    case: PreviewCase,
) : PreviewSemanticsChecks(case) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = PreviewCase.all(tall = false).map { arrayOf(it) }
    }
}

/** The full-editor states, in a window tall enough that the whole scrolling form is laid out. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4400dp-mdpi")
class PreviewEditorSemanticsTest(
    case: PreviewCase,
) : PreviewSemanticsChecks(case) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = PreviewCase.all(tall = true).map { arrayOf(it) }
    }
}
