package com.yawnandpawn.app.ui.reliability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.ChecklistEntry
import com.yawnandpawn.app.core.reliability.EvaluateReliability
import com.yawnandpawn.app.core.reliability.ReliabilitySettings
import com.yawnandpawn.app.core.reliability.RowStatus
import com.yawnandpawn.app.ui.qr.CameraPermission
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.yawnandpawn.app.core.reliability.ChecklistRow as CoreRow

/** One-off results of the reliability checklist. */
sealed interface ReliabilityEffect {
    /** "Fix" on "Manufacturer settings": the maker guidance (Story 5.5) opens over the checklist. */
    data object OpenManufacturerGuidance : ReliabilityEffect
}

/**
 * The reliability checklist (Story 5.4, FR-ONB-2/3, flow F7).
 *
 * - **Rows:** [refresh] (every `ON_START` and `ON_RESUME`, so a row fixed in the system settings reads "OK" on return
 *   without a tap) runs [evaluate] and shows each row that is not Hidden ([toChecklistRows]); evaluations run one after
 *   the other, so the latest decides. Before the first one ends there are no rows.
 * - **Fix:** each row's own target, from the user's tap. Manufacturer opens the maker guidance ([effects]). Camera asks
 *   the runtime permission through [camera] on the first tap (granted: the row re-reads); after a "no" (also "Don't ask
 *   again", which the app cannot tell apart) the next tap opens the app details through [settings] (DEFAULT TAKEN 2).
 *   Every other row opens its system screen through [settings], which checks it resolves and falls back.
 * - **Ring a test alarm:** [ringTest] schedules a test 10 s from now, one at a time, then the snackbar "Lock your
 *   phone. We'll ring in 10 seconds." shows for 4 s. A test that cannot be armed is logged and shows nothing, as in the
 *   editor (no new string).
 */
class ReliabilityViewModel(
    private val evaluate: EvaluateReliability,
    private val settings: ReliabilitySettings,
    private val camera: CameraPermission,
    private val ringTest: ChecklistTestAlarm,
    private val logger: Logger,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReliabilityUiState(rows = emptyList()))
    val state: StateFlow<ReliabilityUiState> = mutableState.asStateFlow()

    private val mutableEffects = Channel<ReliabilityEffect>(Channel.BUFFERED)
    val effects: Flow<ReliabilityEffect> = mutableEffects.receiveAsFlow()

    private val evaluations = Mutex()

    /** The camera permission was asked from this screen and refused: the next "Fix" opens the app details. */
    private var cameraRefused = false
    private var testInFlight = false
    private var snackbarJob: Job? = null

    /** Re-reads every row (the screen started or resumed). */
    fun refresh() {
        viewModelScope.launch {
            evaluations.withLock {
                val rows = evaluate().toChecklistRows()
                mutableState.update { it.copy(rows = rows) }
            }
        }
    }

    /** Back is the route's own; the design-preview pane's intents do nothing here (the guidance is its own screen). */
    fun onIntent(intent: ReliabilityIntent) {
        when (intent) {
            is ReliabilityIntent.FixClicked -> fix(intent.item)
            ReliabilityIntent.RingTestAlarm -> ringTestAlarm()
            else -> Unit
        }
    }

    private fun fix(item: ChecklistItem) {
        when {
            item == ChecklistItem.Manufacturer -> {
                mutableEffects.trySend(ReliabilityEffect.OpenManufacturerGuidance)
            }

            item == ChecklistItem.TestAlarm -> {
                ringTestAlarm()
            }

            item == ChecklistItem.Camera && !cameraRefused -> {
                viewModelScope.launch {
                    cameraRefused = !camera.request()
                    refresh()
                }
            }

            else -> {
                settings.open(item.row)
            }
        }
    }

    private fun ringTestAlarm() {
        // One test at a time: a second tap while one is being armed would interleave the store and the arming.
        if (testInFlight) return
        testInFlight = true
        viewModelScope.launch {
            // Not cancelled by the screen closing: the config is stored and the alarm armed (or both taken back).
            val result = withContext(NonCancellable) { ringTest() }
            testInFlight = false
            when (result) {
                is Outcome.Success -> showTestScheduled()
                is Outcome.Failure -> logger.log(LogEvent.OperationFailed.of(SCHEDULE_TEST, result.error))
            }
        }
    }

    private fun showTestScheduled() {
        snackbarJob?.cancel()
        mutableState.update { it.copy(testScheduled = true) }
        snackbarJob =
            viewModelScope.launch {
                delay(SNACKBAR_MILLIS)
                mutableState.update { it.copy(testScheduled = false) }
            }
    }

    companion object {
        /** The log operation of a test alarm that could not be armed. */
        const val SCHEDULE_TEST = "schedule checklist test alarm"

        /** How long "Lock your phone. We'll ring in 10 seconds." shows (`snackbar`). */
        const val SNACKBAR_MILLIS = 4_000L
    }
}

/** The rows the checklist shows: every row that is not Hidden, in order; NeedsFix reads as missing. */
fun List<ChecklistEntry>.toChecklistRows(): List<ChecklistRow> =
    mapNotNull { entry ->
        val status =
            when (entry.status) {
                RowStatus.Ok -> ItemStatus.Ok
                RowStatus.NeedsFix -> ItemStatus.Missing
                RowStatus.Revoked -> ItemStatus.Revoked
                RowStatus.Hidden -> null
            }
        status?.let { ChecklistRow(entry.row.item, it) }
    }

/** The approved row of this checklist row. */
val CoreRow.item: ChecklistItem
    get() =
        when (this) {
            CoreRow.Notifications -> ChecklistItem.Notifications
            CoreRow.FullScreenAlarm -> ChecklistItem.FullScreen
            CoreRow.ExactAlarms -> ChecklistItem.ExactAlarms
            CoreRow.DoNotDisturb -> ChecklistItem.DoNotDisturb
            CoreRow.Battery -> ChecklistItem.Battery
            CoreRow.Manufacturer -> ChecklistItem.Manufacturer
            CoreRow.Camera -> ChecklistItem.Camera
            CoreRow.Microphone -> ChecklistItem.Microphone
            CoreRow.LockedScreenTest -> ChecklistItem.TestAlarm
        }

/** The checklist row this approved row shows. */
val ChecklistItem.row: CoreRow
    get() = CoreRow.entries.first { it.item == this }
