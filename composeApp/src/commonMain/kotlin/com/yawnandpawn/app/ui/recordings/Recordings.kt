package com.yawnandpawn.app.ui.recordings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.AppSnackbar
import com.yawnandpawn.app.ui.components.BannerWarning
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.PpsFilledButton
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.editor_save
import com.yawnandpawn.app.ui.resources.home_fix
import com.yawnandpawn.app.ui.resources.recordings_delete_dialog
import com.yawnandpawn.app.ui.resources.recordings_delete_named
import com.yawnandpawn.app.ui.resources.recordings_empty
import com.yawnandpawn.app.ui.resources.recordings_mic_off
import com.yawnandpawn.app.ui.resources.recordings_name
import com.yawnandpawn.app.ui.resources.recordings_pause
import com.yawnandpawn.app.ui.resources.recordings_play
import com.yawnandpawn.app.ui.resources.recordings_rerecord
import com.yawnandpawn.app.ui.resources.recordings_start
import com.yawnandpawn.app.ui.resources.recordings_stop
import com.yawnandpawn.app.ui.resources.recordings_time
import com.yawnandpawn.app.ui.resources.recordings_title
import com.yawnandpawn.app.ui.resources.recordings_too_short
import com.yawnandpawn.app.ui.resources.recordings_yours
import com.yawnandpawn.app.ui.resources.settings_delete_confirm
import com.yawnandpawn.app.ui.resources.settings_delete_keep
import com.yawnandpawn.app.ui.resources.symbol_delete
import com.yawnandpawn.app.ui.resources.symbol_mic
import com.yawnandpawn.app.ui.resources.symbol_pause
import com.yawnandpawn.app.ui.resources.symbol_play_arrow
import com.yawnandpawn.app.ui.resources.symbol_stop
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.TABULAR_FIGURES
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Where the `recorder` is. */
sealed interface RecorderState {
    data object Idle : RecorderState

    /** Recording for [seconds] (auto-stop at [RecordingsUiState.MAX_SECONDS]); [levels] 0 to 1 draw the level meter. */
    data class Recording(
        val seconds: Int,
        val levels: List<Float>,
    ) : RecorderState

    /** A take of [seconds] waiting to be played, re-recorded, saved or deleted. */
    data class Recorded(
        val seconds: Int,
        val playing: Boolean = false,
    ) : RecorderState
}

/** One saved motivation message, named "Message {number}". */
data class RecordedMessage(
    val number: Int,
    val seconds: Int,
    /** Playing, [playedSeconds] into it. */
    val playing: Boolean = false,
    val playedSeconds: Int = 0,
)

/** What Recordings renders (FR-SND-3). */
data class RecordingsUiState(
    val messages: List<RecordedMessage> = emptyList(),
    val recorder: RecorderState = RecorderState.Idle,
    /** Microphone permission denied: "Microphone is off. Turn it on in Settings." with "Fix". */
    val micDenied: Boolean = false,
    /** The take was too short: the "Too short. Try again." snackbar. */
    val tooShort: Boolean = false,
    /** The message whose delete dialog is open. */
    val deleteDialogFor: Int? = null,
) {
    companion object {
        /** The recorder stops by itself at 60 s. */
        const val MAX_SECONDS = 60
    }
}

/** Everything the user can do in Recordings. */
sealed interface RecordingsIntent {
    data object Back : RecordingsIntent

    data object RecordClicked : RecordingsIntent

    data object StopClicked : RecordingsIntent

    data object TakePlayToggled : RecordingsIntent

    data object ReRecordClicked : RecordingsIntent

    data object SaveTakeClicked : RecordingsIntent

    data object DeleteTakeClicked : RecordingsIntent

    data class PlayToggled(
        val number: Int,
    ) : RecordingsIntent

    data class DeleteClicked(
        val number: Int,
    ) : RecordingsIntent

    data object DeleteConfirmed : RecordingsIntent

    data object DeleteCancelled : RecordingsIntent

    data object FixMicrophone : RecordingsIntent
}

