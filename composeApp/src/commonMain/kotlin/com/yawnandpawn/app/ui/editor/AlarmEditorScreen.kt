package com.yawnandpawn.app.ui.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.components.DayChipRow
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.InlineError
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PILL_CLEARANCE
import com.yawnandpawn.app.ui.components.PpsBackground
import com.yawnandpawn.app.ui.components.PpsSegmentedControl
import com.yawnandpawn.app.ui.components.PpsSnackbarHost
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.PpsWheelTimePicker
import com.yawnandpawn.app.ui.components.SaveCancelPill
import com.yawnandpawn.app.ui.components.SwitchRow
import com.yawnandpawn.app.ui.components.TextFieldRow
import com.yawnandpawn.app.ui.components.glassSource
import com.yawnandpawn.app.ui.components.rememberGlassBackdrop
import com.yawnandpawn.app.ui.format.countdownText
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_after_im_up
import com.yawnandpawn.app.ui.resources.editor_alarm_name
import com.yawnandpawn.app.ui.resources.editor_cancel
import com.yawnandpawn.app.ui.resources.editor_check_chip
import com.yawnandpawn.app.ui.resources.editor_discard
import com.yawnandpawn.app.ui.resources.editor_discard_title
import com.yawnandpawn.app.ui.resources.editor_edit_title
import com.yawnandpawn.app.ui.resources.editor_grace_seconds
import com.yawnandpawn.app.ui.resources.editor_keep_editing
import com.yawnandpawn.app.ui.resources.editor_label_too_long
import com.yawnandpawn.app.ui.resources.editor_message_none
import com.yawnandpawn.app.ui.resources.editor_message_random
import com.yawnandpawn.app.ui.resources.editor_mix_into_alarm
import com.yawnandpawn.app.ui.resources.editor_mode_all
import com.yawnandpawn.app.ui.resources.editor_mode_random
import com.yawnandpawn.app.ui.resources.editor_motivation
import com.yawnandpawn.app.ui.resources.editor_new_title
import com.yawnandpawn.app.ui.resources.editor_no_check
import com.yawnandpawn.app.ui.resources.editor_quiet_time
import com.yawnandpawn.app.ui.resources.editor_repeat
import com.yawnandpawn.app.ui.resources.editor_rings_tomorrow
import com.yawnandpawn.app.ui.resources.editor_save
import com.yawnandpawn.app.ui.resources.editor_save_failed
import com.yawnandpawn.app.ui.resources.editor_snooze
import com.yawnandpawn.app.ui.resources.editor_snooze_minutes
import com.yawnandpawn.app.ui.resources.editor_sound
import com.yawnandpawn.app.ui.resources.editor_sound_default
import com.yawnandpawn.app.ui.resources.editor_test_alarm
import com.yawnandpawn.app.ui.resources.editor_vibration
import com.yawnandpawn.app.ui.resources.editor_wake_check
import com.yawnandpawn.app.ui.resources.editor_weakening_under_lock
import com.yawnandpawn.app.ui.resources.repeat_custom
import com.yawnandpawn.app.ui.resources.repeat_once
import com.yawnandpawn.app.ui.resources.repeat_weekdays
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
    // Back goes through the ViewModel: a sub-screen returns to the main screen, unsaved changes ask "Discard changes?".
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
 * The Alarm editor, stateless (owner decisions 2026-09-27): a header ("New alarm" / "Edit alarm" and "Rings in ..."),
 * the time wheels in their own card, the repeat quick choices, then grouped `card-group`s whose rows show their value
 * and open a sub-screen ([EditorPane]), "Test alarm", and the floating "Cancel | Save" pill. Sub-screens slide in and
 * out. With [EditorUiState.full] it is the full editor (wake-up check, quiet time, motivation, sound list, fee ladder,
 * "Test alarm"); without it, the Story 1.8 fields only.
 *
 * The pill sits above the keyboard (`imePadding`, with the activity edge-to-edge and `adjustResize`), so Save stays
 * visible while the alarm name is typed.
 */
@Composable
fun AlarmEditorScreen(
    state: EditorUiState,
    is24Hour: Boolean,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    // Outside the pane animation, so returning from a sub-screen keeps the main screen where it was scrolled to.
    val mainScroll = rememberScrollState()
    Box(modifier = modifier.fillMaxSize()) {
        if (state.isLoading) {
            PpsBackground()
        } else {
            AnimatedContent(
                targetState = state.pane,
                transitionSpec = { paneTransition(forward = targetState != EditorPane.Main) },
                label = "editor pane",
            ) { pane ->
                when (pane) {
                    EditorPane.Main -> EditorMain(state = state, is24Hour = is24Hour, onIntent = onIntent, scroll = mainScroll)
                    else -> EditorSubScreen(pane = pane, state = state, onIntent = onIntent)
                }
            }
        }
        PpsSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = PILL_CLEARANCE),
        )
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

