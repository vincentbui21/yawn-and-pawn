package com.yawnandpawn.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmListOrder
import com.yawnandpawn.app.core.alarm.AlarmWithChecks
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.alarm.durationUntil
import com.yawnandpawn.app.core.alarm.nextOccurrence
import com.yawnandpawn.app.core.alarm.orderedEntries
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.errorOrNull
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.reliability.ReliabilitySettings
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.core.stats.ReRegisterInputs
import com.yawnandpawn.app.core.stats.ReRegisterSuggestion
import com.yawnandpawn.app.core.stats.ReRegisterSuggestions
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.checks.toUi
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.countdownOf
import com.yawnandpawn.app.ui.wake.uiCheckType
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
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType
import com.yawnandpawn.app.ui.checks.CheckType as UiCheckType

/**
 * Home, the Alarms tab (Story 1.9): the stored alarms as `card-alarm`s in [AlarmListOrder] and the countdown to the
 * soonest enabled one, computed with the scheduler's `nextOccurrence` and `durationUntil` from [clock] and
 * [timeZoneProvider]. The countdown is recomputed on every [timeChanges] signal (each minute, a time set, a zone change)
 * and when Home resumes. A switch calls `SetAlarmEnabled` at once and shows the new value until the store confirms it
 * (or reverts on failure); Duplicate opens the editor on a new, unsaved alarm prefilled from the card (owner decision
 * 2026-10-05); Delete asks first and is logged. A switch or Delete that cannot be stored shows "Couldn't save the
 * alarm. Try again." (owner decision 2026-10-02). Navigation goes out through [effects]. The missed note (Story 1.16)
 * shows the alarm time of the latest Missed session from [missedNotes] until "Dismiss" stores its dismissal. The
 * reliability banner (Story 1.19) shows while [reliability] finds a setting off; it is checked when Home starts and
 * resumes (a permission dialog only pauses it), and "Fix" checks again and opens the setting that is off now through
 * [reliabilitySettings]. The re-register banner (Story 3.13) shows [reRegister]'s suggestion for the listed alarms,
 * again on every tick (the 7-day window moves), named by [uiTypeOf]; "Re-register" opens that alarm's editor until QR
 * registration arrives (Story 3.10), "Dismiss" stores the dismissal.
 */
