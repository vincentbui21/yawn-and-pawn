package com.yawnandpawn.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.ui.components.AppSnackbar
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_save_failed
import com.yawnandpawn.app.ui.resources.home_open_failed
import com.yawnandpawn.app.ui.settings.weakeningNoteText
import org.jetbrains.compose.resources.stringResource

/**
 * Home's `snackbar`, one at a time: "Couldn't open this alarm.", "Couldn't save the alarm. Try again." (a switch
 * or Delete that could not be stored, owner decision 2026-10-02), or after the editor saved a weakening change under the
 * commitment lock "Saved. Takes effect after tomorrow's {time} alarm." (or "today's", {time} per [is24Hour]; Story 4.6).
 * Nothing when none is showing.
 */
@Composable
internal fun HomeSnackbar(
    state: HomeUiState,
    is24Hour: Boolean,
    modifier: Modifier = Modifier,
) {
    val text =
        when {
            state.openFailed -> stringResource(Res.string.home_open_failed)
            state.saveFailed -> stringResource(Res.string.editor_save_failed)
            else -> state.savedUnderLock?.let { weakeningNoteText(it, is24Hour) } ?: return
        }
    AppSnackbar(text = text, modifier = modifier)
}
