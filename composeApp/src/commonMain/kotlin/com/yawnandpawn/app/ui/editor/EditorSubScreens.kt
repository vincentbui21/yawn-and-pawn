package com.yawnandpawn.app.ui.editor

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.ui.checkpicker.CheckPickerContent
import com.yawnandpawn.app.ui.checkpicker.CheckPickerIntent
import com.yawnandpawn.app.ui.checkpicker.CheckPickerUiState
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PercentSlider
import com.yawnandpawn.app.ui.components.RadioRow
import com.yawnandpawn.app.ui.components.StepSlider
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.components.SwitchRow
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_after_im_up
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.editor_fee_ladder
import com.yawnandpawn.app.ui.resources.editor_grace_seconds
import com.yawnandpawn.app.ui.resources.editor_gradual_volume
import com.yawnandpawn.app.ui.resources.editor_message
import com.yawnandpawn.app.ui.resources.editor_message_none
import com.yawnandpawn.app.ui.resources.editor_message_random
import com.yawnandpawn.app.ui.resources.editor_mix_into_alarm
import com.yawnandpawn.app.ui.resources.editor_motivation
import com.yawnandpawn.app.ui.resources.editor_percent
import com.yawnandpawn.app.ui.resources.editor_quiet_time
import com.yawnandpawn.app.ui.resources.editor_quiet_time_note
import com.yawnandpawn.app.ui.resources.editor_record_message
import com.yawnandpawn.app.ui.resources.editor_snooze
import com.yawnandpawn.app.ui.resources.editor_snooze_length
import com.yawnandpawn.app.ui.resources.editor_snooze_minutes
import com.yawnandpawn.app.ui.resources.editor_sound
import com.yawnandpawn.app.ui.resources.editor_vibrate_quiet_time
import com.yawnandpawn.app.ui.resources.editor_volume
import com.yawnandpawn.app.ui.resources.editor_wake_check
import com.yawnandpawn.app.ui.resources.editor_when_it_plays
import com.yawnandpawn.app.ui.resources.sound_file_missing
import com.yawnandpawn.app.ui.sound.SoundList
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One editor sub-screen (progressive disclosure, owner decision 2026-09-27), stateless: a back arrow and its title, then
 * `card-group`s that set one row of the main screen. Back returns to the main screen (the change stays in the form).
 */
@Composable
internal fun EditorSubScreen(
    pane: EditorPane,
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
) {
    val full = state.full
    SubScreen(
        title = stringResource(pane.title()),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = { onIntent(EditorIntent.BackRequested) },
    ) {
        when (pane) {
            EditorPane.Main -> Unit
            EditorPane.Sound -> SoundPane(state = state, onIntent = onIntent)
            EditorPane.Snooze -> SnoozePane(state = state, onIntent = onIntent)
            EditorPane.WakeCheck -> full?.let { WakeCheckPane(full = it, onIntent = onIntent) }
            EditorPane.QuietTime -> full?.let { QuietTimePane(full = it, onIntent = onIntent) }
            EditorPane.Motivation -> full?.let { MotivationPane(full = it, onIntent = onIntent) }
        }
    }
}

private fun EditorPane.title(): StringResource =
    when (this) {
        EditorPane.Main, EditorPane.Sound -> Res.string.editor_sound
        EditorPane.Snooze -> Res.string.editor_snooze
        EditorPane.WakeCheck -> Res.string.editor_wake_check
        EditorPane.QuietTime -> Res.string.editor_quiet_time
        EditorPane.Motivation -> Res.string.editor_motivation
    }

/**
 * Sound: the volume slider and "Gradually increase volume" (owner decision 2026-09-27: no starting-volume slider; the
 * ramp always starts at 20% and rises to the set volume over 30 s), then the sound list with previews (full editor).
 */
@Composable
private fun SoundPane(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
) {
    val form = state.form
    GroupCard {
        PercentSlider(
            title = stringResource(Res.string.editor_volume),
            valueText = stringResource(Res.string.editor_percent, form.volumePercent),
            percent = form.volumePercent,
            onPercentChange = { onIntent(EditorIntent.VolumeChanged(it)) },
            modifier = Modifier.padding(horizontal = PpsTheme.spacing.cardPadding, vertical = PpsTheme.spacing.space3),
        )
        GroupDivider()
        SwitchRow(
            label = stringResource(Res.string.editor_gradual_volume),
            checked = form.gradualVolume,
            onCheckedChange = { onIntent(EditorIntent.GradualVolumeToggled(it)) },
        )
    }
    state.full?.let { full ->
        if (full.soundMissing) NoteInline(text = stringResource(Res.string.sound_file_missing))
        SoundList(state = full.sounds, onIntent = { onIntent(EditorIntent.Sound(it)) })
    }
}

