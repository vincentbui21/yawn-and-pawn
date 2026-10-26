package com.yawnandpawn.app.ui.daydetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.config.CommitmentEvent
import com.yawnandpawn.app.core.config.CommitmentEventRepository
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.stats.SessionOnDay
import com.yawnandpawn.app.core.stats.isCounted
import com.yawnandpawn.app.core.stats.sessionsOn
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

/**
 * Day detail of [date] (Story 6.7): every finished session of that local day from session history with its purchase
 * records (6.2's `sessionsOn`), its merged alarms, the alarms' labels, and the alarm changes logged for the day, mapped
 * by [dayDetailOf] in the phone's current zone. It follows every new history row, purchase record and alarm change.
 *
 * Loading until the first read. A failing history or records flow is logged and shows the load failure with "Try again"
 * (resubscribes). The merges, the commitment events and the alarm labels only add detail: a failing read of them is
 * logged and leaves those rows, notes or labels out. A read only: nothing is written, so no session-lock check.
 */
class DayDetailViewModel(
    private val date: LocalDate,
    private val history: SessionHistoryRepository,
    private val records: PurchaseRecordRepository,
    alarms: AlarmRepository,
    private val commitmentEvents: CommitmentEventRepository,
    private val timeZoneProvider: TimeZoneProvider,
    private val logger: Logger,
) : ViewModel() {
    /** Bumped by "Try again" to subscribe again. */
    private val loads = MutableStateFlow(0)

    /** The last load attempt that showed data or its failure. */
    private var settledAttempt = -1

    private val alarmsById: Flow<Map<String, Alarm>> =
        alarms
            .observeAll()
            .map { list -> list.associateBy { it.id } }
            .catch { cause ->
                logFailure("load alarm labels", cause)
                emit(emptyMap())
            }

    /**
     * Loading shows before the first read (the initial value) and after "Try again", never when the upstream only
     * restarts (back on Day detail after more than 5 s away): the last screen stays until the new read.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DayDetailUiState> =
        loads
            .flatMapLatest { attempt ->
                combine(history.observeAll(), records.observeAll(), alarmsById) { rows, read, byId ->
                    load(rows, read.records, byId).also { settledAttempt = attempt }
                }.onStart { if (attempt > 0 && settledAttempt != attempt) emit(DayDetailUiState(date, loading = true)) }
                    .catch { cause ->
                        settledAttempt = attempt
                        logFailure("load day detail", cause)
                        emit(DayDetailUiState(date, loadFailed = true))
                    }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DayDetailUiState(date, loading = true))

    fun onIntent(intent: DayDetailIntent) {
        when (intent) {
            DayDetailIntent.RetryLoad -> loads.update { it + 1 }
        }
    }

    private suspend fun load(
        rows: List<SessionHistoryRow>,
        purchases: List<PurchaseRecord>,
        alarms: Map<String, Alarm>,
    ): DayDetailUiState {
        val zone = timeZoneProvider.current()
        val sessions = sessionsOn(rows, purchases, date, zone)
        return dayDetailOf(
            date = date,
            sessions = sessions,
            merges = mergesOf(sessions),
            events = changes(),
            alarms = alarms,
            zone = zone,
        )
    }

    /** The merges of the day's counted sessions (Test and Skipped show no timeline); a failed read leaves that session's out. */
    private suspend fun mergesOf(sessions: List<SessionOnDay>): Map<String, List<SessionMergeRow>> =
        sessions
            .filter { isCounted(it.row) }
            .associate { session ->
                val id = session.row.sessionId
                id to
                    when (val read = history.merges(id)) {
                        is Outcome.Success -> read.value
                        is Outcome.Failure -> emptyList<SessionMergeRow>().also { logger.log(LogEvent.OperationFailed.of("load merges", read.error)) }
                    }
            }

    /** Every logged alarm change; a failed read shows no notes. */
    private suspend fun changes(): List<CommitmentEvent> =
        when (val read = commitmentEvents.all()) {
            is Outcome.Success -> read.value
            is Outcome.Failure -> emptyList<CommitmentEvent>().also { logger.log(LogEvent.OperationFailed.of("load alarm changes", read.error)) }
        }

    private fun logFailure(
        operation: String,
        cause: Throwable,
    ) = logger.log(LogEvent.OperationFailed(operation, cause.message ?: cause::class.simpleName.orEmpty()))

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
