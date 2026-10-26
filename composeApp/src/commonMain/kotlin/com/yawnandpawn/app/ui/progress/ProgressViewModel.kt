package com.yawnandpawn.app.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.stats.ObserveProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Progress (Story 6.5, FR-PRG-1, FR-PRG-2): Story 6.2's [ObserveProgress] snapshot mapped by [progressStateOf], with the
 * chip of the day tapped once. The screen follows every new history row or purchase record, local midnight and a zone
 * change (the snapshot emits); a chip whose day left the ring is cleared for good.
 *
 * Loading until the first snapshot. A failing snapshot flow is logged and shows the load failure with "Try again"
 * (resubscribes), never the empty state, which would say no morning exists. A read only: nothing is written, so no
 * session-lock check is needed. Opening Day detail and Purchase history happens in [ProgressRoute].
 */
class ProgressViewModel(
    private val observeProgress: ObserveProgress,
    private val logger: Logger,
) : ViewModel() {
    /** Bumped by "Try again" to subscribe to the snapshot again. */
    private val loads = MutableStateFlow(0)

    /** The ring dot or calendar day tapped once; kept across Day detail and back. */
    private val selection = MutableStateFlow<DaySelection?>(null)

    /** The last load attempt that showed data or its failure. */
    private var settledAttempt = -1

    /**
     * Loading shows before the first snapshot (the initial value) and after "Try again", never when the upstream only
     * restarts (back on Progress after more than 5 s away): the last screen stays, with its scroll, until the new snapshot.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ProgressUiState> =
        loads
            .flatMapLatest { attempt ->
                combine(observeProgress(), selection) { snapshot, chosen ->
                    settledAttempt = attempt
                    progressStateOf(snapshot, chosen).also { shown ->
                        // A chip whose day left the ring never comes back with a later snapshot.
                        if (shown.selection != chosen) selection.compareAndSet(chosen, shown.selection)
                    }
                }.onStart { if (attempt > 0 && settledAttempt != attempt) emit(ProgressUiState(loading = true)) }
                    .catch { cause ->
                        settledAttempt = attempt
                        logger.log(LogEvent.OperationFailed("load progress", cause.message ?: cause::class.simpleName.orEmpty()))
                        emit(ProgressUiState(loadFailed = true))
                    }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProgressUiState(loading = true))

    fun onIntent(intent: ProgressIntent) {
        when (intent) {
            is ProgressIntent.DaySelected -> selection.value = intent.selection
            ProgressIntent.RetryLoad -> loads.update { it + 1 }
            // The route opens Day detail and keeps the chip (it is still there after Back); Purchase history is the
            // route's too. The calendar's months page in Story 6.6.
            is ProgressIntent.DayTapped,
            ProgressIntent.PurchaseHistoryClicked,
            ProgressIntent.PreviousMonth,
            ProgressIntent.NextMonth,
            -> Unit
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
