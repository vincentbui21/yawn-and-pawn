package com.yawnandpawn.app.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.ICON_SIZE
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.outcome_missed
import com.yawnandpawn.app.ui.resources.outcome_on_time
import com.yawnandpawn.app.ui.resources.outcome_skipped
import com.yawnandpawn.app.ui.resources.outcome_snoozed
import com.yawnandpawn.app.ui.resources.outcome_test
import com.yawnandpawn.app.ui.resources.symbol_alt_route
import com.yawnandpawn.app.ui.resources.symbol_schedule_fill1
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The outcome's label (EXPERIENCE.md glossary): "On time", "Snoozed", "Missed", "Skipped", "Test". */
@Composable
fun Outcome.label(): String =
    stringResource(
        when (this) {
            Outcome.OnTime -> Res.string.outcome_on_time
            Outcome.Snoozed -> Res.string.outcome_snoozed
            Outcome.Missed -> Res.string.outcome_missed
            Outcome.Skipped -> Res.string.outcome_skipped
            Outcome.Test -> Res.string.outcome_test
        },
    )

/**
 * `outcome-marker` (owner decision 2026-10-01, feedback item 23): the outcome carried by its shape as well as its colour,
 * so no legend is needed: on time a filled dot (`success`), snoozed a dot with a small clock (`snoozed`), missed a hollow
 * ring (`missed`), skipped or test a small neutral dot (`outline`). Decorative: the day's TalkBack text or the label
 * next to it says the outcome. [size] is the box; the shapes sit inside it.
 */
@Composable
fun OutcomeMarker(
    outcome: Outcome,
    modifier: Modifier = Modifier,
    size: Dp = ICON_SIZE,
) {
    val colors = PpsTheme.colors
    val shape = PpsTheme.shapes.full
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        when (outcome) {
            Outcome.OnTime -> {
                Box(modifier = Modifier.size(size * DOT_FRACTION).clip(shape).background(colors.success))
            }

            Outcome.Snoozed -> {
                Icon(
                    painter = painterResource(Res.drawable.symbol_schedule_fill1),
                    contentDescription = null,
                    modifier = Modifier.size(size),
                    tint = colors.snoozed,
                )
            }

            Outcome.Missed -> {
                Box(modifier = Modifier.size(size * DOT_FRACTION).border(size * RING_FRACTION, colors.missed, shape))
            }

            Outcome.Skipped, Outcome.Test -> {
                Box(modifier = Modifier.size(size * SMALL_FRACTION).clip(shape).background(colors.outline))
            }
        }
    }
}

/** The small `alt_route` badge for "fallback check used" (`text-secondary`), decorative (Day detail). */
@Composable
fun FallbackBadge(
    modifier: Modifier = Modifier,
    size: Dp = BADGE_SIZE,
) {
    Icon(
        painter = painterResource(Res.drawable.symbol_alt_route),
        contentDescription = null,
        modifier = modifier.size(size),
        tint = PpsTheme.colors.textSecondary,
    )
}

/** The filled and hollow dots fill most of the box (the clock glyph has its own margin). */
private const val DOT_FRACTION = 0.84f

/** The missed ring's stroke. */
private const val RING_FRACTION = 0.14f

/** Skipped and test: a small neutral dot. */
private const val SMALL_FRACTION = 0.42f

private val BADGE_SIZE = 12.dp
