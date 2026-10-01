package com.yawnandpawn.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmListOrder
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.durationUntil
import com.yawnandpawn.app.core.alarm.nextOccurrence
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.countdownOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Home, the Alarms tab (Story 1.9): the stored alarms as `card-alarm`s in [AlarmListOrder] and the countdown to the
 * soonest enabled one, computed with the scheduler's `nextOccurrence` and `durationUntil` from [clock] and
 * [timeZoneProvider]. The countdown is recomputed on every [timeChanges] signal (each minute, a time set, a zone change)
 * and when Home resumes. A switch calls `SetAlarmEnabled` at once and shows the new value until the store confirms it
 * (or reverts on failure); Duplicate opens the copy in the editor; Delete asks first and is logged. Navigation goes out
 * through [effects]. The missed note (Story 1.16) shows the alarm time of the latest Missed session from [missedNotes]
 * until "Dismiss" stores its dismissal.
 */
class HomeViewModel(
    repository: AlarmRepository,
    private val actions: AlarmActions,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    timeChanges: TimeChangeSignal,
    private val missedNotes: MissedNotes,
) : ViewModel() {
    /** Bumped by "Try again" to subscribe to the alarms again. */
    private val loads = MutableStateFlow(0)
    private val resumes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val local = MutableStateFlow(LocalState())
    private val _effects = Channel<HomeEffect>(Channel.BUFFERED)
    val effects: Flow<HomeEffect> = _effects.receiveAsFlow()
    private var snackbarJob: Job? = null
    private var lastToggle = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val stored: Flow<StoredAlarms> =
        loads.flatMapLatest {
            repository
                .observeAll()
                .map<List<Alarm>, StoredAlarms> { StoredAlarms.Loaded(it) }
                // A storage failure must not look like "No alarms yet." (that invites re-creating alarms that exist).
                .catch { cause ->
                    actions.logFailure("load alarms", cause)
                    emit(StoredAlarms.Failed)
                }
        }

    private val ticks: Flow<Unit> = merge(timeChanges.changes(), resumes).onStart { emit(Unit) }

    /** The session whose missed note shows, for "Dismiss". */
    private var shownMissed: String? = null

    /** The Missed session to tell the user about (Story 1.16); a failing read is logged and shows no note. */
    private val missed: Flow<SessionHistoryRow?> =
        missedNotes
            .current()
            .onStart { emit(null) }
            .catch { cause ->
                actions.logFailure("load missed note", cause)
                emit(null)
            }

    val state: StateFlow<HomeUiState> =
        combine(stored, ticks, local, missed) { alarms, _, ui, missedRow -> render(alarms, ui, missedRow) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState(isLoading = true))

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.AddAlarm -> {
                _effects.trySend(HomeEffect.OpenEditor(null))
            }

            is HomeIntent.EditAlarm -> {
                _effects.trySend(HomeEffect.OpenEditor(intent.id))
            }

            is HomeIntent.AlarmToggled -> {
                toggle(intent.id, intent.enabled)
            }

            is HomeIntent.DuplicateClicked -> {
                duplicate(intent.id)
            }

            is HomeIntent.DeleteClicked -> {
                requestDelete(intent.id)
            }

            HomeIntent.DeleteConfirmed -> {
                confirmDelete()
            }

            HomeIntent.DeleteCancelled -> {
                local.update { it.copy(deleteDialog = null) }
            }

            HomeIntent.RetryLoad -> {
                // A dialog left open when the list failed must not come back by itself.
                local.update { it.copy(deleteDialog = null) }
                loads.update { it + 1 }
            }

            HomeIntent.EditorOpenFailed -> {
                showOpenFailed()
            }

            HomeIntent.Resumed -> {
                resumes.tryEmit(Unit)
            }

            HomeIntent.MissedNoteDismissed -> {
                dismissMissed()
            }

            // The hero, notices, session panel and commitment-lock dialog arrive with their own stories.
            else -> {
                Unit
            }
        }
    }

    private fun toggle(
        id: String,
        enabled: Boolean,
    ) {
        val token = ++lastToggle
        local.update { it.copy(toggles = it.toggles + (id to PendingToggle(enabled, token))) }
        viewModelScope.launch {
            val result = actions.setEnabled(id, enabled)
            local.update { ui ->
                // A newer toggle of the same alarm owns the switch now.
                val pending = ui.toggles[id]?.takeIf { it.token == token } ?: return@update ui
                when (result) {
                    is Outcome.Success -> ui.copy(toggles = ui.toggles + (id to pending.copy(confirmedAt = result.value.updatedAt)))

                    // The switch goes back to the stored value.
                    is Outcome.Failure -> ui.copy(toggles = ui.toggles - id)
                }
            }
        }
    }

    private fun duplicate(id: String) {
        viewModelScope.launch {
            val result = actions.duplicate(id)
            if (result is Outcome.Success) _effects.send(HomeEffect.OpenEditor(result.value.id))
        }
    }

    private fun requestDelete(id: String) {
        val card = state.value.alarms.firstOrNull { it.id == id } ?: return
        local.update { it.copy(deleteDialog = DeleteAlarmDialog(card.id, card.time)) }
    }

    private fun confirmDelete() {
        // Cleared first, so a second tap on "Delete" cannot delete (and log) twice.
        val dialog = local.value.deleteDialog ?: return
        local.update { it.copy(deleteDialog = null) }
        viewModelScope.launch { actions.delete(dialog.alarmId) }
    }

    /** The note hides once the store has the dismissal; a failed write is logged and the note stays. */
    private fun dismissMissed() {
        val sessionId = shownMissed ?: return
        viewModelScope.launch {
            val dismissed = missedNotes.dismiss(sessionId)
            if (dismissed is Outcome.Failure) actions.logFailure("dismiss missed note", dismissed.error)
        }
    }

    private fun showOpenFailed() {
        local.update { it.copy(openFailed = true) }
        snackbarJob?.cancel()
        snackbarJob =
            viewModelScope.launch {
                delay(SNACKBAR_MILLIS)
                local.update { it.copy(openFailed = false) }
            }
    }

    private fun render(
        stored: StoredAlarms,
        ui: LocalState,
        missedRow: SessionHistoryRow?,
    ): HomeUiState {
        shownMissed = missedRow?.sessionId
        // The note names the alarm's own time ("Your 6:00 AM alarm"), in the zone the phone is in now.
        val missedAlarmAt = missedRow?.scheduledAt?.toLocalDateTime(timeZoneProvider.current())?.time
        return when (stored) {
            StoredAlarms.Failed -> {
                HomeUiState(loadFailed = true, openFailed = ui.openFailed, missedAlarmAt = missedAlarmAt)
            }

            is StoredAlarms.Loaded -> {
                val alarms = stored.alarms.sortedWith(AlarmListOrder)
                val cards = alarms.map { it.toCard(ui.toggles[it.id]) }
                HomeUiState(
                    nextAlarm = nextAlarm(alarms.filterIndexed { index, _ -> cards[index].enabled }),
                    alarms = cards,
                    deleteDialog = ui.deleteDialog?.takeIf { dialog -> cards.any { it.id == dialog.alarmId } },
                    openFailed = ui.openFailed,
                    missedAlarmAt = missedAlarmAt,
                )
            }
        }
    }

    /** The countdown to the soonest of [enabled], as the scheduler computes it; `null` when none is enabled. */
    private fun nextAlarm(enabled: List<Alarm>): Countdown? {
        val now = clock.now()
        val zone = timeZoneProvider.current()
        return enabled
            .minOfOrNull { nextOccurrence(it.toRule(), now, zone) }
            ?.let { countdownOf(durationUntil(it, now)) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** How long "Couldn't open this alarm." shows (`snackbar`). */
        const val SNACKBAR_MILLIS = 4_000L
    }
}

/** What the repository gave: the alarms, or a read failure. */
private sealed interface StoredAlarms {
    data class Loaded(
        val alarms: List<Alarm>,
    ) : StoredAlarms

    data object Failed : StoredAlarms
}

/** A switch the user moved: [enabled] shows until the store has an update at least as new as [confirmedAt]. */
private data class PendingToggle(
    val enabled: Boolean,
    val token: Long,
    val confirmedAt: Instant? = null,
)

/** Home state that is not in the store. */
private data class LocalState(
    val toggles: Map<String, PendingToggle> = emptyMap(),
    val deleteDialog: DeleteAlarmDialog? = null,
    val openFailed: Boolean = false,
)

/** Epic 1 cards show no check icons (checks arrive in Epic 3). */
private fun Alarm.toCard(pending: PendingToggle?): AlarmCard {
    val confirmed = pending?.confirmedAt
    val shown = if (pending == null || (confirmed != null && updatedAt >= confirmed)) enabled else pending.enabled
    return AlarmCard(id = id, time = time, repeatDays = repeatDays, label = label, checks = emptyList(), enabled = shown)
}
