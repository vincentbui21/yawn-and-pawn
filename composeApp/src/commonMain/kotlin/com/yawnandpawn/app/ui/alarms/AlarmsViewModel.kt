package com.yawnandpawn.app.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.alarm.AlarmRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/** One row of the interim alarm list (Story 1.9 replaces it with `card-alarm`). */
data class AlarmRow(
    val id: String,
    val time: LocalTime,
    val repeatDays: Set<DayOfWeek>,
    val label: String?,
)

/** What the Alarms route renders. */
data class AlarmsUiState(
    val isLoading: Boolean = true,
    val alarms: List<AlarmRow> = emptyList(),
    /** The alarms could not be read: neither the empty state nor the list is shown (error copy is not defined yet). */
    val loadFailed: Boolean = false,
)

/** Alarms route (Story 1.8): the stored alarms in list order, or the empty state. */
class AlarmsViewModel(
    repository: AlarmRepository,
) : ViewModel() {
    val state: StateFlow<AlarmsUiState> =
        repository
            .observeAll()
            .map { alarms -> AlarmsUiState(isLoading = false, alarms = alarms.map { AlarmRow(it.id, it.time, it.repeatDays, it.label) }) }
            // A storage failure must not look like "No alarms yet." (that invites re-creating alarms that exist).
            .catch { emit(AlarmsUiState(isLoading = false, loadFailed = true)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AlarmsUiState())

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
