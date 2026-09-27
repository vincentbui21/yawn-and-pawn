package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.yawnandpawn.app.ui.format.DayNameStyle
import com.yawnandpawn.app.ui.format.WeekOrder
import com.yawnandpawn.app.ui.format.dayName
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.datetime.DayOfWeek

/**
 * `chip-day`: a 48 dp toggle with one letter. Unselected `surface-variant` with `text`; selected accent fill with a
 * bold on-accent letter. TalkBack reads [fullName] ("Monday") with the checkbox role and its checked state.
 */
@Composable
fun DayChip(
    letter: String,
    fullName: String,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    Box(
        modifier =
            modifier
                .widthIn(max = PpsTheme.spacing.targetMin)
                .height(PpsTheme.spacing.targetMin)
                .clip(PpsTheme.shapes.sm)
                .background(if (selected) colors.accent else colors.surfaceVariant)
                .semantics { contentDescription = fullName }
                .toggleable(value = selected, role = Role.Checkbox, onValueChange = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            // TalkBack reads the full day name (set on the chip), not the letter.
            modifier = Modifier.clearAndSetSemantics { },
            style = PpsTheme.typography.label,
            fontWeight = if (selected) FontWeight.Bold else null,
            color = if (selected) colors.onAccent else colors.text,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * The seven repeat chips, Monday first, always on one line: equal-width slots of at most 48 dp. On a 360 dp screen a
 * chip is drawn about 42 dp wide; Compose still gives it a 48 dp touch target (its touch bounds extend to the minimum
 * touch-target size into the gaps), checked by the semantics tests at 360 dp.
 */
@Composable
fun DayChipRow(
    selectedDays: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space1),
    ) {
        WeekOrder.forEach { day ->
            DayChip(
                letter = dayName(day, DayNameStyle.Narrow),
                fullName = dayName(day, DayNameStyle.Full),
                selected = day in selectedDays,
                onToggle = { onToggle(day) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
