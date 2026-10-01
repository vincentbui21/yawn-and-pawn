package com.yawnandpawn.app.ui.sound

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.ICON_SIZE
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.sound_builtin
import com.yawnandpawn.app.ui.resources.sound_file_missing
import com.yawnandpawn.app.ui.resources.sound_pick_file
import com.yawnandpawn.app.ui.resources.sound_play_preview
import com.yawnandpawn.app.ui.resources.sound_stop_preview
import com.yawnandpawn.app.ui.resources.sound_system
import com.yawnandpawn.app.ui.resources.sound_your_file
import com.yawnandpawn.app.ui.resources.sound_your_files
import com.yawnandpawn.app.ui.resources.symbol_play_arrow
import com.yawnandpawn.app.ui.resources.symbol_radio_button_checked
import com.yawnandpawn.app.ui.resources.symbol_radio_button_unchecked
import com.yawnandpawn.app.ui.resources.symbol_stop
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Where a sound comes from (its section and caption). */
enum class SoundSource { BuiltIn, System, File }

/** One `sound-row`. */
data class SoundOption(
    val id: String,
    val name: String,
    val source: SoundSource,
    /** A custom file that can no longer be read: "File missing. Default sound will play." */
    val missing: Boolean = false,
)

/** The sound list of the editor's Sound sub-screen (the IA's Sound picker). */
data class SoundPickerUiState(
    val options: List<SoundOption> = emptyList(),
    val selectedId: String? = null,
    /** The sound whose preview is playing, if any. */
    val previewingId: String? = null,
)

sealed interface SoundPickerIntent {
    data class Selected(
        val id: String,
    ) : SoundPickerIntent

    data class PreviewToggled(
        val id: String,
    ) : SoundPickerIntent

    data object PickFileClicked : SoundPickerIntent
}

/**
 * The Sound picker as sections of the Sound sub-screen, stateless: a `card-group` each for built-in sounds, system
 * ringtones and the user's files ("Built-in" · "System" · "Your files"), every row with its preview button, and
 * "Pick a file" under the files.
 */
@Composable
fun SoundList(
    state: SoundPickerUiState,
    onIntent: (SoundPickerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space3)) {
        SoundSource.entries.forEach { source ->
            val options = state.options.filter { it.source == source }
            if (options.isEmpty() && source != SoundSource.File) return@forEach
            GroupCard(title = stringResource(source.sectionTitle()), modifier = Modifier.padding(top = PpsTheme.spacing.space2)) {
                options.forEachIndexed { index, option ->
                    if (index > 0) GroupDivider()
                    SoundRow(
                        option = option,
                        selected = option.id == state.selectedId,
                        previewing = option.id == state.previewingId,
                        onIntent = onIntent,
                    )
                }
                if (source == SoundSource.File) {
                    if (options.isNotEmpty()) GroupDivider()
                    PpsTextButton(
                        text = stringResource(Res.string.sound_pick_file),
                        onClick = { onIntent(SoundPickerIntent.PickFileClicked) },
                        modifier = Modifier.padding(horizontal = PpsTheme.spacing.space2),
                    )
                }
            }
        }
    }
}

/**
 * `sound-row`: 56 dp, radio selection, name in `body`, source caption, 48 dp preview button. The row is one radio
 * button for TalkBack; the preview button reads "Play preview" / "Stop preview".
 */
@Composable
private fun SoundRow(
    option: SoundOption,
    selected: Boolean,
    previewing: Boolean,
    onIntent: (SoundPickerIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = SOUND_ROW_HEIGHT).padding(end = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = SOUND_ROW_HEIGHT)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onIntent(SoundPickerIntent.Selected(option.id)) },
                    ).padding(start = spacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter =
                    painterResource(
                        if (selected) Res.drawable.symbol_radio_button_checked else Res.drawable.symbol_radio_button_unchecked,
                    ),
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE + spacing.space1),
                tint = if (selected) colors.accentText else colors.textSecondary,
            )
            Column(modifier = Modifier.padding(horizontal = spacing.space3, vertical = spacing.space2)) {
                Text(text = option.name, style = PpsTheme.typography.body, color = colors.text)
                Text(
                    text = if (option.missing) stringResource(Res.string.sound_file_missing) else stringResource(option.source.caption()),
                    style = PpsTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }
        }
        IconButton(
            onClick = { onIntent(SoundPickerIntent.PreviewToggled(option.id)) },
            modifier = Modifier.size(spacing.targetMin),
            enabled = !option.missing,
            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.text, disabledContentColor = colors.textSecondary),
        ) {
            Icon(
                painter = painterResource(if (previewing) Res.drawable.symbol_stop else Res.drawable.symbol_play_arrow),
                contentDescription = stringResource(if (previewing) Res.string.sound_stop_preview else Res.string.sound_play_preview),
            )
        }
    }
}

private fun SoundSource.sectionTitle(): StringResource =
    when (this) {
        SoundSource.BuiltIn -> Res.string.sound_builtin
        SoundSource.System -> Res.string.sound_system
        SoundSource.File -> Res.string.sound_your_files
    }

private fun SoundSource.caption(): StringResource =
    when (this) {
        SoundSource.BuiltIn -> Res.string.sound_builtin
        SoundSource.System -> Res.string.sound_system
        SoundSource.File -> Res.string.sound_your_file
    }

/** DESIGN.md `sound-row.height`. */
private val SOUND_ROW_HEIGHT = 56.dp
