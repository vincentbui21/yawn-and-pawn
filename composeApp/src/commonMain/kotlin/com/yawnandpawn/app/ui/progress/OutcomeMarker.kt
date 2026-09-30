package com.yawnandpawn.app.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
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
import com.yawnandpawn.app.ui.resources.symbol_cancel_fill1
import com.yawnandpawn.app.ui.resources.symbol_check_circle_fill1
import com.yawnandpawn.app.ui.resources.symbol_radio_button_unchecked
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
 * `outcome-marker`: a distinct glyph per outcome, never colour alone: on time a filled check circle (`success`), snoozed
 * a filled clock (`snoozed`), missed a filled cross circle (`missed`), skipped or test a hollow ring (`outline`).
 * Decorative: the label next to it (legend, Day detail) or the day's TalkBack text says the outcome.
 */
@Composable
fun OutcomeMarker(
    outcome: Outcome,
    modifier: Modifier = Modifier,
    size: Dp = ICON_SIZE,
) {
    val colors = PpsTheme.colors
    val (icon, tint) =
        when (outcome) {
            Outcome.OnTime -> Res.drawable.symbol_check_circle_fill1 to colors.success
            Outcome.Snoozed -> Res.drawable.symbol_schedule_fill1 to colors.snoozed
            Outcome.Missed -> Res.drawable.symbol_cancel_fill1 to colors.missed
            Outcome.Skipped, Outcome.Test -> Res.drawable.symbol_radio_button_unchecked to colors.outline
        }
    Icon(painter = painterResource(icon), contentDescription = null, modifier = modifier.size(size), tint = tint)
}

/** The small `alt_route` badge for "fallback check used" (`text-secondary`), decorative. */
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

/** A marker with its visible label (legend, Day detail): read as the label alone. */
@Composable
fun OutcomeLabel(
    outcome: Outcome,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space1),
    ) {
        OutcomeMarker(outcome)
        Text(text = outcome.label(), style = PpsTheme.typography.caption, color = PpsTheme.colors.text)
    }
}

/** The fallback badge in the calendar (a corner of the day's marker). */
private val BADGE_SIZE = 12.dp
