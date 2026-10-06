package com.yawnandpawn.app.ui.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.checksetup.CheckPreviewScreen
import com.yawnandpawn.app.ui.checksetup.CheckPreviewUiState
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.components.DayChipRow
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.InlineError
import com.yawnandpawn.app.ui.components.LocalViewfinderFeed
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
import com.yawnandpawn.app.ui.components.subScreenTransition
import com.yawnandpawn.app.ui.format.countdownText
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.home.DeleteAlarmConfirm
import com.yawnandpawn.app.ui.qr.CodeScanner
import com.yawnandpawn.app.ui.qr.QrRegistrationRoute
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
import com.yawnandpawn.app.ui.resources.editor_test_scheduled
import com.yawnandpawn.app.ui.resources.editor_vibration
import com.yawnandpawn.app.ui.resources.editor_wake_check
import com.yawnandpawn.app.ui.resources.editor_weakening_under_lock
import com.yawnandpawn.app.ui.resources.repeat_custom
import com.yawnandpawn.app.ui.resources.repeat_once
import com.yawnandpawn.app.ui.resources.repeat_weekdays
import com.yawnandpawn.app.ui.resources.sound_file_missing
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.wake.CheckContent
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The Alarm editor route: its ViewModel (scoped to the nav entry), effects, and Back interception. [onOpenFailed] runs
 * when the alarm could not be read (the editor closes and Home says so); [onOpenCopy] replaces this editor with one on
 * a new alarm prefilled from the stored alarm it gets (Duplicate). [copyOf] opens this editor that way. [scanCode] opens
 * it on QR registration of the alarm's QR/Barcode check and saves the code chosen (Home's "Re-register", Story 3.10).
 */
@Composable
fun AlarmEditorRoute(
    alarmId: String?,
    onClose: () -> Unit,
    onOpenFailed: () -> Unit,
    onOpenCopy: (String) -> Unit,
    copyOf: String? = null,
    scanCode: Boolean = false,
    viewModel: AlarmEditorViewModel = koinViewModel { parametersOf(AlarmEditorArgs(alarmId, copyOf, scanCode)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // QR registration reads the camera permission again each time the editor comes back (from "Fix", Story 3.10).
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailed = stringResource(Res.string.editor_save_failed)
    val testScheduled = stringResource(Res.string.editor_test_scheduled)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                EditorEffect.Close -> onClose()

                EditorEffect.OpenFailed -> onOpenFailed()

                is EditorEffect.OpenCopy -> onOpenCopy(effect.sourceId)

                // Its own coroutine: showSnackbar suspends until the snackbar goes, which must not hold back a Close.
                EditorEffect.ShowSaveFailed -> launch { snackbarHostState.showSnackbar(saveFailed) }

                EditorEffect.ShowTestScheduled -> launch { snackbarHostState.showSnackbar(testScheduled) }
            }
        }
    }
    // The app going to the background (or another screen covering the editor) stops a Sound preview.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(EditorIntent.Backgrounded) }
    // Back goes through the ViewModel: a sub-screen returns to the main screen, unsaved changes ask "Discard changes?".
    NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true) {
        viewModel.onIntent(EditorIntent.BackRequested)
    }
    AlarmEditorScreen(
        state = state,
        is24Hour = is24HourClock(),
        onIntent = viewModel::onIntent,
        snackbarHostState = snackbarHostState,
        resumed = resumed,
    )
}

/**
 * The Alarm editor, stateless (owner decisions 2026-09-27): a header ("New alarm" / "Edit alarm" and "Rings in ..."),
 * the time wheels in their own card, the repeat quick choices, then grouped `card-group`s whose rows show their value
 * and open a sub-screen ([EditorPane]), "Test alarm", and the floating "Cancel | Save" pill. Sub-screens slide in and
 * out. With [EditorUiState.full] it is the full editor (wake-up check, quiet time, motivation, fee ladder); without
 * it, the Story 1.8 fields, the Sound row and "Test alarm" (Story 1.18) only.
 *
 * The pill has its own bottom area under the scrolling content and sits above the keyboard (`imePadding`, with the
 * activity edge-to-edge and `adjustResize`), so Save and the focused name field stay visible while it is typed.
 */
