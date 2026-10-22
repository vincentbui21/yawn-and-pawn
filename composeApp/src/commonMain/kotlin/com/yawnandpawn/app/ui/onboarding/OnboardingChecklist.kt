package com.yawnandpawn.app.ui.onboarding

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yawnandpawn.app.core.onboarding.RememberOnboardingTest
import com.yawnandpawn.app.core.reliability.ReliabilityRecords
import com.yawnandpawn.app.core.telemetry.ConsentStore
import com.yawnandpawn.app.core.telemetry.SetAnalyticsConsent
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.ui.checkpicker.CheckPickerIntent
import com.yawnandpawn.app.ui.checkpicker.CheckPickerUiState
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.FullEditorSections
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ChecklistRow
import com.yawnandpawn.app.ui.reliability.ChecklistTestAlarm
import com.yawnandpawn.app.ui.reliability.ReliabilityEffect
import com.yawnandpawn.app.ui.reliability.ReliabilityIntent
import com.yawnandpawn.app.ui.reliability.ReliabilityViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Builds onboarding's step 6 checklist (Story 5.11): Story 5.4's own [ReliabilityViewModel] (its rows, re-check and
 * every row's "Fix", the resolver-checked deep links and the camera ask included), so step 6 and the checklist screen
 * never differ.
 */
fun interface ChecklistScreens {
    fun create(): ReliabilityViewModel
}

/** What onboarding's step 7 needs (Story 5.11): whether the user was asked already, and the choice's use case (5.6). */
class UsageStatsSources(
    val consents: ConsentStore,
    val setConsent: SetAnalyticsConsent,
)

/**
 * What onboarding's step 8 needs (Story 5.12): the checklist's own test ([ring], Story 1.18 through 5.4, rung on the
 * first alarm), where its ring time is stored ([remember]), where 5.3 records a locked pass ([records], read only), and
 * the clock's changes ([timeChanges]: each minute, a time set, a zone change), on which step 8 renders again (review fix).
 */
class TestAlarmSources(
    val ring: ChecklistTestAlarm,
    val remember: RememberOnboardingTest,
    val records: ReliabilityRecords,
    val timeChanges: TimeChangeSignal = TimeChangeSignal { emptyFlow() },
)

/**
 * Onboarding's step 6, "Make sure it rings" (Story 5.11): a child [ReliabilityViewModel] in its own [ViewModelStore],
 * cleared when onboarding closes. [rows] are the checklist screen's rows without the test alarm (step 8 is the test);
 * "Fix" on the maker row reports [onOpenGuidance], which onboarding shows over the step (no route can be pushed over
 * onboarding).
 */
internal class OnboardingChecklist(
    screens: ChecklistScreens,
    scope: CoroutineScope,
    private val onOpenGuidance: () -> Unit,
) : AutoCloseable {
    private val store = ViewModelStore()
    private val checklist: ReliabilityViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { screens.create() } })[ReliabilityViewModel::class]

    /** The rows shown on step 6: every row of the checklist screen except "Test alarm". */
    val rows: Flow<List<ChecklistRow>> = checklist.state.map { state -> state.rows.filterNot { it.item == ChecklistItem.TestAlarm } }

    init {
        scope.launch {
            checklist.effects.collect { effect ->
                when (effect) {
                    ReliabilityEffect.OpenManufacturerGuidance -> onOpenGuidance()
                }
            }
        }
    }

    /** Reads every row again (the step opened, the app resumed, the maker guidance closed). */
    fun refresh() = checklist.refresh()

    /** "Fix" on [item]: the checklist screen's own target for it. */
    fun fix(item: ChecklistItem) = checklist.onIntent(ReliabilityIntent.FixClicked(item))

    override fun close() = store.clear()
}

/**
 * The editor's Wake-up check sub-screen as step 5's Check picker; with no check, "Pick at least one check." shows under
 * it (and "Continue" is disabled).
 */
internal fun FullEditorSections.toPicker(): CheckPickerUiState =
    CheckPickerUiState(
        checks = checks,
        mode = checkMode,
        noCheckError = noCheckError || checks.isEmpty(),
        talkBackOn = talkBackOn,
        cameraUnavailable = cameraUnavailable,
        qrCodeSaved = qrCodeSaved,
        houseHuntPhotos = houseHuntPhotos,
        types = types,
    )

/** A tap in step 5's Check picker as the editor's Wake-up check sub-screen sends it. */
internal fun CheckPickerIntent.toEditor(): EditorIntent =
    when (this) {
        is CheckPickerIntent.Toggled -> EditorIntent.CheckToggled(type, selected)
        is CheckPickerIntent.ModeSelected -> EditorIntent.CheckModeSelected(mode)
        is CheckPickerIntent.SetupClicked -> EditorIntent.CheckSetupClicked(type)
        is CheckPickerIntent.Moved -> EditorIntent.CheckMoved(type, up)
        CheckPickerIntent.FixCamera -> EditorIntent.FixCamera
    }
