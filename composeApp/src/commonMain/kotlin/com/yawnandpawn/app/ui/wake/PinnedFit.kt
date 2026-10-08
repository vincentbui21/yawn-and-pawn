package com.yawnandpawn.app.ui.wake

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

/**
 * Whether a check's input fits pinned above the footer (Story 3.12 review): the natural heights, in px, of the parts
 * that do not scroll (the input, Word's actions, the footer with its link and message), reported by [natural] as they
 * are laid out. Measured rather than estimated, so the snooze price, a payment message, the fallback link, the system
 * bars and the font scale all count. One frame after a part grows past the window, the input scrolls with the rest
 * instead of being squashed.
 */
@Stable
internal class PinnedFit {
    private val heights = mutableStateMapOf<String, Int>()

    /** The non-scrolling parts fit in [maxHeight] px (true until they are measured). */
    fun fits(maxHeight: Int): Boolean = heights.values.sum() <= maxHeight

    /**
     * Reports this part's natural height under [key]: it is measured without a height limit, so it is never squashed,
     * and takes at most the height it is given (it may draw past it for the frame before the layout changes).
     */
    fun Modifier.natural(key: String): Modifier =
        layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            if (heights[key] != placeable.height) heights[key] = placeable.height
            val height = if (constraints.hasBoundedHeight) minOf(placeable.height, constraints.maxHeight) else placeable.height
            layout(placeable.width, height) { placeable.place(0, 0) }
        }
}

/**
 * The scroll of the area above a pinned input: whenever its content grows past the area (a large font scale), it shows
 * the end, so the instruction next to the input stays in view and the grace header scrolls up (Story 3.12 review,
 * default taken; TalkBack still reads the header first). Where everything fits nothing moves.
 */
@Composable
internal fun rememberEndScroll(): ScrollState {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { if (scroll.maxValue > 0) scroll.scrollTo(scroll.maxValue) }
    return scroll
}
