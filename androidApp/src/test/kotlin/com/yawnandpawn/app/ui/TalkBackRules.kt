package com.yawnandpawn.app.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 3.12: what TalkBack reads on a screen, checked from the semantics tree only (labels, roles, headings, live
 * regions and reading order), never by test tag, colour or position, so a test proves the screen needs no sight.
 */
internal object TalkBackRules {
    /** What TalkBack reads for [node]: its content description, else its text. */
    fun label(node: SemanticsNode): String {
        val config = node.config
        val described = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        val text = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        return described.ifEmpty { text }.trim()
    }

    /** Every node of the merged tree (one per TalkBack stop), in reading order (the order the layout places them). */
    fun ComposeTestRule.readingOrder(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()

        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        visit(onRoot().fetchSemanticsNode())
        return nodes
    }

    /**
     * The spoken labels in TalkBack's order: [readingOrder], with a node's traversal index (its own, else its nearest
     * ancestor's; 0 by default) moving it first (Ringing's clock at -1) or later. A stable sort keeps reading order.
     */
    fun ComposeTestRule.talkBackOrder(): List<String> {
        fun index(node: SemanticsNode): Float =
            generateSequence(node) { it.parent }.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.TraversalIndex) } ?: 0f
        return readingOrder().sortedBy(::index).map(::label).filter { it.isNotEmpty() }
    }

    /** The labels of [readingOrder] that TalkBack can read (non-empty). */
    fun ComposeTestRule.spokenOrder(): List<String> = readingOrder().map(::label).filter { it.isNotEmpty() }

    /** [expected] appear in this order among the spoken labels (each matched by `contains`), with others between them. */
    fun ComposeTestRule.assertReadsInOrder(vararg expected: String) {
        val spoken = spokenOrder()
        var next = 0
        spoken.forEach { label -> if (next < expected.size && label.contains(expected[next])) next++ }
        assertEquals(expected.size, next, "reading order: expected ${expected.toList()} in order, found $spoken")
    }

    /**
     * The rules every control on a check screen keeps (NFR-9), by hand, since the Accessibility Test Framework does not
     * run under Robolectric:
     * - every clickable node has a non-empty label, a role and a touch target of at least 48 × 48 dp; each of
     *   [wakeActions] (snooze, "Done", the number pad keys) is at least 64 dp tall;
     * - a node with a role but no click action says it is disabled (the unavailable snooze, a tile while the sequence
     *   plays), so TalkBack reads its state;
     * - a toggle (the torch) is a switch or a check box with a label, a click action and an on or off state;
     * - no action is reachable only by a long click or a custom action.
     */
    fun ComposeTestRule.assertControlsLabelledWithRoles(wakeActions: List<SemanticsMatcher> = emptyList()) {
        val clickable = onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(clickable.isNotEmpty(), "the screen has controls")
        clickable.forEach { node ->
            assertTrue(label(node).isNotEmpty(), "a control without a label: ${node.config}")
            assertNotNull(node.config.getOrNull(SemanticsProperties.Role), "a control without a role: ${label(node)}")
            assertAtLeast(node, TARGET_MIN, TARGET_MIN)
        }
        wakeActions.forEach { matcher ->
            onAllNodes(matcher).fetchSemanticsNodes().also { assertTrue(it.isNotEmpty(), "no ${matcher.description}") }.forEach { node ->
                assertAtLeast(node, TARGET_MIN, TARGET_WAKE)
            }
        }
        readingOrder().forEach { node ->
            val config = node.config
            assertTrue(SemanticsActions.OnLongClick !in config, "a long-click action on ${label(node)}")
            assertTrue(SemanticsActions.CustomActions !in config, "a custom action on ${label(node)}")
            if (SemanticsProperties.Role in config && SemanticsActions.OnClick !in config) {
                assertTrue(SemanticsProperties.Disabled in config, "${label(node)} has a role and no action, but is not disabled")
            }
            config.getOrNull(SemanticsProperties.ToggleableState)?.let { state ->
                assertTrue(
                    config.getOrNull(SemanticsProperties.Role) in setOf(Role.Switch, Role.Checkbox),
                    "a toggle as a switch: ${label(node)}",
                )
                assertTrue(SemanticsActions.OnClick in config, "a toggle that can be toggled: ${label(node)}")
                assertTrue(state != ToggleableState.Indeterminate, "a toggle that is on or off: ${label(node)}")
                assertTrue(label(node).isNotEmpty(), "a toggle without a label")
            }
        }
    }

    /** [node]'s touch target is at least [width] × [height]. */
    private fun ComposeTestRule.assertAtLeast(
        node: SemanticsNode,
        width: Dp,
        height: Dp,
    ) {
        val size = with(density) { DpSize(node.size.width.toDp(), node.size.height.toDp()) }
        assertTrue(size.width >= width && size.height >= height, "${label(node)} is $size, below $width × $height")
    }

    private val TARGET_MIN = 48.dp
    private val TARGET_WAKE = 64.dp

    /** Headings in reading order. */
    fun ComposeTestRule.headings(): List<String> = readingOrder().filter { SemanticsProperties.Heading in it.config }.map(::label)

    /**
     * Exactly one heading comes before the first control ([firstInput]) in reading order, and it reads [heading] (Story
     * 3.12: the problem or instruction is a heading).
     */
    fun ComposeTestRule.assertOneHeadingBefore(
        heading: String,
        firstInput: SemanticsMatcher,
    ) {
        val order = readingOrder()
        val first = order.indexOfFirst { firstInput.matches(it) }
        assertTrue(first >= 0, "no first input")
        val before = order.take(first).filter { SemanticsProperties.Heading in it.config }.map(::label)
        assertEquals(listOf(heading), before, "the heading(s) before the first input")
    }

    /** A node reading [label] (by `contains`) is a polite live region. */
    fun ComposeTestRule.assertPolite(label: String) {
        val node = readingOrder().firstOrNull { label(it).contains(label) }
        assertNotNull(node, "no node reads '$label': ${spokenOrder()}")
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion), "'$label' is a polite live region")
    }

    /** [inner] lies fully inside [outer]. */
    fun Rect.isInside(outer: Rect): Boolean = left >= outer.left && top >= outer.top && right <= outer.right && bottom <= outer.bottom
}