@Composable
fun AlarmEditorScreen(
    state: EditorUiState,
    is24Hour: Boolean,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    resumed: Int = 0,
) {
    // Outside the pane animation, so returning from a sub-screen keeps the main screen where it was scrolled to.
    val mainScroll = rememberScrollState()
    Box(modifier = modifier.fillMaxSize()) {
        if (state.isLoading) {
            PpsBackground()
        } else {
            AnimatedContent(
                targetState = state.pane,
                transitionSpec = { subScreenTransition(forward = targetState.depth > initialState.depth) },
                label = "editor pane",
            ) { pane ->
                when (pane) {
                    EditorPane.Main -> {
                        EditorMain(state = state, is24Hour = is24Hour, onIntent = onIntent, scroll = mainScroll)
                    }

                    else -> {
                        EditorPaneContent(pane = pane, state = state, onIntent = onIntent, resumed = resumed)
                    }
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
    state.deleteDialogTime?.let { time ->
        DeleteAlarmConfirm(
            time = time,
            is24Hour = is24Hour,
            onConfirm = { onIntent(EditorIntent.DeleteConfirmed) },
            onKeep = { onIntent(EditorIntent.DeleteCancelled) },
        )
    }
}

/** The main editor screen: header, time card, repeat card, the two row cards, notes, "Test alarm" and the pill. */
@Composable
private fun EditorMain(
    state: EditorUiState,
    is24Hour: Boolean,
    onIntent: (EditorIntent) -> Unit,
    scroll: ScrollState,
) {
    val spacing = PpsTheme.spacing
    // The pill has its own bottom area (owner decision 2026-09-28, like the Samsung editor): the scrolling content ends
    // above it, so the last row is fully visible when scrolled to the end, and both sit above the keyboard.
    PpsBackground {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .clipToBounds()
                        .verticalScroll(scroll)
                        .padding(start = spacing.screenMargin, end = spacing.screenMargin, top = spacing.space4, bottom = spacing.space4),
                verticalArrangement = Arrangement.spacedBy(spacing.space3),
            ) {
                EditorHeader(state = state, onIntent = onIntent)
                TimeCard(state = state, is24Hour = is24Hour, onIntent = onIntent)
                RepeatCard(state = state, onIntent = onIntent)
                NameSoundCard(state = state, onIntent = onIntent)
                RowsCard(state = state, onIntent = onIntent)
                state.full?.weakeningAppliesAfter?.let { time ->
                    NoteInline(text = stringResource(Res.string.editor_weakening_under_lock, formatClockTime(time, is24Hour)))
                }
                // Under the cards in every editor (Story 1.18); Save and Cancel stay in the pill.
                PpsTextButton(
                    text = stringResource(Res.string.editor_test_alarm),
                    onClick = { onIntent(EditorIntent.TestAlarmClicked) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            SaveCancelPill(
                cancelText = stringResource(Res.string.editor_cancel),
                saveText = stringResource(Res.string.editor_save),
                onCancel = { onIntent(EditorIntent.BackRequested) },
                onSave = { onIntent(EditorIntent.SaveClicked) },
                saveEnabled = !state.isSaving,
                modifier = Modifier.padding(top = spacing.space2),
            )
        }
    }
}

/** The editor's time card. */
@Composable
private fun TimeCard(
    state: EditorUiState,
    is24Hour: Boolean,
    onIntent: (EditorIntent) -> Unit,
) = TimeWheelCard(
    time = state.form.time,
    is24Hour = is24Hour,
    onTimeChange = { onIntent(EditorIntent.TimeChanged(it)) },
    ringsTomorrowAt = state.ringsTomorrowAt,
)

/** The editor's repeat card. */
@Composable
private fun RepeatCard(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
) = RepeatChoiceCard(
    choice = state.repeatChoice,
    days = state.form.repeatDays,
    onChoose = { onIntent(EditorIntent.RepeatChosen(it)) },
    onToggleDay = { onIntent(EditorIntent.DayToggled(it)) },
)

/** Card 1: alarm name (inline field), Sound (value, opens the Sound sub-screen) and Vibration. */
@Composable
private fun NameSoundCard(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
) {
    val form = state.form
    val sound = state.sound
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
                value =
                    sound?.nameRes?.let { stringResource(it) }
                        ?: sound?.name?.ifEmpty { null }
                        ?: stringResource(Res.string.editor_sound_default),
                onClick = { onIntent(EditorIntent.PaneOpened(EditorPane.Sound)) },
            )
            GroupDivider()
            SwitchRow(
                label = stringResource(Res.string.editor_vibration),
                checked = form.vibration,
                onCheckedChange = { onIntent(EditorIntent.VibrationToggled(it)) },
            )
        }
        if (sound?.missing == true) NoteInline(text = stringResource(Res.string.sound_file_missing))
    }
}

/** Card 2: Wake-up check, Quiet time, Snooze and Motivation, each with its value, opening its sub-screen. */
@Composable
private fun RowsCard(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
) {
    val full = state.full
    val shown = full?.rows.orEmpty()
    GroupCard {
        if (full != null && EditorPane.WakeCheck in shown) {
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
        }
        // Story 3.4: the quiet time is a stored alarm setting, so its row shows in every editor.
        NavRow(
            label = stringResource(Res.string.editor_quiet_time),
            value = stringResource(Res.string.editor_grace_seconds, state.form.graceSeconds),
            onClick = { onIntent(EditorIntent.PaneOpened(EditorPane.QuietTime)) },
        )
        GroupDivider()
        NavRow(
            label = stringResource(Res.string.editor_snooze),
            value = stringResource(Res.string.editor_snooze_minutes, state.form.snoozeLengthMinutes),
            onClick = { onIntent(EditorIntent.PaneOpened(EditorPane.Snooze)) },
        )
        if (full != null && EditorPane.Motivation in shown) {
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

/**
 * A QR/Barcode "Try it" (Story 3.10) shows the live camera in its viewfinder while it scans, and hands what the camera
 * reports to the editor ([EditorIntent.TryItScanned]); any other preview shows [content] as it is. One feed while the
 * camera runs: the torch is read inside it.
 */
@Composable
private fun TryItFeed(
    tryIt: CheckPreviewUiState,
    onIntent: (EditorIntent) -> Unit,
    content: @Composable () -> Unit,
) {
    val qr = tryIt.content as? CheckContent.QrBarcode
    val camera = qr != null && qr.cameraAvailable && !tryIt.done
    val torch by rememberUpdatedState(qr?.torchOn == true)
    val events by rememberUpdatedState(onIntent)
    val feed: (@Composable BoxScope.() -> Unit)? =
        remember(camera) {
            if (camera) {
                { koinInject<CodeScanner>().Feed(torchOn = torch, onEvent = { events(EditorIntent.TryItScanned(it)) }) }
            } else {
                null
            }
        }
    CompositionLocalProvider(LocalViewfinderFeed provides feed) { content() }
}

/** A pane other than the main screen: "Try it", QR registration, or one of the sub-screens. */
@Composable
private fun EditorPaneContent(
    pane: EditorPane,
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
    resumed: Int,
) {
    when (pane) {
        // "Try it" (Story 3.6): the approved Sunrise preview, full screen, over the editor's own state.
        EditorPane.TryIt -> {
            // The last preview, so it slides out with its content after Done or Back (Story 3.6 review).
            rememberLastNonNull(state.tryIt)?.let { tryIt ->
                TryItFeed(tryIt, onIntent) {
                    CheckPreviewScreen(
                        state = tryIt,
                        onIntent = { onIntent(EditorIntent.TryIt(it)) },
                        onClose = { onIntent(EditorIntent.BackRequested) },
                    )
                }
            }
        }

        // QR registration (Story 3.10): "Use this code" puts the code in the form.
        EditorPane.ScanCode -> {
            QrRegistrationRoute(
                onCodeChosen = { onIntent(EditorIntent.CodeRegistered(it)) },
                onBack = { onIntent(EditorIntent.BackRequested) },
                resumed = resumed,
            )
        }

        else -> {
            EditorSubScreen(pane = pane, state = state, onIntent = onIntent)
        }
    }
}
