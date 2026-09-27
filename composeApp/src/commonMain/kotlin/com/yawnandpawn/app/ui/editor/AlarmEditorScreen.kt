package com.yawnandpawn.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.checks.icon
import com.yawnandpawn.app.ui.components.CheckChipView
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.components.DayChipRow
import com.yawnandpawn.app.ui.components.InlineError
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PercentSlider
import com.yawnandpawn.app.ui.components.PpsFilledButton
import com.yawnandpawn.app.ui.components.PpsSegmentedControl
import com.yawnandpawn.app.ui.components.PpsSnackbarHost
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.PpsTextField
import com.yawnandpawn.app.ui.components.PpsTopAppBar
import com.yawnandpawn.app.ui.components.PpsWheelTimePicker
import com.yawnandpawn.app.ui.components.SectionLabel
import com.yawnandpawn.app.ui.components.StepSlider
import com.yawnandpawn.app.ui.components.SwitchRow
import com.yawnandpawn.app.ui.components.ValueRow
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_after_im_up
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.editor_check_chip
import com.yawnandpawn.app.ui.resources.editor_checks
import com.yawnandpawn.app.ui.resources.editor_discard
import com.yawnandpawn.app.ui.resources.editor_discard_title
import com.yawnandpawn.app.ui.resources.editor_edit_title
import com.yawnandpawn.app.ui.resources.editor_fee_ladder
import com.yawnandpawn.app.ui.resources.editor_grace_seconds
import com.yawnandpawn.app.ui.resources.editor_grace_window
import com.yawnandpawn.app.ui.resources.editor_gradual_volume
import com.yawnandpawn.app.ui.resources.editor_keep_editing
import com.yawnandpawn.app.ui.resources.editor_label
import com.yawnandpawn.app.ui.resources.editor_label_too_long
import com.yawnandpawn.app.ui.resources.editor_message
import com.yawnandpawn.app.ui.resources.editor_message_none
import com.yawnandpawn.app.ui.resources.editor_message_random
import com.yawnandpawn.app.ui.resources.editor_mix_into_alarm
import com.yawnandpawn.app.ui.resources.editor_mode_all
import com.yawnandpawn.app.ui.resources.editor_mode_random
import com.yawnandpawn.app.ui.resources.editor_motivation
import com.yawnandpawn.app.ui.resources.editor_new_title
import com.yawnandpawn.app.ui.resources.editor_no_check
import com.yawnandpawn.app.ui.resources.editor_percent
import com.yawnandpawn.app.ui.resources.editor_repeat
import com.yawnandpawn.app.ui.resources.editor_rings_tomorrow
import com.yawnandpawn.app.ui.resources.editor_save
import com.yawnandpawn.app.ui.resources.editor_save_failed
import com.yawnandpawn.app.ui.resources.editor_snooze_length
import com.yawnandpawn.app.ui.resources.editor_snooze_minutes
import com.yawnandpawn.app.ui.resources.editor_sound
import com.yawnandpawn.app.ui.resources.editor_sound_default
import com.yawnandpawn.app.ui.resources.editor_starting_volume
import com.yawnandpawn.app.ui.resources.editor_test_alarm
import com.yawnandpawn.app.ui.resources.editor_vibrate_in_grace
import com.yawnandpawn.app.ui.resources.editor_vibration
import com.yawnandpawn.app.ui.resources.editor_volume
import com.yawnandpawn.app.ui.resources.editor_weakening_under_lock
import com.yawnandpawn.app.ui.resources.sound_file_missing
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The Alarm editor route: its ViewModel (scoped to the nav entry), effects, and Back interception. */
@Composable
fun AlarmEditorRoute(
    alarmId: String?,
    onClose: () -> Unit,
    viewModel: AlarmEditorViewModel = koinViewModel { parametersOf(AlarmEditorArgs(alarmId)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailed = stringResource(Res.string.editor_save_failed)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                EditorEffect.Close -> onClose()

                // Its own coroutine: showSnackbar suspends until the snackbar goes, which must not hold back a Close.
                EditorEffect.ShowSaveFailed -> launch { snackbarHostState.showSnackbar(saveFailed) }
            }
        }
    }
    // Back goes through the ViewModel, so unsaved changes ask "Discard changes?" first.
    NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true) {
        viewModel.onIntent(EditorIntent.BackRequested)
    }
    AlarmEditorScreen(
        state = state,
        is24Hour = is24HourClock(),
        onIntent = viewModel::onIntent,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * The Alarm editor, stateless: renders [state] and reports every user action as an [EditorIntent]. With
 * [EditorUiState.full] it is the full editor (checks, grace window, fee ladder, sound picker, motivation and a
 * "Test alarm" / "Save" bottom bar); without it, the Story 1.8 fields only.
 *
 * The whole screen sits above the keyboard (`imePadding`, with the activity edge-to-edge and `adjustResize`), so the
 * bottom bar with Save stays visible while the label is being typed.
 */
@Composable
fun AlarmEditorScreen(
    state: EditorUiState,
    is24Hour: Boolean,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val colors = PpsTheme.colors
    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        containerColor = colors.bg,
        contentColor = colors.text,
        topBar = {
            PpsTopAppBar(
                title = stringResource(if (state.isNew) Res.string.editor_new_title else Res.string.editor_edit_title),
                backContentDescription = stringResource(Res.string.editor_back),
                onBack = { onIntent(EditorIntent.BackRequested) },
            )
        },
        bottomBar = {
            if (!state.isLoading) EditorBottomBar(state = state, is24Hour = is24Hour, onIntent = onIntent)
        },
        snackbarHost = { PpsSnackbarHost(snackbarHostState) },
    ) { padding ->
        if (!state.isLoading) {
            EditorForm(
                state = state,
                is24Hour = is24Hour,
                onIntent = onIntent,
                modifier = Modifier.padding(padding),
            )
        }
    }
    if (state.showDiscardDialog) {
        ConfirmDialog(
            title = stringResource(Res.string.editor_discard_title),
            confirmText = stringResource(Res.string.editor_discard),
            safeText = stringResource(Res.string.editor_keep_editing),
            onConfirm = { onIntent(EditorIntent.DiscardConfirmed) },
            onSafe = { onIntent(EditorIntent.KeepEditing) },
            destructive = true,
        )
    }
}