/** Snooze: the length 5 / 9 / 10 / 15 min, then (full editor) the fee ladder. */
@Composable
private fun SnoozePane(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
) {
    GroupCard(title = stringResource(Res.string.editor_snooze_length)) {
        EditorForm.SNOOZE_OPTIONS.forEachIndexed { index, minutes ->
            if (index > 0) GroupDivider()
            RadioRow(
                label = stringResource(Res.string.editor_snooze_minutes, minutes),
                selected = state.form.snoozeLengthMinutes == minutes,
                onSelect = { onIntent(EditorIntent.SnoozeLengthSelected(minutes)) },
            )
        }
    }
    state.full
        ?.feeLadder
        ?.takeIf { it.size >= FEE_LADDER_STEPS }
        ?.let { ladder ->
            NoteInline(
                text = stringResource(Res.string.editor_fee_ladder, formatMoney(ladder[0]), formatMoney(ladder[1]), formatMoney(ladder[2])),
                modifier = Modifier.padding(horizontal = PpsTheme.spacing.cardPadding),
            )
        }
}

/**
 * Wake-up check: the Check picker (design preview round 3; IA: in the editor it is this sub-screen): the check types,
 * Random / All (two or more checks, with the order in All mode) and each selected check's setup (difficulty and count,
 * or its code or photos), which opens Check setup.
 */
@Composable
private fun WakeCheckPane(
    full: FullEditorSections,
    onIntent: (EditorIntent) -> Unit,
) {
    CheckPickerContent(
        state =
            CheckPickerUiState(
                checks = full.checks,
                mode = full.checkMode,
                noCheckError = full.noCheckError,
                qrCodeSaved = full.qrCodeSaved,
                houseHuntPhotos = full.houseHuntPhotos,
            ),
        onIntent = { intent ->
            when (intent) {
                is CheckPickerIntent.Toggled -> onIntent(EditorIntent.CheckToggled(intent.type, intent.selected))

                is CheckPickerIntent.ModeSelected -> onIntent(EditorIntent.CheckModeSelected(intent.mode))

                is CheckPickerIntent.SetupClicked -> onIntent(EditorIntent.CheckSetupClicked(intent.type))

                is CheckPickerIntent.Moved -> onIntent(EditorIntent.CheckMoved(intent.type, intent.up))

                // The camera banner only shows in onboarding and the standalone picker.
                CheckPickerIntent.FixCamera -> Unit
            }
        },
    )
}

/** Quiet time (the grace window): 15 to 30 s and "Vibrate during quiet time". */
@Composable
private fun QuietTimePane(
    full: FullEditorSections,
    onIntent: (EditorIntent) -> Unit,
) {
    GroupCard {
        StepSlider(
            title = stringResource(Res.string.editor_quiet_time),
            valueText = stringResource(Res.string.editor_grace_seconds, full.graceSeconds),
            value = full.graceSeconds,
            range = Alarm.GRACE_SECONDS_RANGE,
            step = 1,
            onValueChange = { onIntent(EditorIntent.GraceChanged(it)) },
            modifier = Modifier.padding(horizontal = PpsTheme.spacing.cardPadding, vertical = PpsTheme.spacing.space3),
        )
        GroupDivider()
        SwitchRow(
            label = stringResource(Res.string.editor_vibrate_quiet_time),
            checked = full.vibrateInGrace,
            onCheckedChange = { onIntent(EditorIntent.VibrateInGraceToggled(it)) },
        )
    }
    NoteInline(
        text = stringResource(Res.string.editor_quiet_time_note),
        modifier = Modifier.padding(horizontal = PpsTheme.spacing.cardPadding),
    )
}

/** Motivation: which message plays (none, random, one recording), "Record a message", and when it plays. */
@Composable
private fun MotivationPane(
    full: FullEditorSections,
    onIntent: (EditorIntent) -> Unit,
) {
    val choices = listOf(MotivationChoice.None, MotivationChoice.Random) + full.recordings.map { MotivationChoice.Recording(it) }
    GroupCard(title = stringResource(Res.string.editor_message)) {
        choices.forEachIndexed { index, choice ->
            if (index > 0) GroupDivider()
            RadioRow(
                label =
                    when (choice) {
                        MotivationChoice.None -> stringResource(Res.string.editor_message_none)
                        MotivationChoice.Random -> stringResource(Res.string.editor_message_random)
                        is MotivationChoice.Recording -> choice.name
                    },
                selected = full.motivation == choice,
                onSelect = { onIntent(EditorIntent.MotivationChosen(choice)) },
            )
        }
        GroupDivider()
        NavRow(label = stringResource(Res.string.editor_record_message), onClick = { onIntent(EditorIntent.RecordMessageClicked) })
    }
    if (full.motivation != MotivationChoice.None) {
        GroupCard(title = stringResource(Res.string.editor_when_it_plays)) {
            MotivationTiming.entries.forEachIndexed { index, timing ->
                if (index > 0) GroupDivider()
                RadioRow(
                    label =
                        stringResource(
                            if (timing == MotivationTiming.AfterImUp) Res.string.editor_after_im_up else Res.string.editor_mix_into_alarm,
                        ),
                    selected = full.motivationTiming == timing,
                    onSelect = { onIntent(EditorIntent.MotivationTimingSelected(timing)) },
                )
            }
        }
    }
}

/** The fee ladder preview shows the first three snooze prices. */
private const val FEE_LADDER_STEPS = 3