/** EXPERIENCE.md standard transition: 250 ms, Material emphasized easing; a sub-screen slides in from the end. */
private fun paneTransition(forward: Boolean): ContentTransform {
    val spec = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = TRANSITION_MILLIS, easing = EmphasizedEasing)
    val fade = tween<Float>(durationMillis = TRANSITION_MILLIS, easing = EmphasizedEasing)
    return if (forward) {
        (slideInHorizontally(spec) { it } + fadeIn(fade)) togetherWith (slideOutHorizontally(spec) { -it / PARALLAX } + fadeOut(fade))
    } else {
        (slideInHorizontally(spec) { -it / PARALLAX } + fadeIn(fade)) togetherWith (slideOutHorizontally(spec) { it } + fadeOut(fade))
    }
}

/** The main editor screen: header, time card, repeat card, the two row cards, notes, "Test alarm" and the pill. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorMain(
    state: EditorUiState,
    is24Hour: Boolean,
    onIntent: (EditorIntent) -> Unit,
    scroll: ScrollState,
) {
    val spacing = PpsTheme.spacing
    val backdrop = rememberGlassBackdrop()
    Box(modifier = Modifier.fillMaxSize()) {
        PpsBackground(modifier = Modifier.glassSource(backdrop)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .imePadding()
                        // With the keyboard up, the viewport ends above the pill, so the focused name field is never
                        // hidden behind it; otherwise content scrolls under the pill (which blurs it).
                        .padding(bottom = if (WindowInsets.isImeVisible) PILL_CLEARANCE else 0.dp)
                        .verticalScroll(scroll)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .navigationBarsPadding()
                        .padding(start = spacing.screenMargin, end = spacing.screenMargin, top = spacing.space4, bottom = PILL_CLEARANCE),
                verticalArrangement = Arrangement.spacedBy(spacing.space3),
            ) {
                EditorHeader(state)
                TimeCard(state = state, is24Hour = is24Hour, onIntent = onIntent)
                RepeatCard(state = state, onIntent = onIntent)
                NameSoundCard(state = state, onIntent = onIntent)
                RowsCard(state = state, onIntent = onIntent)
                state.full?.weakeningAppliesAfter?.let { time ->
                    NoteInline(text = stringResource(Res.string.editor_weakening_under_lock, formatClockTime(time, is24Hour)))
                }
                if (state.full != null) {
                    PpsTextButton(
                        text = stringResource(Res.string.editor_test_alarm),
                        onClick = { onIntent(EditorIntent.TestAlarmClicked) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
        // The status bar area keeps the top of the background, so scrolled cards never run under the clock and icons.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(PpsTheme.colors.gradientTop),
        )
        SaveCancelPill(
            cancelText = stringResource(Res.string.editor_cancel),
            saveText = stringResource(Res.string.editor_save),
            onCancel = { onIntent(EditorIntent.BackRequested) },
            onSave = { onIntent(EditorIntent.SaveClicked) },
            saveEnabled = !state.isSaving,
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** "New alarm" / "Edit alarm" in `headline` with "Rings in ..." under it. */
@Composable
private fun EditorHeader(state: EditorUiState) {
    Column(modifier = Modifier.padding(bottom = PpsTheme.spacing.space2)) {
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

/** The time wheels in their own card and, for a one-time alarm whose time has passed today, "Rings tomorrow at {time}." */
@Composable
private fun TimeCard(
    state: EditorUiState,
    is24Hour: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    val spacing = PpsTheme.spacing
    GroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.space2, vertical = spacing.space3),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PpsWheelTimePicker(
                time = state.form.time,
                is24Hour = is24Hour,
                onTimeChange = { onIntent(EditorIntent.TimeChanged(it)) },
            )
            state.ringsTomorrowAt?.let { ringTime ->
                NoteInline(
                    text = stringResource(Res.string.editor_rings_tomorrow, formatClockTime(ringTime, is24Hour)),
                    modifier = Modifier.padding(top = spacing.space2),
                )
            }
        }
    }
}

/** "Once" · "Weekdays" · "Custom"; Custom expands to the seven day chips. */
@Composable
private fun RepeatCard(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
) {
    val spacing = PpsTheme.spacing
    GroupCard(title = stringResource(Res.string.editor_repeat)) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.space3)) {
            PpsSegmentedControl(
                options = RepeatChoice.entries,
                selected = state.repeatChoice,
                label = { choice ->
                    stringResource(
                        when (choice) {
                            RepeatChoice.Once -> Res.string.repeat_once
                            RepeatChoice.Weekdays -> Res.string.repeat_weekdays
                            RepeatChoice.Custom -> Res.string.repeat_custom
                        },
                    )
                },
                onSelect = { onIntent(EditorIntent.RepeatChosen(it)) },
            )
            AnimatedVisibility(
                visible = state.repeatChoice == RepeatChoice.Custom,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                DayChipRow(
                    selectedDays = state.form.repeatDays,
                    onToggle = { onIntent(EditorIntent.DayToggled(it)) },
                    modifier = Modifier.padding(top = spacing.space3),
                )
            }
        }
    }
}