/** Save (and, in the full editor, "Test alarm" and the weakening-under-lock note). */
@Composable
private fun EditorBottomBar(
    state: EditorUiState,
    is24Hour: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    val spacing = PpsTheme.spacing
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(PpsTheme.colors.bg)
                .navigationBarsPadding()
                .padding(horizontal = spacing.screenMargin, vertical = spacing.space3),
        verticalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        state.full?.weakeningAppliesAfter?.let { time ->
            NoteInline(text = stringResource(Res.string.editor_weakening_under_lock, formatClockTime(time, is24Hour)))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
            if (state.full != null) {
                PpsTextButton(
                    text = stringResource(Res.string.editor_test_alarm),
                    onClick = { onIntent(EditorIntent.TestAlarmClicked) },
                )
            }
            PpsFilledButton(
                text = stringResource(Res.string.editor_save),
                onClick = { onIntent(EditorIntent.SaveClicked) },
                modifier = Modifier.weight(1f),
                enabled = !state.isSaving,
            )
        }
    }
}

@Composable
private fun EditorForm(
    state: EditorUiState,
    is24Hour: Boolean,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    val full = state.full
    val spacing = PpsTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenMargin, vertical = spacing.space4),
        verticalArrangement = Arrangement.spacedBy(spacing.sectionGap),
    ) {
        TimeSection(state = state, is24Hour = is24Hour, onIntent = onIntent)
        Column {
            SectionLabel(stringResource(Res.string.editor_repeat))
            DayChipRow(selectedDays = form.repeatDays, onToggle = { onIntent(EditorIntent.DayToggled(it)) })
        }
        PpsTextField(
            value = form.label,
            onValueChange = { onIntent(EditorIntent.LabelChanged(it)) },
            label = stringResource(Res.string.editor_label),
            errorText = if (state.fieldError == AlarmField.Label) stringResource(Res.string.editor_label_too_long) else null,
        )
        if (full != null) {
            ChecksSection(full = full, onIntent = onIntent)
            GraceSection(full = full, onIntent = onIntent)
        }
        Column {
            SectionLabel(stringResource(Res.string.editor_snooze_length))
            PpsSegmentedControl(
                options = EditorForm.SNOOZE_OPTIONS,
                selected = form.snoozeLengthMinutes,
                label = { stringResource(Res.string.editor_snooze_minutes, it) },
                onSelect = { onIntent(EditorIntent.SnoozeLengthSelected(it)) },
            )
            full?.feeLadder?.takeIf { it.size >= FEE_LADDER_STEPS }?.let { ladder ->
                Text(
                    text =
                        stringResource(
                            Res.string.editor_fee_ladder,
                            formatMoney(ladder[0]),
                            formatMoney(ladder[1]),
                            formatMoney(ladder[2]),
                        ),
                    modifier = Modifier.padding(top = spacing.space2),
                    style = PpsTheme.typography.caption,
                    color = PpsTheme.colors.textSecondary,
                )
            }
        }
        SoundSection(form = form, full = full, onIntent = onIntent)
        if (full != null) MotivationSection(full = full, onIntent = onIntent)
    }
}

/** The time wheels and, for a one-time alarm whose time has passed today, "Rings tomorrow at {time}." */
@Composable
private fun TimeSection(
    state: EditorUiState,
    is24Hour: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PpsWheelTimePicker(
            time = state.form.time,
            is24Hour = is24Hour,
            onTimeChange = { onIntent(EditorIntent.TimeChanged(it)) },
        )
        state.ringsTomorrowAt?.let { ringTime ->
            NoteInline(
                text = stringResource(Res.string.editor_rings_tomorrow, formatClockTime(ringTime, is24Hour)),
                modifier = Modifier.padding(top = PpsTheme.spacing.space2),
            )
        }
    }
}

