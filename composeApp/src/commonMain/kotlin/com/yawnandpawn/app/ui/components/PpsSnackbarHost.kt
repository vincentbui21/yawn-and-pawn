package com.yawnandpawn.app.ui.components

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.ui.theme.PpsTheme

/** `snackbar` on app screens: `inverse-surface` container, `inverse-text` message, `inverse-accent` action, `rounded.sm`. */
@Composable
fun PpsSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = PpsTheme.shapes.sm,
            containerColor = colors.inverseSurface,
            contentColor = colors.inverseText,
            actionColor = colors.inverseAccent,
        )
    }
}