/** Card 1: alarm name (inline field), Sound (value, opens the Sound sub-screen) and Vibration. */
@Composable
private fun NameSoundCard(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
) {
    val form = state.form
    val full = state.full
    Column(verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space2)) {
        GroupCard {
            TextFieldRow(
                label = stringResource(Res.string.editor_alarm_name),
                value = form.label,
                onValueChange = { onIntent(EditorIntent.LabelChanged(it)) },
                errorText = if (state.fieldError == AlarmField.Label) stringResource(Res.string.editor_label_too_long) else null,
            )
            GroupDivider()
            NavRow(
                label = stringResource(Res.string.editor_sound),
                value = full?.soundName?.ifEmpty { null } ?: stringResource(Res.string.editor_sound_default),
                onClick = { onIntent(EditorIntent.PaneOpened(EditorPane.Sound)) },
            )
            GroupDivider()
            SwitchRow(
                label = stringResource(Res.string.editor_vibration),
                checked = form.vibration,
                onCheckedChange = { onIntent(EditorIntent.VibrationToggled(it)) },
            )
        }
        if (full?.soundMissing == true) NoteInline(text = stringResource(Res.string.sound_file_missing))
    }
}

/** Card 2: Wake-up check, Quiet time, Snooze and Motivation, each with its value, opening its sub-screen. */
@Composable
private fun RowsCard(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
) {
    val full = state.full
    GroupCard {
        if (full != null) {
            NavRow(
                label = stringResource(Res.string.editor_wake_check),
                value = checkSummary(full),
                onClick = { onIntent(EditorIntent.PaneOpened(EditorPane.WakeCheck)) },
            )
            if (full.noCheckError) {
                InlineError(
                    text = stringResource(Res.string.editor_no_check),
                    modifier = Modifier.padding(start = PpsTheme.spacing.cardPadding, bottom = PpsTheme.spacing.space3),
                )
            }
            GroupDivider()
            NavRow(
                label = stringResource(Res.string.editor_quiet_time),
                value = stringResource(Res.string.editor_grace_seconds, full.graceSeconds),
                onClick = { onIntent(EditorIntent.PaneOpened(EditorPane.QuietTime)) },
            )
            GroupDivider()
        }
        NavRow(
            label = stringResource(Res.string.editor_snooze),
            value = stringResource(Res.string.editor_snooze_minutes, state.form.snoozeLengthMinutes),
            onClick = { onIntent(EditorIntent.PaneOpened(EditorPane.Snooze)) },
        )
        if (full != null) {
            GroupDivider()
            NavRow(
                label = stringResource(Res.string.editor_motivation),
                value = motivationSummary(full),
                onClick = { onIntent(EditorIntent.PaneOpened(EditorPane.Motivation)) },
            )
        }
    }
}

/** "Math, QR/Barcode · Random", "Math", or "None". */
@Composable
private fun checkSummary(full: FullEditorSections): String {
    val names = full.checks.map { it.type.displayName() }.joinToString(", ")
    val mode = stringResource(if (full.checkMode == CheckMode.Random) Res.string.editor_mode_random else Res.string.editor_mode_all)
    return when (full.checks.size) {
        0 -> stringResource(Res.string.editor_message_none)
        1 -> names
        else -> stringResource(Res.string.editor_check_chip, names, mode)
    }
}

/** "None", or the message and when it plays ("Message 1 · After I'm up"). */
@Composable
internal fun motivationSummary(full: FullEditorSections): String {
    val message =
        when (val choice = full.motivation) {
            MotivationChoice.None -> return stringResource(Res.string.editor_message_none)
            MotivationChoice.Random -> stringResource(Res.string.editor_message_random)
            is MotivationChoice.Recording -> choice.name
        }
    val timing =
        stringResource(
            if (full.motivationTiming == MotivationTiming.AfterImUp) Res.string.editor_after_im_up else Res.string.editor_mix_into_alarm,
        )
    return stringResource(Res.string.editor_check_chip, message, timing)
}

private const val TRANSITION_MILLIS = 250

/** The outgoing screen moves a quarter of the way (a gentle parallax), the incoming one the whole width. */
private const val PARALLAX = 4

/** Material 3 emphasized easing (EXPERIENCE.md standard transition). */
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
