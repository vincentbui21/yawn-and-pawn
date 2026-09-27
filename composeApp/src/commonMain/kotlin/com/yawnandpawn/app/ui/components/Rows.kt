package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.symbol_chevron_right
import com.yawnandpawn.app.ui.resources.symbol_info
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource

/**
 * A `switch` with its label: the whole row (≥ 48 dp) toggles, and TalkBack reads [label] with the switch role and
 * its on/off state. Material 3 switch with accent checked track and `outline` unchecked border.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = SETTINGS_ROW_HEIGHT)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(end = PpsTheme.spacing.space4),
            style = PpsTheme.typography.body,
            color = colors.text,
        )
        Switch(
            checked = checked,
            // The row handles the toggle, so TalkBack sees one control, not two.
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    checkedBorderColor = colors.accent,
                    uncheckedThumbColor = colors.outline,
                    // Rows sit on `bg`: outline on bg is in the DESIGN.md contrast table (3.53 light, 3.62 dark).
                    uncheckedTrackColor = colors.bg,
                    uncheckedBorderColor = colors.outline,
                ),
        )
    }
}

/**
 * A read-only `settings-row`: [label] left, [value] in `text-secondary` right, 56 dp. Not tappable; TalkBack reads
 * label and value together.
 */
@Composable
fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = SETTINGS_ROW_HEIGHT).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(end = PpsTheme.spacing.space4),
            style = PpsTheme.typography.body,
            color = PpsTheme.colors.text,
        )
        Text(text = value, style = PpsTheme.typography.body, color = PpsTheme.colors.textSecondary)
    }
}

/** `note-inline`: leading `info` icon and a `caption` in `text-secondary`. Read-only. */
@Composable
fun NoteInline(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    Row(modifier = modifier.semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(Res.drawable.symbol_info),
            contentDescription = null,
            modifier = Modifier.size(NOTE_ICON_SIZE),
            tint = colors.textSecondary,
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = PpsTheme.spacing.space2),
            style = PpsTheme.typography.caption,
            color = colors.textSecondary,
        )
    }
}

/** A section title above a control ("Repeat", "Snooze length"). */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(bottom = PpsTheme.spacing.space2),
        style = PpsTheme.typography.body,
        color = PpsTheme.colors.text,
    )
}

/** DESIGN.md `settings-row.height`. */
private val SETTINGS_ROW_HEIGHT = 56.dp

private val NOTE_ICON_SIZE = 20.dp

/**
 * A tappable `settings-row`: [label] left, optional [value] in `text-secondary` and a chevron right, 56 dp. TalkBack
 * reads label and value as one button.
 */
@Composable
fun NavRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
) {
    val colors = PpsTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = SETTINGS_ROW_HEIGHT)
                .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(end = PpsTheme.spacing.space4),
            style = PpsTheme.typography.body,
            color = colors.text,
        )
        if (value != null) {
            Text(
                text = value,
                modifier = Modifier.weight(1f, fill = false),
                style = PpsTheme.typography.body,
                color = colors.textSecondary,
                textAlign = TextAlign.End,
            )
        }
        Icon(
            painter = painterResource(Res.drawable.symbol_chevron_right),
            contentDescription = null,
            modifier = Modifier.padding(start = PpsTheme.spacing.space2).size(NOTE_ICON_SIZE + PpsTheme.spacing.space1),
            tint = colors.textSecondary,
        )
    }
}

/** An inline field error in `error` (`caption`), announced politely when it appears. */
@Composable
fun InlineError(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = PpsTheme.typography.caption,
        color = PpsTheme.colors.error,
    )
}
