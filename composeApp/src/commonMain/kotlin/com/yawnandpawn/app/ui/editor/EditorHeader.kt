package com.yawnandpawn.app.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.yawnandpawn.app.ui.components.PpsMenu
import com.yawnandpawn.app.ui.components.PpsMenuItem
import com.yawnandpawn.app.ui.format.countdownText
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.alarm_delete
import com.yawnandpawn.app.ui.resources.alarm_duplicate
import com.yawnandpawn.app.ui.resources.editor_edit_title
import com.yawnandpawn.app.ui.resources.editor_more_options
import com.yawnandpawn.app.ui.resources.editor_new_title
import com.yawnandpawn.app.ui.resources.symbol_more_vert
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * "New alarm" / "Edit alarm" in `headline` with "Rings in ..." under it; for a stored alarm a 48 dp "More options"
 * button at the end opens the overflow menu (Duplicate, Delete).
 */
@Composable
internal fun EditorHeader(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
) {
    if (!state.hasOverflowMenu) {
        EditorTitle(state = state, modifier = Modifier.padding(bottom = PpsTheme.spacing.space2))
        return
    }
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = PpsTheme.spacing.space2), verticalAlignment = Alignment.Top) {
        EditorTitle(state = state, modifier = Modifier.weight(1f))
        OverflowMenu(onIntent = onIntent)
    }
}

@Composable
private fun EditorTitle(
    state: EditorUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(if (state.isNew) Res.string.editor_new_title else Res.string.editor_edit_title),
            modifier = Modifier.semantics { heading() },
            style = PpsTheme.typography.headline,
            color = PpsTheme.colors.text,
        )
        state.ringsIn?.let { countdown ->
            Text(text = countdownText(countdown), style = PpsTheme.typography.body, color = PpsTheme.colors.textSecondary)
        }
    }
}

/** The overflow button ("More options", `text`) and its menu: Duplicate and Delete. */
@Composable
private fun OverflowMenu(onIntent: (EditorIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(PpsTheme.spacing.targetMin),
            colors = IconButtonDefaults.iconButtonColors(contentColor = PpsTheme.colors.text),
        ) {
            Icon(
                painter = painterResource(Res.drawable.symbol_more_vert),
                contentDescription = stringResource(Res.string.editor_more_options),
            )
        }
        PpsMenu(expanded = open, onDismiss = { open = false }) {
            PpsMenuItem(label = stringResource(Res.string.alarm_duplicate), onClick = {
                open = false
                onIntent(EditorIntent.DuplicateClicked)
            })
            PpsMenuItem(label = stringResource(Res.string.alarm_delete), onClick = {
                open = false
                onIntent(EditorIntent.DeleteClicked)
            })
        }
    }
}
