package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult

/**
 * Story 6.6: every text on screen whose characters do not all show, as "'text' size in constraints". A text is cut off
 * when it is taller than its room, when a line is ellipsized, when a line's glyphs are wider than the text or reach past
 * its paragraph, or when the last line shown ends before the text does.
 *
 * `didOverflowWidth` is not used: the semantics layout result lays the paragraph out again at the room's full width
 * and reports the text's own, narrower width beside it, so a text that fits with room to spare (a calendar day number:
 * 20 px wide in a 36 px room, its paragraph 36 px) reports a width overflow without losing a character. For the same
 * reason a line's edges are measured in that wider paragraph (a centred "day streak" sits at 18..156 px of 174 while
 * the text is 137 px wide), so the glyphs' own width is compared with the text's width, and their edges with the
 * paragraph's. Fails when the screen has no text at all, so a check of nothing never passes.
 */
internal fun ComposeTestRule.cutOffTexts(): List<String> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .also { check(it.isNotEmpty()) { "no text on screen to check" } }
        .flatMap { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.filter(::isCutOff).map { "'${it.layoutInput.text}' ${it.size} in ${it.layoutInput.constraints}" }
        }

private fun isCutOff(result: TextLayoutResult): Boolean {
    val text = result.layoutInput.text.trimEnd()
    return result.didOverflowHeight ||
        if (result.lineCount == 0) {
            text.isNotEmpty()
        } else {
            (0 until result.lineCount).any { isLineCut(result, it) } || lastShownEnd(result) < text.length
        }
}

/** [line] is ellipsized, reaches past the paragraph, or its glyphs are wider than the text. */
private fun isLineCut(
    result: TextLayoutResult,
    line: Int,
): Boolean {
    val left = result.getLineLeft(line)
    val right = result.getLineRight(line)
    return result.isLineEllipsized(line) ||
        left < -ROUNDING ||
        right > result.multiParagraph.width + ROUNDING ||
        right - left > result.size.width + ROUNDING
}

private fun lastShownEnd(result: TextLayoutResult): Int = result.getLineEnd(result.lineCount - 1, visibleEnd = true)

/** Line edges and the text's width are rounded to whole pixels; one pixel either way is still on the text. */
private const val ROUNDING = 1f
