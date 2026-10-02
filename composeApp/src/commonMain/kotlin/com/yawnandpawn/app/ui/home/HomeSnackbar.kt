package com.yawnandpawn.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.ui.components.AppSnackbar
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_save_failed
import com.yawnandpawn.app.ui.resources.home_open_failed
import org.jetbrains.compose.resources.stringResource

/**
 * Home's `snackbar`, one at a time: "Couldn't open this alarm." or "Couldn't save the alarm. Try again." (a switch,
 * Duplicate or Delete that could not be stored, owner decision 2026-10-02). Nothing when neither is showing.
 */
@Composable
internal fun HomeSnackbar(
    state: HomeUiState,
    modifier: Modifier = Modifier,
) {
    val message =
        when {
            state.openFailed -> Res.string.home_open_failed
            state.saveFailed -> Res.string.editor_save_failed
            else -> return
        }
    AppSnackbar(text = stringResource(message), modifier = modifier)
}
