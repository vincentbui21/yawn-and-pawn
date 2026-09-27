package com.yawnandpawn.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
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
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.components.DayChipRow
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PercentSlider
import com.yawnandpawn.app.ui.components.PpsFilledButton
import com.yawnandpawn.app.ui.components.PpsSegmentedControl
import com.yawnandpawn.app.ui.components.PpsSnackbarHost
import com.yawnandpawn.app.ui.components.PpsTextField
import com.yawnandpawn.app.ui.components.PpsTimeInput
import com.yawnandpawn.app.ui.components.PpsTopAppBar
import com.yawnandpawn.app.ui.components.SectionLabel
import com.yawnandpawn.app.ui.components.SwitchRow
import com.yawnandpawn.app.ui.components.ValueRow
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.editor_discard
import com.yawnandpawn.app.ui.resources.editor_discard_title
import com.yawnandpawn.app.ui.resources.editor_edit_title
import com.yawnandpawn.app.ui.resources.editor_gradual_volume
import com.yawnandpawn.app.ui.resources.editor_keep_editing
import com.yawnandpawn.app.ui.resources.editor_label
import com.yawnandpawn.app.ui.resources.editor_label_too_long
import com.yawnandpawn.app.ui.resources.editor_new_title
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
import com.yawnandpawn.app.ui.resources.editor_vibration
import com.yawnandpawn.app.ui.resources.editor_volume
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

/** The Alarm editor, stateless: renders [state] and reports every user action as an [EditorIntent]. */
@Composable
fun AlarmEditorScreen(
    state: EditorUiState,
    is24Hour: Boolean,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Scaffold(
        // Above the keyboard: the scrolling form keeps the focused field in view and Save stays reachable.
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
            if (!state.isLoading) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.bg)
                            .navigationBarsPadding()
                            .padding(horizontal = spacing.screenMargin, vertical = spacing.space3),
                ) {
                    PpsFilledButton(
                        text = stringResource(Res.string.editor_save),
                        onClick = { onIntent(EditorIntent.SaveClicked) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isSaving,
                    )
                }
            }
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

@Composable
private fun EditorForm(
    state: EditorUiState,
    is24Hour: Boolean,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    val spacing = PpsTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenMargin, vertical = spacing.space4),
        verticalArrangement = Arrangement.spacedBy(spacing.sectionGap),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            PpsTimeInput(
                initialTime = form.time,
                is24Hour = is24Hour,
                onTimeChange = { onIntent(EditorIntent.TimeChanged(it)) },
            )
            state.ringsTomorrowAt?.let { ringTime ->
                NoteInline(text = stringResource(Res.string.editor_rings_tomorrow, formatClockTime(ringTime, is24Hour)))
            }
        }
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
        Column {
            SectionLabel(stringResource(Res.string.editor_snooze_length))
            PpsSegmentedControl(
                options = EditorForm.SNOOZE_OPTIONS,
                selected = form.snoozeLengthMinutes,
                label = { stringResource(Res.string.editor_snooze_minutes, it) },
                onSelect = { onIntent(EditorIntent.SnoozeLengthSelected(it)) },
            )
        }
        SoundSection(form = form, onIntent = onIntent)
    }
}

/** Sound (not tappable until the sound picker, Story 1.17), volume, gradual ramp and vibration. */
@Composable
private fun SoundSection(
    form: EditorForm,
    onIntent: (EditorIntent) -> Unit,
) {
    Column {
        ValueRow(label = stringResource(Res.string.editor_sound), value = stringResource(Res.string.editor_sound_default))
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
