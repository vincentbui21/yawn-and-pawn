package com.yawnandpawn.app.ui.progress

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.rememberReducedMotion
import com.yawnandpawn.app.ui.format.DayNameStyle
import com.yawnandpawn.app.ui.format.dayName
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.progress_chip
import com.yawnandpawn.app.ui.resources.progress_day_outcome
import com.yawnandpawn.app.ui.resources.progress_open_day_a11y
import com.yawnandpawn.app.ui.resources.symbol_chevron_right
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The label chip of a tapped dot or day ("Tue 23 · Snoozed" with a chevron): `inverse-surface` / `inverse-text` like the
 * snackbar, a 48 dp button that opens Day detail. It pops in with a small spring (instant with reduced motion). TalkBack
 * reads "{date}, {outcome}" with "open day detail" as its double-tap action.
 */
@Composable
internal fun DayChip(
    selection: DaySelection?,
    onOpen: (DaySelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReducedMotion()
    // Keeps the last selection while the chip animates out.
    var shown by remember { mutableStateOf(selection) }
    if (selection != null) shown = selection
    AnimatedVisibility(
        visible = selection != null,
        modifier = modifier,
        enter = if (reduced) fadeIn(snap()) else scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
        exit = if (reduced) fadeOut(snap()) else scaleOut() + fadeOut(),
    ) {
        val day = shown ?: return@AnimatedVisibility
        val colors = PpsTheme.colors
        val weekday = dayName(day.date.dayOfWeek, DayNameStyle.Short)
        val description =
            stringResource(
                Res.string.progress_day_outcome,
                dayName(day.date.dayOfWeek, DayNameStyle.Full),
                day.date.day,
                day.outcome.label(),
            )
        val openLabel = stringResource(Res.string.progress_open_day_a11y)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = PpsTheme.spacing.targetMin)
                    .clickable(role = Role.Button, onClickLabel = openLabel, onClick = { onOpen(day) })
                    .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier =
                    Modifier
                        .clip(PpsTheme.shapes.full)
                        .background(colors.inverseSurface)
                        .padding(
                            start = PpsTheme.spacing.space4,
                            end = PpsTheme.spacing.space2,
                            top = PpsTheme.spacing.space2,
                            bottom = PpsTheme.spacing.space2,
                        ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space1),
            ) {
                Text(
                    text = stringResource(Res.string.progress_chip, weekday, day.date.day, day.outcome.label()),
                    style = PpsTheme.typography.label,
                    color = colors.inverseText,
                )
                Icon(
                    painter = painterResource(Res.drawable.symbol_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(CHEVRON),
                    tint = colors.inverseText,
                )
            }
        }
    }
}

private val CHEVRON = 18.dp
