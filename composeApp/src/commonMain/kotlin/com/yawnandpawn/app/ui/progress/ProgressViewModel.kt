package com.yawnandpawn.app.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.stats.ObserveProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth
import kotlin.coroutines.CoroutineContext

/**
 * Progress (Story 6.5, FR-PRG-1, FR-PRG-2): Story 6.2's [ObserveProgress] snapshot mapped by [progressStateOf], with the
 * chip of the day tapped once. The screen follows every new history row or purchase record, local midnight and a zone
 * change (the snapshot emits); a chip whose day left the ring or the shown month is cleared for good.
 *
 * Story 6.6: the calendar shows [shownMonth] ([calendarOf] over the same snapshot, so no second read or day ticker).
 * "Previous month" and "Next month" page only within the limits the shown calendar allows; paging clears a calendar chip
 * and keeps a ring chip.
 *
 * Loading until the first snapshot. A failing snapshot flow is logged and shows the load failure with "Try again"
 * (resubscribes), never the empty state, which would say no morning exists. A read only: nothing is written, so no
 * session-lock check is needed. Opening Day detail and Purchase history happens in [ProgressRoute].
 */
class ProgressViewModel(
    private val observeProgress: ObserveProgress,
    private val logger: Logger,
    /** Where the calendar's month is computed (Dispatchers.Default in the app; tests pass their test dispatcher). */
    private val computeContext: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    /** Bumped by "Try again" to subscribe to the snapshot again. */
    private val loads = MutableStateFlow(0)

    /** The ring dot or calendar day tapped once; kept across Day detail and back. */
    private val selection = MutableStateFlow<DaySelection?>(null)

    /** The calendar's month; `null` is today's month, which follows today into a new month at midnight. */
    private val shownMonth = MutableStateFlow<YearMonth?>(null)

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
                // The month is mapped off the main thread, before the chip joins, so a tap does not recompute it. Back on
                // today's month (paged there, or today moved back into it) the calendar follows today again.
                val calendars =
                    combine(observeProgress(), shownMonth) { snapshot, month ->
                        val calendar = calendarOf(snapshot, month)
                        if (month != null && calendar.today != null) shownMonth.compareAndSet(month, null)
                        snapshot to calendar
                    }.flowOn(computeContext)
                combine(calendars, selection) { (snapshot, calendar), chosen ->
                    settledAttempt = attempt
                    progressStateOf(snapshot, chosen, calendar).also { shown ->
                        // A chip whose day left the ring or the month never comes back with a later snapshot.
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

            ProgressIntent.PreviousMonth -> page(forward = false)

            ProgressIntent.NextMonth -> page(forward = true)

            // The route opens Day detail and keeps the chip (it is still there after Back); Purchase history is the
            // route's too.
            is ProgressIntent.DayTapped,
            ProgressIntent.PurchaseHistoryClicked,
            -> Unit
        }
    }

    /**
     * One month back or forward from the shown calendar, only when it allows it (a disabled direction changes nothing).
     * Back on today's month the calendar follows today again. A calendar chip is cleared; a ring chip stays.
     */
    private fun page(forward: Boolean) {
        val shown = state.value
        val calendar = shown.calendar
        val allowed = if (forward) calendar?.hasNext else calendar?.hasPrevious
        if (calendar != null && allowed == true) {
            val month = calendar.firstDay.yearMonth
            val target = if (forward) month.plusMonth() else month.minusMonth()
            shownMonth.value = target.takeIf { it != shown.today?.yearMonth }
            selection.update { if (it?.inCalendar == true) null else it }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
