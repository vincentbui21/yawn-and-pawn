package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.symbol_close
import com.yawnandpawn.app.ui.resources.symbol_error
import com.yawnandpawn.app.ui.resources.symbol_info
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * `banner-warning`: glass fill with a hairline edge, `rounded.md`, leading `error` icon in `error` (or, for [info], the `info`
 * icon in `text-secondary`), [message] in `body` and a `button-text` [actionText]. The error variant is never
 * dismissible (it clears itself); the info variant may pass [onDismiss] for a close button ([dismissLabel]).
 */
@Composable
fun BannerWarning(
    message: String,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    info: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    dismissLabel: String = "",
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .glass(PpsTheme.shapes.md)
                .padding(start = spacing.cardPadding, top = spacing.space2, bottom = spacing.space2, end = spacing.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(if (info) Res.drawable.symbol_info else Res.drawable.symbol_error),
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
            tint = if (info) colors.textSecondary else colors.error,
        )
        Text(
            text = message,
            modifier = Modifier.weight(1f).padding(horizontal = spacing.space3),
            style = PpsTheme.typography.body,
            color = colors.text,
        )
        PpsTextButton(text = actionText, onClick = onAction)
        if (onDismiss != null) DismissButton(label = dismissLabel, onClick = onDismiss)
    }
}

/** A 48 dp close button ("Dismiss" for TalkBack). */
@Composable
fun DismissButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = PpsTheme.colors.textSecondary,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(PpsTheme.spacing.targetMin),
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
    ) {
        Icon(painter = painterResource(Res.drawable.symbol_close), contentDescription = label)
    }
}

/**
 * `chip-check`: `rounded.sm`, `surface-variant` fill, `outline` border, check icon + [text] (name and difficulty) in
 * `label`, 48 dp. Tap opens Check setup.
 */
@Composable
fun CheckChipView(
    text: String,
    icon: DrawableResource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Row(
        modifier =
            modifier
                .heightIn(min = spacing.targetMin)
                .clip(PpsTheme.shapes.sm)
                .background(colors.surfaceVariant)
                .border(1.dp, colors.outline, PpsTheme.shapes.sm)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = spacing.space3, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(ICON_SIZE), tint = colors.text)
        Text(text = text, style = PpsTheme.typography.label, color = colors.text)
    }
}

/**
 * A bare `switch` for a row that has its own tap action (`card-alarm`): accent checked track, `outline` unchecked
 * border on a clear track (it sits on glass), 48 dp target. TalkBack reads [label] with the switch role and its state.
 */
@Composable
fun PpsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.semantics { contentDescription = label },
        colors = ppsSwitchColors(),
    )
}

/** `outcome-marker` and inline icon size (DESIGN.md: 20 dp). */
val ICON_SIZE = 20.dp

/** A leading icon for a row. */
@Composable
fun RowIcon(
    icon: DrawableResource,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(painter = painterResource(icon), contentDescription = null, modifier = modifier.size(ICON_SIZE), tint = tint)
}
