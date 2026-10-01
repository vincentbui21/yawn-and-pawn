package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.theme.PpsTheme

/** `button-filled`: the one primary action of an app screen. Accent fill, on-accent `label`, full radius, 48 dp min. */
@Composable
fun PpsFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = PpsTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = PpsTheme.spacing.targetMin),
        enabled = enabled,
        shape = PpsTheme.shapes.full,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent,
                disabledContainerColor = colors.disabledContainer,
                disabledContentColor = colors.disabledContent,
            ),
    ) {
        Text(text = text, style = PpsTheme.typography.label, textAlign = TextAlign.Center)
    }
}

/**
 * `button-text`: a tertiary action, 48 dp target, `accent-text` label. [contentColor] overrides it for a
 * destructive dialog action (`error`); disabled, the label is `disabled-content`.
 */
@Composable
fun PpsTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = PpsTheme.colors.accentText,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = PpsTheme.spacing.targetMin),
        enabled = enabled,
        shape = PpsTheme.shapes.full,
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor, disabledContentColor = PpsTheme.colors.disabledContent),
    ) {
        Text(text = text, style = PpsTheme.typography.label)
    }
}

/** `button-outlined`: an app secondary action. 48 dp, full radius, `outline` border, `text` label in `label`. */
@Composable
fun PpsOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = PpsTheme.spacing.targetMin),
        shape = PpsTheme.shapes.full,
        border = BorderStroke(1.dp, colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
    ) {
        Text(text = text, style = PpsTheme.typography.label, textAlign = TextAlign.Center)
    }
}
