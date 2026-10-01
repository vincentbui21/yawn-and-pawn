package com.yawnandpawn.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.theme.PpsTheme

/**
 * `progress-dots` (onboarding): [count] 8 dp dots, 8 dp apart; the [current] one (0-based) is an accent pill 16 dp wide,
 * the others `outline`. Not tappable; TalkBack reads [description] ("Step 3 of 8"). They sit on a small glass capsule,
 * because accent directly on the Light gradient fails (DESIGN.md: accent on glass+gradient-top passes, 3.24). The active
 * dot slides between steps (instant with reduced motion, which follows the animator scale).
 */
@Composable
fun ProgressDots(
    count: Int,
    current: Int,
    description: String,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    Row(
        modifier =
            modifier
                .glass(PpsTheme.shapes.full)
                .clearAndSetSemantics { contentDescription = description }
                .padding(horizontal = spacing.space3, vertical = spacing.space2),
        horizontalArrangement = Arrangement.spacedBy(DOT),
    ) {
        repeat(count) { index -> Dot(active = index == current) }
    }
}

@Composable
private fun Dot(active: Boolean) {
    val colors = PpsTheme.colors
    val width by animateDpAsState(if (active) ACTIVE_WIDTH else DOT, label = "dot width")
    val color by animateColorAsState(if (active) colors.accent else colors.outline, label = "dot colour")
    Box(
        modifier =
            Modifier
                .height(DOT)
                .width(width)
                .clip(PpsTheme.shapes.full)
                .background(color),
    )
}

/** DESIGN.md `progress-dots.size`. */
private val DOT = 8.dp

/** The active dot is a pill twice as wide. */
private val ACTIVE_WIDTH = 16.dp
