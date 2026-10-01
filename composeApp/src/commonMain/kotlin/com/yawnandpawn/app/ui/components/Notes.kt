package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.symbol_info
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource

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

/** A section title above a control inside a card ("Repeat"). */
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

private val NOTE_ICON_SIZE = 20.dp
