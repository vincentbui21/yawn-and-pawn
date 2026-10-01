package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.symbol_check_box
import com.yawnandpawn.app.ui.resources.symbol_check_box_outline_blank
import com.yawnandpawn.app.ui.resources.symbol_chevron_right
import com.yawnandpawn.app.ui.resources.symbol_info
import com.yawnandpawn.app.ui.resources.symbol_radio_button_checked
import com.yawnandpawn.app.ui.resources.symbol_radio_button_unchecked
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.TABULAR_FIGURES
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** The side padding and minimum height every row of a `card-group` shares. */
@Composable
private fun Modifier.rowFrame(): Modifier =
    fillMaxWidth()
        .heightIn(min = SETTINGS_ROW_HEIGHT)
        .padding(horizontal = PpsTheme.spacing.cardPadding, vertical = PpsTheme.spacing.space2)

/** Title in `body` / `text` with an optional subtitle in `caption` / `text-secondary`. */
@Composable
private fun RowTexts(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    titleColor: Color = PpsTheme.colors.text,
) {
    Column(modifier = modifier) {
        Text(text = title, style = PpsTheme.typography.body, color = titleColor)
        if (subtitle != null) Text(text = subtitle, style = PpsTheme.typography.caption, color = PpsTheme.colors.textSecondary)
    }
}

/**
 * A `switch` row of a `card-group`: the whole row (≥ 56 dp) toggles, and TalkBack reads [label] with the switch role and
 * its on/off state. Material 3 switch with accent checked track; unchecked it is an `outline` ring on the glass.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier =
            modifier
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .rowFrame(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTexts(title = label, subtitle = subtitle, modifier = Modifier.weight(1f).padding(end = PpsTheme.spacing.space4))
        // The row handles the toggle, so TalkBack sees one control, not two.
        Switch(checked = checked, onCheckedChange = null, colors = ppsSwitchColors())
    }
}

/** Switch colours on glass: accent / on-accent checked; unchecked an `outline` border and thumb on a clear track. */
@Composable
internal fun ppsSwitchColors() =
    PpsTheme.colors.let { colors ->
        SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.outline,
            // Clear, so the glass shows through: outline on glass is in the DESIGN.md contrast table.
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = colors.outline,
        )
    }

/**
 * A read-only `settings-row`: [label] with [value] as its subtitle. Not tappable; TalkBack reads label and value
 * together.
 */
@Composable
fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.semantics(mergeDescendants = true) { }.rowFrame(), verticalAlignment = Alignment.CenterVertically) {
        RowTexts(title = label, subtitle = value, modifier = Modifier.weight(1f))
    }
}

/**
 * A tappable `settings-row` (progressive disclosure): [label] with the current [value] as a subtitle and a chevron; the
 * tap opens the sub-screen that sets it. TalkBack reads label and value as one button. [titleColor] `error` marks a
 * destructive row ("Delete all data", which opens a dialog, so [chevron] is off). Disabled ([enabled] false), the title
 * is `text-secondary` and the row is not tappable ("Export CSV" with nothing to export).
 */
@Composable
fun NavRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    icon: DrawableResource? = null,
    enabled: Boolean = true,
    titleColor: Color = PpsTheme.colors.text,
    chevron: Boolean = true,
) {
    val colors = PpsTheme.colors
    Row(
        modifier = modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).rowFrame(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) RowIcon(icon = icon, tint = colors.text, modifier = Modifier.padding(end = PpsTheme.spacing.space3))
        RowTexts(
            title = label,
            subtitle = value,
            modifier = Modifier.weight(1f),
            titleColor = if (enabled) titleColor else colors.textSecondary,
        )
        if (chevron && enabled) {
            Icon(
                painter = painterResource(Res.drawable.symbol_chevron_right),
                contentDescription = null,
                modifier = Modifier.padding(start = PpsTheme.spacing.space2).size(CHEVRON_SIZE),
                tint = colors.textSecondary,
            )
        }
    }
}

/**
 * A read-only row of a `card-group` with its value on the right ("This week" · "$3", "Rings" · "2"): [label] in `body`
 * / `text`, [value] in `body` / `text` with tabular figures (money is always `text`, never green or red). TalkBack reads
 * both as one.
 */
@Composable
fun ValueEndRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    Row(modifier = modifier.semantics(mergeDescendants = true) { }.rowFrame(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(end = PpsTheme.spacing.space3),
            style = PpsTheme.typography.body,
            color = colors.text,
        )
        Text(text = value, style = PpsTheme.typography.body.copy(fontFeatureSettings = TABULAR_FIGURES), color = colors.text)
    }
}

/**
 * One option of a single-choice list in a `card-group` (snooze length, message, when it plays): radio icon (`accent-text`
 * when selected), [label] and an optional [subtitle]. The row is one radio button for TalkBack with its selected state.
 */
@Composable
fun RadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = PpsTheme.colors
    Row(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).rowFrame(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter =
                painterResource(
                    if (selected) Res.drawable.symbol_radio_button_checked else Res.drawable.symbol_radio_button_unchecked,
                ),
            contentDescription = null,
            modifier = Modifier.size(CHEVRON_SIZE),
            tint = if (selected) colors.accentText else colors.textSecondary,
        )
        RowTexts(title = label, subtitle = subtitle, modifier = Modifier.weight(1f).padding(start = PpsTheme.spacing.space3))
    }
}

/**
 * One option of a multiple-choice list in a `card-group` (the wake-up checks): optional leading [icon], [label],
 * [subtitle] and a check box (`accent-text` when checked). The row is one checkbox for TalkBack with its state.
 */
@Composable
fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: DrawableResource? = null,
) {
    val colors = PpsTheme.colors
    Row(
        modifier = modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange).rowFrame(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) RowIcon(icon = icon, tint = colors.text, modifier = Modifier.padding(end = PpsTheme.spacing.space3))
        RowTexts(title = label, subtitle = subtitle, modifier = Modifier.weight(1f).padding(end = PpsTheme.spacing.space3))
        Icon(
            painter = painterResource(if (checked) Res.drawable.symbol_check_box else Res.drawable.symbol_check_box_outline_blank),
            contentDescription = null,
            modifier = Modifier.size(CHEVRON_SIZE),
            tint = if (checked) colors.accentText else colors.textSecondary,
        )
    }
}

/**
 * An inline text field row of a `card-group` ("Alarm name"): [label] as a small title above the typed text, which uses
 * the system keyboard. On error, [errorText] in `error` below it, announced politely. TalkBack reads [label].
 */
@Composable
fun TextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    errorText: String? = null,
) {
    val colors = PpsTheme.colors
    // The whole row is the field (its tap target), with the label drawn inside it above the typed text.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier.fillMaxWidth().semantics {
                contentDescription = label
                if (errorText != null) error(errorText)
            },
        textStyle = PpsTheme.typography.body.copy(color = colors.text),
        singleLine = true,
        cursorBrush = SolidColor(colors.text),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        decorationBox = { innerTextField ->
            Column(modifier = Modifier.rowFrame(), verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space1)) {
                Text(
                    text = label,
                    style = PpsTheme.typography.caption,
                    color =
                        if (errorText !=
                            null
                        ) {
                            colors.error
                        } else {
                            colors.textSecondary
                        },
                )
                innerTextField()
                if (errorText != null) InlineError(text = errorText)
            }
        },
    )
}

/** DESIGN.md `settings-row.height`. */
private val SETTINGS_ROW_HEIGHT = 56.dp

/** Chevron, radio and check box icons. */
private val CHEVRON_SIZE = 24.dp
