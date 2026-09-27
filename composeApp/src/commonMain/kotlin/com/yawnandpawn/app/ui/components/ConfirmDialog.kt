package com.yawnandpawn.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.ui.theme.PpsTheme

/**
 * `dialog-confirm`: two actions, the safe one ([safeText]) is the default dismiss: Back, a tap outside and the
 * prominent end-position button all run [onSafe]. [confirmText] runs [onConfirm] and uses `error` text when
 * [destructive]. `surface` container, `rounded.lg`, title in `headline`, optional [body] in `body`.
 */
@Composable
fun ConfirmDialog(
    title: String,
    confirmText: String,
    safeText: String,
    onConfirm: () -> Unit,
    onSafe: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    destructive: Boolean = false,
) {
    val colors = PpsTheme.colors
    AlertDialog(
        onDismissRequest = onSafe,
        modifier = modifier,
        confirmButton = { PpsTextButton(text = safeText, onClick = onSafe) },
        dismissButton = {
            PpsTextButton(
                text = confirmText,
                onClick = onConfirm,
                contentColor = if (destructive) colors.error else colors.accentText,
            )
        },
        title = { Text(text = title, style = PpsTheme.typography.headline) },
        text = body?.let { { Text(text = it, style = PpsTheme.typography.body) } },
        shape = PpsTheme.shapes.lg,
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.text,
    )
}