/** Checks: the selected `chip-check`s, the Random / All mode and the "Pick at least one check." error. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChecksSection(
    full: FullEditorSections,
    onIntent: (EditorIntent) -> Unit,
) {
    val spacing = PpsTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
        SectionLabel(stringResource(Res.string.editor_checks))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.space2), verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
            full.checks.forEach { chip ->
                CheckChipView(
                    text = stringResource(Res.string.editor_check_chip, chip.type.displayName(), chip.difficulty.displayName()),
                    icon = chip.type.icon,
                    onClick = { onIntent(EditorIntent.CheckChipClicked(chip.type)) },
                )
            }
        }
        if (full.noCheckError) InlineError(text = stringResource(Res.string.editor_no_check))
        if (full.checks.size > 1) {
            PpsSegmentedControl(
                options = CheckMode.entries,
                selected = full.checkMode,
                label = { mode ->
                    stringResource(if (mode == CheckMode.Random) Res.string.editor_mode_random else Res.string.editor_mode_all)
                },
                onSelect = { onIntent(EditorIntent.CheckModeSelected(it)) },
            )
        }
    }
}

/** Grace window slider (15 to 30 s) and "Vibrate in grace window". */
@Composable
private fun GraceSection(
    full: FullEditorSections,
    onIntent: (EditorIntent) -> Unit,
) {
    Column {
        StepSlider(
            title = stringResource(Res.string.editor_grace_window),
            valueText = stringResource(Res.string.editor_grace_seconds, full.graceSeconds),
            value = full.graceSeconds,
            range = Alarm.GRACE_SECONDS_RANGE,
            step = 1,
            onValueChange = { onIntent(EditorIntent.GraceChanged(it)) },
        )
        SwitchRow(
            label = stringResource(Res.string.editor_vibrate_in_grace),
            checked = full.vibrateInGrace,
            onCheckedChange = { onIntent(EditorIntent.VibrateInGraceToggled(it)) },
        )
    }
}

/** Sound (a picker row in the full editor, read-only otherwise), volume, gradual ramp and vibration. */
@Composable
private fun SoundSection(
    form: EditorForm,
    full: FullEditorSections?,
    onIntent: (EditorIntent) -> Unit,
) {
    Column {
        if (full != null) {
            NavRow(
                label = stringResource(Res.string.editor_sound),
                value = full.soundName.ifEmpty { stringResource(Res.string.editor_sound_default) },
                onClick = { onIntent(EditorIntent.SoundClicked) },
            )
            if (full.soundMissing) NoteInline(text = stringResource(Res.string.sound_file_missing))
        } else {
            ValueRow(label = stringResource(Res.string.editor_sound), value = stringResource(Res.string.editor_sound_default))
        }
        PercentSlider(
            title = stringResource(Res.string.editor_volume),
            valueText = stringResource(Res.string.editor_percent, form.volumePercent),
            percent = form.volumePercent,
            onPercentChange = { onIntent(EditorIntent.VolumeChanged(it)) },
        )
        SwitchRow(
            label = stringResource(Res.string.editor_gradual_volume),
            checked = form.gradualVolume,
            onCheckedChange = { onIntent(EditorIntent.GradualVolumeToggled(it)) },
        )
        if (form.gradualVolume) {
            PercentSlider(
                title = stringResource(Res.string.editor_starting_volume),
                valueText = stringResource(Res.string.editor_percent, form.rampStartPercent),
                percent = form.rampStartPercent,
                onPercentChange = { onIntent(EditorIntent.RampStartChanged(it)) },
            )
        }
        SwitchRow(
            label = stringResource(Res.string.editor_vibration),
            checked = form.vibration,
            onCheckedChange = { onIntent(EditorIntent.VibrationToggled(it)) },
        )
    }
}

/** Motivation: the message row (opens Recordings) and, with a message chosen, when it plays. */
@Composable
private fun MotivationSection(
    full: FullEditorSections,
    onIntent: (EditorIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space2)) {
        SectionLabel(stringResource(Res.string.editor_motivation))
        val message =
            when (val choice = full.motivation) {
                MotivationChoice.None -> stringResource(Res.string.editor_message_none)
                MotivationChoice.Random -> stringResource(Res.string.editor_message_random)
                is MotivationChoice.Recording -> choice.name
            }
        NavRow(label = stringResource(Res.string.editor_message), value = message, onClick = { onIntent(EditorIntent.MotivationClicked) })
        if (full.motivation != MotivationChoice.None) {
            PpsSegmentedControl(
                options = MotivationTiming.entries,
                selected = full.motivationTiming,
                label = { timing ->
                    stringResource(
                        if (timing == MotivationTiming.AfterImUp) Res.string.editor_after_im_up else Res.string.editor_mix_into_alarm,
                    )
                },
                onSelect = { onIntent(EditorIntent.MotivationTimingSelected(it)) },
            )
        }
    }
}

/** The fee ladder preview shows the first three snooze prices. */
private const val FEE_LADDER_STEPS = 3
