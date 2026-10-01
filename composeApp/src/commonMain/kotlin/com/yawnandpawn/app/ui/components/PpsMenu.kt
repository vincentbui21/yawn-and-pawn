package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.theme.PpsTheme

/**
 * A small action menu (the `card-alarm` long-press menu and the editor's overflow menu, EXPERIENCE.md `card-alarm`):
 * opaque `surface` like `dialog-confirm` (DESIGN.md Elevation: no shadow), `rounded.md` with the `outline-subtle`
 * hairline, holding [PpsMenuItem]s. Back or a tap outside runs [onDismiss].
 */
@Composable
fun PpsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PpsTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = PpsTheme.shapes.md,
        containerColor = colors.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(PpsTheme.spacing.hairline, colors.outlineSubtle),
        content = content,
    )
}

/** One [PpsMenu] item: a 48 dp row with [label] in `body` / `text`. */
@Composable
fun PpsMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenuItem(
        text = { Text(text = label, style = PpsTheme.typography.body) },
        onClick = onClick,
        modifier = modifier.heightIn(min = PpsTheme.spacing.targetMin),
        colors = MenuDefaults.itemColors(textColor = PpsTheme.colors.text),
    )
}
