package com.yawnandpawn.app.debug.preview

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.daydetail.DayDetailScreen
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.FullEditorSections
import com.yawnandpawn.app.ui.editor.RepeatChoice
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.home.AlarmCard
import com.yawnandpawn.app.ui.home.HomeIntent
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.onboarding.OnboardingIntent
import com.yawnandpawn.app.ui.onboarding.OnboardingScreen
import com.yawnandpawn.app.ui.onboarding.OnboardingStep
import com.yawnandpawn.app.ui.onboarding.OnboardingUiState
import com.yawnandpawn.app.ui.payments.PaymentsIntent
import com.yawnandpawn.app.ui.payments.PaymentsScreen
import com.yawnandpawn.app.ui.payments.ProblemWithChargeScreen
import com.yawnandpawn.app.ui.progress.ProgressIntent
import com.yawnandpawn.app.ui.progress.ProgressScreen
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryScreen
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ReliabilityIntent
import com.yawnandpawn.app.ui.reliability.ReliabilityScreen
import com.yawnandpawn.app.ui.settings.SettingsIntent
import com.yawnandpawn.app.ui.settings.SettingsPane
import com.yawnandpawn.app.ui.settings.SettingsScreen
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.sound.SoundPickerIntent
import com.yawnandpawn.app.ui.you.YouIntent
import com.yawnandpawn.app.ui.you.YouScreen
import kotlinx.datetime.LocalDate

/** A pushed screen of the tap-through (the shell with its tabs, or onboarding, is the root). */
internal sealed interface Pushed {
    data class Editor(
        val alarmId: String?,
    ) : Pushed

    data object Wake : Pushed

    data class DayDetail(
        val date: LocalDate,
    ) : Pushed

    data object PurchaseHistory : Pushed

    data object Reliability : Pushed

    data object Payments : Pushed

    data object ProblemWithCharge : Pushed
}

/**
 * The tap-through's fake app state and what each tap does to it. Nothing is stored, scheduled, played or charged.
 * [start] opens onboarding first, or the editor on its Wake-up check or Motivation sub-screen (round 3).
 */