/**
 * Recordings, stateless (IA: from the editor's Motivation sub-screen, "Record a message"): the `recorder` in a glass card
 * ("Record a message for your morning self." while there are none; elapsed and max time "0:09 / 1:00" in `display`; the
 * level meter; the 72 dp record button, "Start recording" / "Stop recording"); after a take "Play message", "Re-record",
 * "Delete" and `button-filled` "Save"; then "Your messages" with play / pause and delete for each ("Delete this
 * message? Alarms using it will play no message."). Microphone denied: "Microphone is off. Turn it on in Settings."
 * with "Fix"; a take under 1 s: the "Too short. Try again." snackbar.
 */
@Composable
fun RecordingsScreen(
    state: RecordingsUiState,
    onIntent: (RecordingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        SubScreen(
            title = stringResource(Res.string.recordings_title),
            backContentDescription = stringResource(Res.string.editor_back),
            onBack = { onIntent(RecordingsIntent.Back) },
        ) {
            if (state.micDenied) {
                BannerWarning(
                    message = stringResource(Res.string.recordings_mic_off),
                    actionText = stringResource(Res.string.home_fix),
                    onAction = { onIntent(RecordingsIntent.FixMicrophone) },
                )
            }
            RecorderCard(state = state, onIntent = onIntent)
            if (state.messages.isNotEmpty()) MessagesCard(messages = state.messages, onIntent = onIntent)
        }
        if (state.tooShort) {
            AppSnackbar(text = stringResource(Res.string.recordings_too_short), modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
    state.deleteDialogFor?.let {
        ConfirmDialog(
            title = stringResource(Res.string.recordings_delete_dialog),
            confirmText = stringResource(Res.string.settings_delete_confirm),
            safeText = stringResource(Res.string.settings_delete_keep),
            onConfirm = { onIntent(RecordingsIntent.DeleteConfirmed) },
            onSafe = { onIntent(RecordingsIntent.DeleteCancelled) },
            destructive = true,
        )
    }
}

/** `recorder`: the prompt, the time, the level meter, the record button, and a take's actions. */
@Composable
private fun RecorderCard(
    state: RecordingsUiState,
    onIntent: (RecordingsIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val recorder = state.recorder
    GroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.cardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            if (state.messages.isEmpty() && recorder == RecorderState.Idle) {
                Text(
                    text = stringResource(Res.string.recordings_empty),
                    style = PpsTheme.typography.body,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                )
            }
            val seconds =
                when (recorder) {
                    RecorderState.Idle -> 0
                    is RecorderState.Recording -> recorder.seconds
                    is RecorderState.Recorded -> recorder.seconds
                }
            Text(
                text = stringResource(Res.string.recordings_time, duration(seconds), duration(RecordingsUiState.MAX_SECONDS)),
                style = PpsTheme.typography.display.copy(fontFeatureSettings = TABULAR_FIGURES),
                color = colors.text,
                textAlign = TextAlign.Center,
            )
            LevelMeter(levels = (recorder as? RecorderState.Recording)?.levels.orEmpty())
            if (recorder is RecorderState.Recorded) {
                TakeActions(take = recorder, onIntent = onIntent)
            } else {
                RecordButton(recording = recorder is RecorderState.Recording, enabled = !state.micDenied, onIntent = onIntent)
            }
        }
    }
}

/** The 72 dp round record button: accent with the on-accent mic, or the stop square while recording; disabled without a mic. */
@Composable
private fun RecordButton(
    recording: Boolean,
    enabled: Boolean,
    onIntent: (RecordingsIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val label = stringResource(if (recording) Res.string.recordings_stop else Res.string.recordings_start)
    Box(
        modifier =
            Modifier
                .size(PpsTheme.spacing.targetWakeHero)
                .clip(PpsTheme.shapes.full)
                .background(if (enabled) colors.accent else colors.disabledContainer)
                .clickable(enabled = enabled, role = Role.Button) {
                    onIntent(if (recording) RecordingsIntent.StopClicked else RecordingsIntent.RecordClicked)
                }.semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (recording) Res.drawable.symbol_stop else Res.drawable.symbol_mic),
            contentDescription = null,
            tint = if (enabled) colors.onAccent else colors.disabledContent,
        )
    }
}

/** After a take: play, "Re-record", "Delete" (wrapping at large font scales), then "Save". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TakeActions(
    take: RecorderState.Recorded,
    onIntent: (RecordingsIntent) -> Unit,
) {
    val spacing = PpsTheme.spacing
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.space1, Alignment.CenterHorizontally),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        PlayButton(playing = take.playing, onClick = { onIntent(RecordingsIntent.TakePlayToggled) })
        PpsTextButton(text = stringResource(Res.string.recordings_rerecord), onClick = { onIntent(RecordingsIntent.ReRecordClicked) })
        PpsTextButton(
            text = stringResource(Res.string.settings_delete_confirm),
            onClick = { onIntent(RecordingsIntent.DeleteTakeClicked) },
            contentColor = PpsTheme.colors.error,
        )
    }
    PpsFilledButton(
        text = stringResource(Res.string.editor_save),
        onClick = { onIntent(RecordingsIntent.SaveTakeClicked) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The level meter: thin bars in `text-secondary` (flat when not recording); decorative, so TalkBack skips it. */
@Composable
private fun LevelMeter(levels: List<Float>) {
    val colors = PpsTheme.colors
    Row(
        modifier = Modifier.height(METER_HEIGHT).clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(BARS) { index ->
            val level = levels.getOrNull(index) ?: 0f
            Box(
                modifier =
                    Modifier
                        .width(BAR_WIDTH)
                        .height(BAR_MIN + (METER_HEIGHT - BAR_MIN) * level.coerceIn(0f, 1f))
                        .clip(PpsTheme.shapes.full)
                        .background(colors.textSecondary),
            )
        }
    }
}

/** "Your messages": one row per saved message with play / pause and delete. */
@Composable
private fun MessagesCard(
    messages: List<RecordedMessage>,
    onIntent: (RecordingsIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    GroupCard(title = stringResource(Res.string.recordings_yours)) {
        messages.forEachIndexed { index, message ->
            if (index > 0) GroupDivider()
            val name = stringResource(Res.string.recordings_name, message.number)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).padding(start = spacing.cardPadding, end = spacing.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
                    Text(text = name, style = PpsTheme.typography.body, color = colors.text)
                    Text(
                        text =
                            if (message.playing) {
                                stringResource(Res.string.recordings_time, duration(message.playedSeconds), duration(message.seconds))
                            } else {
                                duration(message.seconds)
                            },
                        style = PpsTheme.typography.caption.copy(fontFeatureSettings = TABULAR_FIGURES),
                        color = colors.textSecondary,
                    )
                }
                PlayButton(playing = message.playing, onClick = { onIntent(RecordingsIntent.PlayToggled(message.number)) })
                IconAction(
                    icon = Res.drawable.symbol_delete,
                    label = stringResource(Res.string.recordings_delete_named, name),
                    onClick = { onIntent(RecordingsIntent.DeleteClicked(message.number)) },
                )
            }
        }
    }
}

/** 48 dp "Play message" / "Pause message". */
@Composable
private fun PlayButton(
    playing: Boolean,
    onClick: () -> Unit,
) = IconAction(
    icon = if (playing) Res.drawable.symbol_pause else Res.drawable.symbol_play_arrow,
    label = stringResource(if (playing) Res.string.recordings_pause else Res.string.recordings_play),
    onClick = onClick,
)

@Composable
private fun IconAction(
    icon: DrawableResource,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(PpsTheme.spacing.targetMin),
        colors = IconButtonDefaults.iconButtonColors(contentColor = PpsTheme.colors.text),
    ) {
        Icon(painter = painterResource(icon), contentDescription = label)
    }
}

/** "0:09", "1:00": minutes and two-digit seconds (numbers only, not copy). */
private fun duration(seconds: Int): String = "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"

private const val SECONDS_PER_MINUTE = 60
private const val BARS = 24
private val METER_HEIGHT = 32.dp
private val BAR_WIDTH = 3.dp
private val BAR_GAP = 3.dp
private val BAR_MIN = 4.dp
private val ROW_HEIGHT = 56.dp
