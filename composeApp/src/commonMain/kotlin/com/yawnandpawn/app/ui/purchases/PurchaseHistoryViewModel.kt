package com.yawnandpawn.app.ui.purchases

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
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

/**
 * Purchase history (Story 4.16, FR-PRG-4): every purchase record from [records], newest first, as `purchase-row`s in
 * month cards ([monthsOf]), each mapped by [purchaseOf] with the alarm's label from [alarms] and the time its session
 * rang for from [history]. The screen follows every change of the records.
 *
 * A failing read of the records shows the load failure with "Try again" (resubscribes), never the empty state, which
 * would say no charge exists. The alarm labels and session times only name the alarm: a failing read of them is logged
 * and drops that detail, never the charges. A session's ring time never changes, so it is read once per session.
 */
class PurchaseHistoryViewModel(
    private val records: PurchaseRecordRepository,
    alarms: AlarmRepository,
    private val history: SessionHistoryRepository,
    private val timeZoneProvider: TimeZoneProvider,
    private val logger: Logger,
) : ViewModel() {
    /** Bumped by "Try again" to subscribe to the records again. */
    private val loads = MutableStateFlow(0)

    /** Sessions already read (null: no row), by id. */
    private val sessions = mutableMapOf<String, SessionHistoryRow?>()

    private val alarmsById: Flow<Map<String, Alarm>> =
        alarms
            .observeAll()
            .map { list -> list.associateBy { it.id } }
            .catch { cause ->
                logFailure("load alarm labels", cause)
                emit(emptyMap())
            }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<PurchaseHistoryUiState> =
        loads
            .flatMapLatest {
                combine(records.observeAll(), alarmsById) { rows, byId -> PurchaseHistoryUiState(purchases = render(rows, byId)) }
                    .onStart { emit(PurchaseHistoryUiState(loading = true)) }
                    .catch { cause ->
                        logFailure("load purchase history", cause)
                        emit(PurchaseHistoryUiState(loadFailed = true))
                    }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PurchaseHistoryUiState(loading = true))

    fun onIntent(intent: PurchaseHistoryIntent) {
        when (intent) {
            PurchaseHistoryIntent.RetryLoad -> loads.update { it + 1 }
        }
    }

    private suspend fun render(
        rows: List<PurchaseRecord>,
        alarms: Map<String, Alarm>,
    ): List<Purchase> {
        val zone = timeZoneProvider.current()
        return rows.sortedWith(NewestPurchaseFirst).map { record ->
            purchaseOf(record, zone, alarms, record.sessionId?.let { session(it) })
        }
    }

    /** The history row of [sessionId], or null when there is none or it cannot be read now (logged, read again later). */
    private suspend fun session(sessionId: String): SessionHistoryRow? {
        if (sessionId in sessions) return sessions[sessionId]
        return when (val found = history.find(sessionId)) {
            is Outcome.Success -> found.value.also { sessions[sessionId] = it }
            is Outcome.Failure -> null.also { logger.log(LogEvent.OperationFailed.of("load purchase session", found.error)) }
        }
    }

    private fun logFailure(
        operation: String,
        cause: Throwable,
    ) = logger.log(LogEvent.OperationFailed(operation, cause.message ?: cause::class.simpleName.orEmpty()))

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