internal class TapThroughState(
    startInSession: Boolean,
    startTab: AppTab,
    start: FlowStart,
) {
    var tab by mutableStateOf(startTab)
    val stack = mutableStateListOf<Pushed>()
    var home by mutableStateOf(PreviewSamples.homeList.copy(sessionInProgress = startInSession))
    var editor by mutableStateOf(EditorUiState())
    val wake = PreviewWakeFlow()
    var progress by mutableStateOf(PreviewProgressSamples.progress)
    var settings by mutableStateOf(PreviewProgressSamples.settings)
    var reliability by mutableStateOf(PreviewProgressSamples.reliabilityMissing)
    var you by mutableStateOf(PreviewProgressSamples.you)

    /** Onboarding while it runs (the root instead of the tabs); `null` once it is done. */
    var onboarding by mutableStateOf<OnboardingUiState?>(null)

    // Check setup, "Try it", QR and House Hunt registration and Recordings change the checks of whoever opened them.
    val setup =
        SetupFlowState(
            push = { stack.add(it) },
            pop = { pop() },
            updateChips = { change ->
                val current = onboarding
                if (current != null) {
                    onboarding = current.copy(checks = current.checks.copy(checks = change(current.checks.checks)))
                } else {
                    editor = editor.copy(full = editor.full?.let { it.copy(checks = change(it.checks)) })
                }
            },
            onMessages = { numbers -> editor = editor.copy(full = editor.full?.copy(recordings = numbers.map { "Message $it" })) },
            registered = start != FlowStart.Onboarding,
        )

    init {
        when (start) {
            FlowStart.Tabs -> {
                Unit
            }

            FlowStart.Onboarding -> {
                onboarding = PreviewSetupSamples.onboarding.copy(checks = PreviewSetupSamples.onboarding.checks.copy(qrCodeSaved = false))
            }

            FlowStart.CheckPicker -> {
                editor = PreviewSamples.editorEdit.copy(pane = EditorPane.WakeCheck)
                stack.add(Pushed.Editor(LOCKED_ALARM_ID))
            }

            FlowStart.Recordings -> {
                editor = PreviewSamples.editorEdit.copy(pane = EditorPane.Motivation)
                stack.add(Pushed.Editor(LOCKED_ALARM_ID))
                stack.add(SetupPushed.Recordings)
            }
        }
    }

    private fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    fun openWake(test: Boolean) {
        wake.start(test)
        stack.add(Pushed.Wake)
    }

    /**
     * Back: an editor sub-screen returns to the editor, a pushed screen pops; `false` at the root and on wake screens,
     * which in the preview go back to the menu (a real alarm ignores Back; design preview feedback item 6).
     */
    fun back(): Boolean {
        val top = stack.lastOrNull()
        return when (top) {
            null -> {
                backAtRoot()
            }

            Pushed.Wake -> {
                false
            }

            else -> {
                backFrom(top)
                true
            }
        }
    }

    fun push(screen: Pushed) {
        stack.add(screen)
    }

    fun onHome(intent: HomeIntent) {
        when (intent) {
            HomeIntent.AddAlarm -> {
                editor = PreviewSamples.editorNew
                stack.add(Pushed.Editor(null))
            }

            is HomeIntent.EditAlarm -> {
                editor = editorFor(home, intent.id)
                stack.add(Pushed.Editor(intent.id))
            }

            HomeIntent.BackToAlarm -> {
                openWake(test = false)
            }

            HomeIntent.FixSettings -> {
                push(Pushed.Reliability)
            }

            else -> {
                home = reduceHome(home, intent)
            }
        }
    }

    fun onEditor(
        alarmId: String?,
        intent: EditorIntent,
    ) {
        when (intent) {
            EditorIntent.SaveClicked -> {
                home = home.saved(alarmId, editor)
                pop()
            }

            EditorIntent.BackRequested -> {
                if (editor.pane != EditorPane.Main) editor = editor.copy(pane = EditorPane.Main) else pop()
            }

            EditorIntent.DiscardConfirmed -> {
                pop()
            }

            is EditorIntent.Sound -> {
                editor = editor.withSound(intent.intent)
            }

            EditorIntent.TestAlarmClicked -> {
                openWake(test = true)
            }

            is EditorIntent.CheckSetupClicked -> {
                editor.full
                    ?.checks
                    ?.firstOrNull { it.type == intent.type }
                    ?.let(setup::openCheckSetup)
            }

            EditorIntent.RecordMessageClicked -> {
                setup.openRecordings()
            }

            else -> {
                editor = reduceEditor(editor, intent)
            }
        }
    }

    fun onWakeFinished() {
        home = home.copy(sessionInProgress = false)
        stack.clear()
        // The test alarm at the end of onboarding: "Done" lands on Home with the new alarm.
        if (onboarding != null) finishOnboarding(testSkipped = false)
    }

    fun onProgress(intent: ProgressIntent) {
        when (intent) {
            is ProgressIntent.DayTapped -> {
                progress = progress.copy(selection = null)
                push(Pushed.DayDetail(intent.date))
            }

            ProgressIntent.PurchaseHistoryClicked -> {
                push(Pushed.PurchaseHistory)
            }

            else -> {
                progress = reduceProgress(progress, intent)
            }
        }
    }

    fun onSettings(intent: SettingsIntent) {
        when (intent) {
            SettingsIntent.ReliabilityClicked, SettingsIntent.FixSettings -> push(Pushed.Reliability)
            SettingsIntent.BackToAlarm -> openWake(test = false)
            else -> settings = reduceSettings(settings, intent)
        }
    }

    fun onReliability(intent: ReliabilityIntent) {
        when (intent) {
            ReliabilityIntent.Back -> {
                if (reliability.showManufacturerSteps) reliability = reliability.copy(showManufacturerSteps = false) else pop()
            }

            is ReliabilityIntent.FixClicked -> {
                reliability = reliability.fixed(intent.item)
            }

            ReliabilityIntent.ManufacturerDone, ReliabilityIntent.OpenManufacturerSettings -> {
                reliability = reliability.fixed(ChecklistItem.Manufacturer)
            }

            ReliabilityIntent.RingTestAlarm -> {
                reliability = reliability.fixed(ChecklistItem.TestAlarm)
                openWake(test = true)
            }
        }
        // The banners clear themselves once every item is OK.
        if (reliability.allOk) {
            home = home.copy(reliabilityProblem = false)
            settings = settings.copy(reliabilityProblem = false)
        }
    }

    fun onPayments(intent: PaymentsIntent) {
        when (intent) {
            PaymentsIntent.Back -> pop()
            PaymentsIntent.ProblemWithChargeClicked -> push(Pushed.ProblemWithCharge)
            PaymentsIntent.PurchaseHistoryClicked -> push(Pushed.PurchaseHistory)
            else -> Unit
        }
    }
}