class HomeViewModel(
    /** The stored alarms with their checks (the card's check icons, Story 3.5), from one read of both tables. */
    checkConfigs: CheckConfigRepository,
    private val actions: AlarmActions,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    timeChanges: TimeChangeSignal,
    private val missedNotes: MissedNotes,
    private val reliability: ReliabilityProbe,
    private val reliabilitySettings: ReliabilitySettings,
    private val reRegister: ReRegisterSuggestions,
    /** The check the banner names; a type without a UI (the placeholder) shows no banner. */
    private val uiTypeOf: (CoreCheckType) -> UiCheckType? = ::uiCheckType,
) : ViewModel() {
    /** Bumped by "Try again" to subscribe to the alarms again. */
    private val loads = MutableStateFlow(0)
    private val resumes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val local = MutableStateFlow(LocalState(reliability = reliability.check()))
    private val _effects = Channel<HomeEffect>(Channel.BUFFERED)
    val effects: Flow<HomeEffect> = _effects.receiveAsFlow()
    private var snackbarJob: Job? = null
    private var lastToggle = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val stored: Flow<StoredAlarms> =
        loads.flatMapLatest {
            checkConfigs
                .observeAlarmsWithChecks()
                .map<List<AlarmWithChecks>, StoredAlarms> { StoredAlarms.Loaded(it) }
                // A storage failure must not look like "No alarms yet." (that invites re-creating alarms that exist). The
                // alarms and their checks are one read, so a failure of either shows the load failure with "Try again".
                .catch { cause ->
                    actions.logFailure("load alarms", cause)
                    emit(StoredAlarms.Failed)
                }
        }

    private val ticks: Flow<Unit> = merge(timeChanges.changes(), resumes).onStart { emit(Unit) }

    /**
     * The Missed session to tell the user about (Story 1.16). A failing read is logged and shows no note, then the read
     * is tried again after a growing pause (1 s, 2 s, 4 s … at most a minute), so a passing failure does not hide the note
     * for the rest of the ViewModel's life.
     */
    private val missed: Flow<SessionHistoryRow?> =
        missedNotes
            .current()
            .retryWhen { cause, attempt ->
                actions.logFailure("load missed note", cause)
                emit(null)
                delay(missedRetryDelay(attempt))
                true
            }.onStart { emit(null) }

    /** The suggestion the banner shows now, so "Re-register" and "Dismiss" act on exactly that alarm and check. */
    private var shownSuggestion: ReRegisterSuggestion? = null

    /**
     * What the re-register banner reads from storage (Story 3.13). A failing read is logged and shows no banner, then is
     * tried again after the missed note's growing pause.
     */
    private val reRegisterInputs: Flow<ReRegisterInputs> =
        reRegister
            .inputs()
            .retryWhen { cause, attempt ->
                actions.logFailure("load re-register suggestion", cause)
                emit(ReRegisterInputs())
                delay(missedRetryDelay(attempt))
                true
            }.onStart { emit(ReRegisterInputs()) }

    // The banner is computed with the alarms Home lists and on every tick, as its 7-day window moves with the clock.
    val state: StateFlow<HomeUiState> =
        combine(stored, ticks, local, missed, reRegisterInputs) { alarms, _, ui, missedRow, inputs ->
            val suggestion = reRegister.banner(alarms, inputs, uiTypeOf)
            shownSuggestion = suggestion
            render(alarms, ui, missedRow).copy(reregisterCheck = suggestion?.type?.let(uiTypeOf))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState(isLoading = true))

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
                // Nothing is stored until the editor saves (owner decision 2026-10-05).
                _effects.trySend(HomeEffect.OpenDuplicate(intent.id))
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
                showMessage(HomeMessage.OpenFailed)
            }

            // A permission dialog answered over Home only pauses it, so a resume checks the settings too.
            HomeIntent.Resumed -> {
                resumes.tryEmit(Unit)
                checkReliability()
            }

            is HomeIntent.MissedNoteDismissed -> {
                intent.sessionId?.let { sessionId -> dismiss("dismiss missed note") { missedNotes.dismiss(sessionId) } }
            }

            else -> {
                onReliabilityIntent(intent)
            }
        }
    }

    /**
     * The reliability banner (Story 1.19) and the re-register banner (Story 3.13); other intents (hero, session panel,
     * lock dialog) arrive with their stories.
     */
    private fun onReliabilityIntent(intent: HomeIntent) {
        when (intent) {
            // A setting may have changed while Home was away (the user came back from "Fix").
            HomeIntent.Started -> {
                checkReliability()
            }

            // The setting that is off now, not when Home last checked.
            HomeIntent.FixSettings -> {
                checkReliability().firstFailing?.let(reliabilitySettings::open)
            }

            // Story 3.10: QR registration of the alarm's code (another camera check: the alarm's editor, where its checks are).
            HomeIntent.ReregisterClicked -> {
                shownSuggestion?.let { shown ->
                    val effect =
                        if (shown.type == CoreCheckType.QrBarcode) {
                            HomeEffect.OpenQrRegistration(shown.alarmId)
                        } else {
                            HomeEffect.OpenEditor(shown.alarmId)
                        }
                    _effects.trySend(effect)
                }
            }

            HomeIntent.ReregisterDismissed -> {
                shownSuggestion?.let { shown -> dismiss("dismiss re-register banner") { reRegister.dismiss(shown) } }
            }

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
            // A newer toggle of the same alarm decides what the switch (and the snackbar) shows.
            val ownsSwitch = local.value.toggles[id]?.token == token
            local.update { ui ->
                // A newer toggle of the same alarm owns the switch now.
                val pending = ui.toggles[id]?.takeIf { it.token == token } ?: return@update ui
                when (result) {
                    is Outcome.Success -> ui.copy(toggles = ui.toggles + (id to pending.copy(confirmedAt = result.value.updatedAt)))

                    // The switch goes back to the stored value.
                    is Outcome.Failure -> ui.copy(toggles = ui.toggles - id)
                }
            }
            if (ownsSwitch && result is Outcome.Failure && result.error.isSaveFailure()) showMessage(HomeMessage.SaveFailed)
        }
    }

    private fun checkReliability(): ReliabilityStatus =
        reliability.check().also { status -> local.update { it.copy(reliability = status) } }

    private fun requestDelete(id: String) {
        val card = state.value.alarms.firstOrNull { it.id == id } ?: return
        local.update { it.copy(deleteDialog = DeleteAlarmDialog(card.id, card.time)) }
    }

    private fun confirmDelete() {
        // Cleared first, so a second tap on "Delete" cannot delete (and log) twice.
        val dialog = local.value.deleteDialog ?: return
        local.update { it.copy(deleteDialog = null) }
        viewModelScope.launch {
            // Gone already (deleted elsewhere) is what the user asked for.
            val error = actions.delete(dialog.alarmId).errorOrNull()
            if (error != null && error !is DomainError.NotFound && error.isSaveFailure()) showMessage(HomeMessage.SaveFailed)
        }
    }

    /** A session that started meanwhile refused the write (Story 2.6): the lock screen replaces Home, no message. */
    private fun DomainError.isSaveFailure(): Boolean = this != DomainError.SessionActive

    /**
     * Stores a dismissal of the missed note or the re-register banner, always the one the user saw (the note's session id
     * comes with the intent; the banner is [shownSuggestion]), never a newer one that arrived since. It hides once the
     * store has the dismissal; a failed [write] is logged as [what] and it stays.
     */
    private fun dismiss(
        what: String,
        write: suspend () -> Outcome<Unit, DomainError>,
    ) {
        viewModelScope.launch {
            val dismissed = write()
            if (dismissed is Outcome.Failure) actions.logFailure(what, dismissed.error)
        }
    }

    /** Shows the snackbar [message] for [SNACKBAR_MILLIS]; a newer message replaces the one showing. */
    private fun showMessage(message: HomeMessage) {
        local.update { it.copy(message = message) }
        snackbarJob?.cancel()
        snackbarJob =
            viewModelScope.launch {
                delay(SNACKBAR_MILLIS)
                local.update { it.copy(message = null) }
            }
    }

    private fun render(
        stored: StoredAlarms,
        ui: LocalState,
        missedRow: SessionHistoryRow?,
    ): HomeUiState {
        // The note names the alarm's own time ("Your 6:00 AM alarm"), in the zone the phone is in now.
        val missedAlarmAt = missedRow?.scheduledAt?.toLocalDateTime(timeZoneProvider.current())?.time
        val missedSessionId = missedRow?.sessionId
        return when (stored) {
            StoredAlarms.Failed -> {
                HomeUiState(
                    loadFailed = true,
                    openFailed = ui.message == HomeMessage.OpenFailed,
                    saveFailed = ui.message == HomeMessage.SaveFailed,
                    missedAlarmAt = missedAlarmAt,
                    missedSessionId = missedSessionId,
                    reliabilityProblem = !ui.reliability.allOk,
                )
            }

            is StoredAlarms.Loaded -> {
                val sorted = stored.alarms.sortedWith(compareBy(AlarmListOrder) { it.alarm })
                val alarms = sorted.map { it.alarm }
                val cards = sorted.map { it.alarm.toCard(ui.toggles[it.alarm.id], it.checks) }
                HomeUiState(
                    nextAlarm = nextAlarm(alarms.filterIndexed { index, _ -> cards[index].enabled }),
                    alarms = cards,
                    deleteDialog = ui.deleteDialog?.takeIf { dialog -> cards.any { it.id == dialog.alarmId } },
                    openFailed = ui.message == HomeMessage.OpenFailed,
                    saveFailed = ui.message == HomeMessage.SaveFailed,
                    missedAlarmAt = missedAlarmAt,
                    missedSessionId = missedSessionId,
                    reliabilityProblem = !ui.reliability.allOk,
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

        /** How long "Couldn't open this alarm." or "Couldn't save the alarm. Try again." shows (`snackbar`). */
        const val SNACKBAR_MILLIS = 4_000L
    }
}

private val MISSED_RETRY_FIRST: Duration = 1.seconds
private val MISSED_RETRY_MAX: Duration = 1.minutes
private const val MISSED_RETRY_MAX_DOUBLINGS = 6L

/** The pause after failed missed-note read [attempt] (0 first): 1 s, doubling, at most a minute. */
internal fun missedRetryDelay(attempt: Long): Duration {
    val doublings = attempt.coerceIn(0L, MISSED_RETRY_MAX_DOUBLINGS).toInt()
    return (MISSED_RETRY_FIRST * (1 shl doublings)).coerceAtMost(MISSED_RETRY_MAX)
}

/**
 * The suggestion the banner shows: none while the alarms cannot be read. A check type without a screen ([uiTypeOf]) is
 * left out before the rule picks one, so it never hides another alarm's suggestion.
 */
private fun ReRegisterSuggestions.banner(
    stored: StoredAlarms,
    inputs: ReRegisterInputs,
    uiTypeOf: (CoreCheckType) -> UiCheckType?,
): ReRegisterSuggestion? =
    (stored as? StoredAlarms.Loaded)?.let { loaded ->
        suggestion(inputs.copy(checkConfigs = inputs.checkConfigs.filter { uiTypeOf(it.type) != null }), loaded.alarms.map { it.alarm })
    }

/** What the repository gave: the alarms, or a read failure. */
private sealed interface StoredAlarms {
    data class Loaded(
        /** Each alarm with its checks (Story 3.5). */
        val alarms: List<AlarmWithChecks>,
    ) : StoredAlarms

    data object Failed : StoredAlarms
}

/** A switch the user moved: [enabled] shows until the store has an update at least as new as [confirmedAt]. */
private data class PendingToggle(
    val enabled: Boolean,
    val token: Long,
    val confirmedAt: Instant? = null,
)

/** Home's snackbars; one shows at a time. */
private enum class HomeMessage {
    /** The editor could not read its alarm: "Couldn't open this alarm.". */
    OpenFailed,

    /** A switch, Duplicate or Delete could not be stored: "Couldn't save the alarm. Try again.". */
    SaveFailed,
}

/** Home state that is not in the store. */
private data class LocalState(
    val toggles: Map<String, PendingToggle> = emptyMap(),
    val deleteDialog: DeleteAlarmDialog? = null,
    /** The snackbar showing, if any. */
    val message: HomeMessage? = null,
    /** What the reliability probe found when Home last started (Story 1.19). */
    val reliability: ReliabilityStatus = ReliabilityStatus.ALL_OK,
)

/**
 * The card of this alarm, with its [checks]' icons in order (Story 3.5; a check the app cannot show has no icon). An
 * alarm without check rows rings the default checks, so its card shows theirs (review fix: the same default as the
 * editor, Save, Duplicate and the ring).
 */
private fun Alarm.toCard(
    pending: PendingToggle?,
    checks: List<CheckConfig>,
): AlarmCard {
    val confirmed = pending?.confirmedAt
    val shown = if (pending == null || (confirmed != null && updatedAt >= confirmed)) enabled else pending.enabled
    val icons = checks.orderedEntries().ifEmpty { CheckConfig.DEFAULT_ENTRIES }.mapNotNull { it.type.toUi() }
    return AlarmCard(id = id, time = time, repeatDays = repeatDays, label = label, checks = icons, enabled = shown)
}