/**
 * The daily loop, tappable like the finished app with fake state only: bottom navigation, Home, the full editor and its
 * sub-screens (every control responds; Save updates the card), and the wake flow (see [PreviewWakeFlow]).
 * [startInSession] opens Home in the session lock ("Back to alarm" opens Ringing). Back walks back; at the root and on
 * wake screens [onExit] returns to the preview menu. Pushed screens slide in and out. [start] (round 3) opens onboarding
 * first, or the editor on its Wake-up check (the Check picker) or Motivation sub-screen with Recordings over it.
 */
@Composable
fun TapThrough(
    is24Hour: Boolean,
    onExit: () -> Unit,
    startInSession: Boolean = false,
    startTab: AppTab = AppTab.Alarms,
    start: FlowStart = FlowStart.Tabs,
) {
    val state = remember { TapThroughState(startInSession, startTab, start) }
    BackHandler { if (!state.back()) onExit() }
    // Each screen keeps its saved state (scroll position) while a screen is pushed over it, like the app's nav entries.
    val saved = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = state.stack.size to state.stack.lastOrNull(),
        transitionSpec = {
            // Deeper slides in from the end; back slides the other way.
            if (targetState.first > initialState.first) {
                (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / PARALLAX } + fadeOut())
            } else {
                (slideInHorizontally { -it / PARALLAX } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "tap-through screen",
    ) { (depth, top) ->
        saved.SaveableStateProvider(key = "$depth/$top") { Screen(top = top, state = state, is24Hour = is24Hour) }
    }
}

@Composable
private fun Screen(
    top: Pushed?,
    state: TapThroughState,
    is24Hour: Boolean,
) {
    when (top) {
        null -> {
            Tabs(state = state, is24Hour = is24Hour)
        }

        is Pushed.Editor -> {
            AlarmEditorScreen(state = state.editorShown(), is24Hour = is24Hour, onIntent = { state.onEditor(top.alarmId, it) })
        }

        is SetupPushed -> {
            SetupScreen(top = top, setup = state.setup)
        }

        Pushed.Wake -> {
            PreviewWakeScreens(flow = state.wake, is24Hour = is24Hour, onFinished = state::onWakeFinished)
        }

        is Pushed.DayDetail -> {
            DayDetailScreen(state = PreviewProgressSamples.dayDetail(top.date), is24Hour = is24Hour, onBack = { state.back() })
        }

        Pushed.PurchaseHistory -> {
            PurchaseHistoryScreen(
                state = PreviewProgressSamples.purchases,
                is24Hour = is24Hour,
                onBack = { state.back() },
                onProblemWithCharge = { state.push(Pushed.ProblemWithCharge) },
            )
        }

        Pushed.Reliability -> {
            ReliabilityScreen(state = state.reliability, onIntent = state::onReliability)
        }

        Pushed.Payments -> {
            PaymentsScreen(priceCap = PreviewProgressSamples.priceCap, onIntent = state::onPayments)
        }

        Pushed.ProblemWithCharge -> {
            ProblemWithChargeScreen(onIntent = state::onPayments)
        }
    }
}

/**
 * Back at the root: a Settings sub-screen returns to Settings, onboarding steps back; `false` on the tabs and on
 * onboarding's first step, which leave the tap-through.
 */
private fun TapThroughState.backAtRoot(): Boolean {
    val step = onboarding?.step
    return when {
        step != null && step != OnboardingStep.Mission -> {
            onOnboarding(OnboardingIntent.Back)
            true
        }

        step == null && tab == AppTab.Settings && settings.pane != SettingsPane.Main -> {
            settings = settings.copy(pane = SettingsPane.Main)
            true
        }

        else -> {
            false
        }
    }
}

/** Back on a pushed screen: an editor sub-screen returns to the editor, the checklist closes its steps, others pop. */
private fun TapThroughState.backFrom(top: Pushed) {
    when {
        top is SetupPushed -> {
            setup.back(top)
        }

        top is Pushed.Editor -> {
            if (editor.pane !=
                EditorPane.Main
            ) {
                editor = editor.copy(pane = EditorPane.Main)
            } else {
                stack.removeAt(stack.lastIndex)
            }
        }

        top == Pushed.Reliability -> {
            onReliability(ReliabilityIntent.Back)
        }

        else -> {
            stack.removeAt(stack.lastIndex)
        }
    }
}

/** The You tab: its rows push Purchase history and Payments; the rest is local state. */
private fun TapThroughState.onYou(intent: YouIntent) {
    when (intent) {
        YouIntent.PurchaseHistoryClicked -> push(Pushed.PurchaseHistory)
        YouIntent.PaymentsClicked -> push(Pushed.Payments)
        else -> you = reduceYou(you, intent)
    }
}

/** The app shell with its four tabs and the floating nav bar. */
@Composable
private fun Tabs(
    state: TapThroughState,
    is24Hour: Boolean,
) {
    state.onboarding?.let { onboarding ->
        OnboardingScreen(state = onboarding.withRegistrations(state.setup), is24Hour = is24Hour, onIntent = state::onOnboarding)
        return
    }
    // The session lock hides the nav bar and every tab shows only "Back to alarm".
    val sessionLock = state.home.sessionInProgress
    // A Settings sub-screen is a pushed screen: no nav bar, like the editor's sub-screens.
    val settingsSubScreen = state.tab == AppTab.Settings && state.settings.pane != SettingsPane.Main
    AppShell(
        selected = state.tab,
        onSelect = { state.tab = it },
        // The centre "+" starts a new alarm from any tab (owner decision 2026-10-01).
        onAdd = { state.onHome(HomeIntent.AddAlarm) },
        showNavBar = !sessionLock && !settingsSubScreen,
    ) {
        when (state.tab) {
            AppTab.Alarms -> {
                HomeScreen(state = state.home, is24Hour = is24Hour, onIntent = state::onHome)
            }

            AppTab.Progress -> {
                ProgressScreen(state = state.progress, onIntent = state::onProgress)
            }

            AppTab.Settings -> {
                SettingsScreen(
                    state = state.settings.copy(sessionInProgress = sessionLock),
                    is24Hour = is24Hour,
                    onIntent = state::onSettings,
                )
            }

            AppTab.You -> {
                YouScreen(state = state.you, onIntent = state::onYou)
            }
        }
    }
}

private fun reduceHome(
    home: HomeUiState,
    intent: HomeIntent,
): HomeUiState =
    when (intent) {
        // The 7:30 sample rings within 8 h, so turning it off asks first (commitment lock).
        is HomeIntent.AlarmToggled -> {
            if (!intent.enabled && intent.id == LOCKED_ALARM_ID) {
                home.copy(disableDialog = PreviewSamples.homeDisableDialog.disableDialog)
            } else {
                home.withEnabled(intent.id, intent.enabled)
            }
        }

        HomeIntent.DisableConfirmed -> {
            home.withEnabled(home.disableDialog?.alarmId, false).copy(disableDialog = null)
        }

        HomeIntent.DisableCancelled -> {
            home.copy(disableDialog = null)
        }

        is HomeIntent.MissedNoteDismissed -> {
            home.copy(missedAlarmAt = null)
        }

        HomeIntent.ReregisterDismissed, HomeIntent.ReregisterClicked -> {
            home.copy(reregisterCheck = null)
        }

        else -> {
            home
        }
    }

private fun HomeUiState.withEnabled(
    id: String?,
    enabled: Boolean,
) = copy(alarms = alarms.map { if (it.id == id) it.copy(enabled = enabled) else it })

private fun editorFor(
    home: HomeUiState,
    id: String,
): EditorUiState {
    val card = home.alarms.first { it.id == id }
    val sample = PreviewSamples.editorEdit
    return sample.copy(form = sample.form.copy(time = card.time, repeatDays = card.repeatDays, label = card.label.orEmpty()))
}

private fun HomeUiState.saved(
    id: String?,
    editor: EditorUiState,
): HomeUiState {
    val form = editor.form
    val card =
        AlarmCard(
            id = id ?: "new-${alarms.size + 1}",
            time = form.time,
            repeatDays = form.repeatDays,
            label = form.label.ifBlank { null },
            checks = editor.full?.checks?.map { it.type } ?: listOf(CheckType.Math),
            enabled = true,
        )
    val updated = if (id == null) alarms + card else alarms.map { if (it.id == id) card else it }
    return copy(alarms = updated.sortedBy { it.time })
}

/** The outgoing screen moves a quarter of the way, the incoming one the whole width. */
private const val PARALLAX = 4

private const val LOCKED_ALARM_ID = "2"
