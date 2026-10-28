package com.yawnandpawn.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmListOrder
import com.yawnandpawn.app.core.alarm.AlarmWithChecks
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.alarm.SkipResult
import com.yawnandpawn.app.core.alarm.durationUntil
import com.yawnandpawn.app.core.alarm.isNextOccurrenceSkipped
import com.yawnandpawn.app.core.alarm.nextRing
import com.yawnandpawn.app.core.alarm.orderedEntries
import com.yawnandpawn.app.core.config.LockWindow
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.errorOrNull
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.onboarding.OnboardingTestNote
import com.yawnandpawn.app.core.reliability.EvaluateReliability
import com.yawnandpawn.app.core.stats.ObserveProgress
import com.yawnandpawn.app.core.stats.ProgressSnapshot
import com.yawnandpawn.app.core.stats.ReRegisterInputs
import com.yawnandpawn.app.core.stats.ReRegisterSuggestion
import com.yawnandpawn.app.core.stats.ReRegisterSuggestions
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.checks.toUi
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.countdownOf
import com.yawnandpawn.app.ui.reliability.AlarmBlockingCheck
import com.yawnandpawn.app.ui.settings.WeakeningNote
import com.yawnandpawn.app.ui.wake.uiCheckType
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.CoroutineContext
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
 * reliability banner shows while [reliability]'s checklist has a blocking row that is not OK
 * (`ReliabilityChecklist.alarmBlocking`, Story 5.4); it is evaluated again in [evaluationContext], without waiting, each
 * time Home resumes (once per start, and after a permission dialog, which only pauses it), and "Fix" opens the
 * reliability checklist. The re-register banner (Story 3.13)
 * shows [reRegister]'s suggestion for the listed alarms,
 * again on every tick (the 7-day window moves), named by [uiTypeOf]; "Re-register" opens that alarm's editor until QR
 * registration arrives (Story 3.10), "Dismiss" stores the dismissal. After "Skip for now" on onboarding's test alarm,
 * [testNote] shows "Ring a test alarm with your phone locked to check it works." until a locked test passes or "Dismiss"
 * is stored (Story 5.12); a failing read is logged and shows no note, a failed dismissal is logged and the note stays.
 * `card-hero` (Story 6.3) shows [progress]'s current streak and this week's money once Home has an alarm or a history
 * row; the snapshot is recomputed on either table, a new local date or zone (a time signal) and each resume. While it
 * loads, or after a failed read (logged, then read again with the missed note's growing pause), there is no hero.
 *
 * Story 7.11: the countdown targets each alarm's next ring (`nextRing`: a skipped occurrence is passed over), and a card
 * whose next occurrence is skipped says "Next ring skipped" with "Don't skip" (which undoes the skip). A pre-alarm
 * notification's "Skip this one" arrives as [HomeIntent.SkipRequested]: "Skip your {time} alarm? This is logged." for an
 * enabled alarm, then "Skip" skips it and shows "Skipped. No charge."; a skip that cannot be stored is logged, with no
 * snackbar.
 */
@Suppress("TooManyFunctions") // One small handler per Home action (toggle, turn-off dialog, delete, banners, snackbars).
class HomeViewModel(
    /** The stored alarms with their checks (the card's check icons, Story 3.5), from one read of both tables. */
    private val checkConfigs: CheckConfigRepository,
    private val actions: AlarmActions,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    timeChanges: TimeChangeSignal,
    private val missedNotes: MissedNotes,
    reliability: EvaluateReliability,
    private val reRegister: ReRegisterSuggestions,
    /** The check the banner names; a type without a UI (the placeholder) shows no banner. */
    private val uiTypeOf: (CoreCheckType) -> UiCheckType? = ::uiCheckType,
    /** Where the banner's evaluation runs (system service calls): off the main thread in the app, the test's in tests. */
    evaluationContext: CoroutineContext = Dispatchers.Default,
    /** Onboarding's skipped-test note (Story 5.12); the app binds it (`UiModule`), null shows none. */
    private val testNote: OnboardingTestNote? = null,
    /** The stats behind `card-hero` (Story 6.3, core.stats); the app binds it (`UiModule`), null shows no hero. */
    private val progress: ObserveProgress? = null,
) : ViewModel() {
    /** Bumped by "Try again" to subscribe to the alarms again. */
    private val loads = MutableStateFlow(0)
    private val resumes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val local = MutableStateFlow(LocalState())
    private val reliabilityBanner =
        AlarmBlockingCheck(reliability, viewModelScope, evaluationContext) { blocking ->
            local.update { it.copy(reliabilityProblem = blocking) }
        }
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

    /** The alarms the cards show, as stored: the turn-off dialog reads the switched alarm's next ring from them. */
    private var listed: List<Alarm> = emptyList()

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

    /**
     * The stats behind the hero (Story 6.3): null until the first snapshot and after a failed read, which is logged and
     * read again after the missed note's growing pause. Home's resumes are its refresh, so a date that changed while
     * Home was away is caught even before the next time signal. Read only while [state] is collected; the last snapshot
     * is kept while Home is away, so coming back does not hide the hero and show it again (review fix 3).
     */
    private val snapshot: StateFlow<ProgressSnapshot?> =
        (
            progress?.let { observe ->
                observe(refresh = resumes)
                    .map<ProgressSnapshot, ProgressSnapshot?> { it }
                    .retryWhen { cause, attempt ->
                        actions.logFailure("load progress", cause)
                        emit(null)
                        delay(missedRetryDelay(attempt))
                        true
                    }
            } ?: emptyFlow()
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    // The banner is computed with the alarms Home lists and on every tick, as its 7-day window moves with the clock. The
    // hero is joined outside that combine (5 flows is the largest typed overload), only while Home is shown.
    val state: StateFlow<HomeUiState> =
        combine(
            combine(stored, ticks, local, missed, reRegisterInputs) { alarms, _, ui, missedRow, inputs ->
                val suggestion = reRegister.banner(alarms, inputs, uiTypeOf)
                shownSuggestion = suggestion
                render(alarms, ui, missedRow).copy(reregisterCheck = suggestion?.type?.let(uiTypeOf))
            },
            snapshot,
        ) { home, stats -> home.withHero(stats) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState(isLoading = true))

    init {
        testNote?.let { note ->
            viewModelScope.launch {
                note
                    .shown()
                    .catch { cause ->
                        actions.logFailure("load test note", cause)
                        emit(false)
                    }.collect { shown -> local.update { it.copy(testNote = shown) } }
            }
        }
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.AddAlarm -> {
                _effects.trySend(HomeEffect.OpenEditor(null))
            }

            is HomeIntent.EditAlarm -> {
                _effects.trySend(HomeEffect.OpenEditor(intent.id))
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
                local.update { it.copy(deleteDialog = null, disableDialog = null, skipDialog = null) }
                loads.update { it + 1 }
            }

            HomeIntent.EditorOpenFailed -> {
                showMessage(HomeMessage.OpenFailed)
            }

            // Every start ends in a resume, and a permission dialog answered over Home only pauses it: one evaluation each.
            HomeIntent.Resumed -> {
                resumes.tryEmit(Unit)
                reliabilityBanner.refresh()
            }

            is HomeIntent.MissedNoteDismissed -> {
                intent.sessionId?.let { sessionId -> dismiss("dismiss missed note") { missedNotes.dismiss(sessionId) } }
            }

            else -> {
                if (!onLockIntent(intent)) onReliabilityIntent(intent)
            }
        }
    }

    /** "Skip this one" and "Don't skip" (Story 7.11); false for any other intent. */
    private fun onSkipIntent(intent: HomeIntent): Boolean {
        when (intent) {
            is HomeIntent.SkipRequested -> {
                requestSkip(intent.id)
            }

            // Cleared first, so a second tap on "Skip" cannot skip twice.
            HomeIntent.SkipConfirmed -> {
                local.value.skipDialog?.let { dialog ->
                    local.update { it.copy(skipDialog = null) }
                    skip(dialog.alarmId)
                }
            }

            // "Keep it", Back or a tap outside: nothing is skipped.
            HomeIntent.SkipCancelled -> {
                local.update { it.copy(skipDialog = null) }
            }

            is HomeIntent.DontSkipClicked -> {
                viewModelScope.launch {
                    val result = actions.undoSkip(intent.id)
                    if (result is Outcome.Failure && result.error.isSaveFailure()) showMessage(HomeMessage.SaveFailed)
                }
            }

            else -> {
                return false
            }
        }
        return true
    }

    /**
     * Asks "Skip your {time} alarm? This is logged." for the alarm [id] when it is on; an alarm the cards do not hold yet
     * is read from the store first. One that is off, gone or unreadable is left as it is (nothing to skip).
     */
    private fun requestSkip(id: String) {
        val known = listed.firstOrNull { it.id == id }
        if (known != null) {
            askSkip(known)
            return
        }
        viewModelScope.launch {
            checkConfigs
                .observeAlarmsWithChecks()
                .map<List<AlarmWithChecks>, Alarm?> { all -> all.firstOrNull { it.alarm.id == id }?.alarm }
                .catch { cause ->
                    actions.logFailure("load alarm to skip", cause)
                    emit(null)
                }.first()
                ?.let(::askSkip)
        }
    }

    private fun askSkip(alarm: Alarm) {
        if (alarm.enabled) local.update { it.copy(skipDialog = SkipDialog(alarm.id, alarm.time)) }
    }

    /** "Skip": the next ring of [id] is skipped and logged; "Skipped. No charge." once it is (or already was). */
    private fun skip(id: String) {
        viewModelScope.launch {
            val result = actions.skipNext(id)
            if (result is Outcome.Success && result.value != SkipResult.NothingToSkip) showMessage(HomeMessage.Skipped)
        }
    }

    /**
     * The switch and the commitment lock on Home (Story 4.6): switching off asks first inside the lock window, "Turn
     * off" or "Keep it on" in the turn-off dialog, and the editor's
     * "Saved. Takes effect after ..." snackbar; false for any other intent.
     */
    private fun onLockIntent(intent: HomeIntent): Boolean {
        when (intent) {
            is HomeIntent.AlarmToggled -> {
                if (intent.enabled) toggle(intent.id, enabled = true) else requestTurnOff(intent.id)
            }

            // Cleared first, so a second tap on "Turn off" cannot write twice.
            HomeIntent.DisableConfirmed -> {
                local.value.disableDialog?.let { dialog ->
                    local.update { it.copy(disableDialog = null) }
                    toggle(dialog.alarmId, enabled = false)
                }
            }

            // "Keep it on", Back or a tap outside: nothing is written, the switch stays on.
            HomeIntent.DisableCancelled -> {
                local.update { it.copy(disableDialog = null) }
            }

            is HomeIntent.SavedUnderLock -> {
                showMessage(HomeMessage.SavedUnderLock(intent.note))
            }

            // Story 7.11: "Skip this one" and "Don't skip".
            else -> {
                return onSkipIntent(intent)
            }
        }
        return true
    }

    /**
     * The reliability banner (Story 5.4) and the re-register banner (Story 3.13); other intents (hero, session panel,
     * lock dialog) arrive with their stories.
     */
    private fun onReliabilityIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.FixSettings -> {
                _effects.trySend(HomeEffect.OpenReliability)
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

            // Story 5.12: it hides once the store has the dismissal.
            HomeIntent.TestNoteDismissed -> {
                testNote?.let { note -> dismiss("dismiss test note") { note.dismiss() } }
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

    /**
     * Switching [id] off: inside its lock window (an enabled alarm ringing within 8 h, PRD §6.2) the turn-off dialog asks
     * first, with how long until it rings (whole hours rounded down, or minutes under 1 h); otherwise it turns off at once
     * (Story 1.9). The card shows on, so the alarm is taken as on even while turning it on is still being stored. An
     * alarm the cards do not hold yet is read from the store first (review fix 4); one the store does not have (or cannot
     * read) is left as it is, never turned off without the dialog.
     */
    private fun requestTurnOff(id: String) {
        val known = listed.firstOrNull { it.id == id }
        if (known != null) {
            turnOffAsking(known)
            return
        }
        viewModelScope.launch {
            checkConfigs
                .observeAlarmsWithChecks()
                .map<List<AlarmWithChecks>, Alarm?> { all -> all.firstOrNull { it.alarm.id == id }?.alarm }
                .catch { cause ->
                    actions.logFailure("load alarm to turn off", cause)
                    emit(null)
                }.first()
                ?.let(::turnOffAsking)
        }
    }

    /** [alarm] off: the turn-off dialog inside its lock window, otherwise at once. */
    private fun turnOffAsking(alarm: Alarm) {
        val now = clock.now()
        val occurrence = LockWindow.of(alarm.copy(enabled = true), now, timeZoneProvider.current())
        if (occurrence == null) {
            toggle(alarm.id, enabled = false)
        } else {
            local.update { it.copy(disableDialog = DisableUnderLock(alarm.id, alarm.time, lockCountdownOf(occurrence.scheduledAt - now))) }
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
                    skippedNoCharge = ui.message == HomeMessage.Skipped,
                    savedUnderLock = (ui.message as? HomeMessage.SavedUnderLock)?.note,
                    missedAlarmAt = missedAlarmAt,
                    missedSessionId = missedSessionId,
                    reliabilityProblem = ui.reliabilityProblem,
                    testSkipped = ui.testNote,
                )
            }

            is StoredAlarms.Loaded -> {
                val sorted = stored.alarms.sortedWith(compareBy(AlarmListOrder) { it.alarm })
                val alarms = sorted.map { it.alarm }
                listed = alarms
                val now = clock.now()
                val zone = timeZoneProvider.current()
                val cards = sorted.map { it.alarm.toCard(ui.toggles[it.alarm.id], it.checks, now, zone) }
                HomeUiState(
                    nextAlarm = nextAlarm(alarms.filterIndexed { index, _ -> cards[index].enabled }),
                    alarms = cards,
                    deleteDialog = ui.deleteDialog?.takeIf { dialog -> cards.any { it.id == dialog.alarmId } },
                    disableDialog = ui.disableDialog?.takeIf { dialog -> cards.any { it.id == dialog.alarmId } },
                    skipDialog = ui.skipDialog?.takeIf { dialog -> cards.any { it.id == dialog.alarmId && it.enabled } },
                    openFailed = ui.message == HomeMessage.OpenFailed,
                    saveFailed = ui.message == HomeMessage.SaveFailed,
                    skippedNoCharge = ui.message == HomeMessage.Skipped,
                    savedUnderLock = (ui.message as? HomeMessage.SavedUnderLock)?.note,
                    missedAlarmAt = missedAlarmAt,
                    missedSessionId = missedSessionId,
                    reliabilityProblem = ui.reliabilityProblem,
                    testSkipped = ui.testNote,
                )
            }
        }
    }

    /**
     * The countdown to the soonest ring of [enabled], as the scheduler computes it (a skipped occurrence is passed over,
     * Story 7.11); `null` when none is enabled.
     */
    private fun nextAlarm(enabled: List<Alarm>): Countdown? {
        val now = clock.now()
        val zone = timeZoneProvider.current()
        return enabled
            .minOfOrNull { nextRing(it, now, zone) }
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

/**
 * How long until a ring inside the lock window, as the turn-off dialog says it (Story 4.6, EXPERIENCE.md Key strings):
 * whole hours rounded down ("It rings in 7 h" at 7 h 59 min), or under 1 h whole minutes rounded down, at least 1 ("It
 * rings in 1 min" at 30 s).
 */
internal fun lockCountdownOf(untilRing: Duration): Countdown {
    val minutes = untilRing.inWholeMinutes.coerceAtLeast(1)
    return if (minutes < MINUTES_PER_HOUR) {
        Countdown.Minutes(minutes.toInt())
    } else {
        Countdown.HoursMinutes((minutes / MINUTES_PER_HOUR).toInt(), (minutes % MINUTES_PER_HOUR).toInt())
    }
}

private const val MINUTES_PER_HOUR = 60L

/** The pause after failed missed-note read [attempt] (0 first): 1 s, doubling, at most a minute. */
internal fun missedRetryDelay(attempt: Long): Duration {
    val doublings = attempt.coerceIn(0L, MISSED_RETRY_MAX_DOUBLINGS).toInt()
    return (MISSED_RETRY_FIRST * (1 shl doublings)).coerceAtMost(MISSED_RETRY_MAX)
}

/**
 * `card-hero` from [stats] (Story 6.3): the current streak and this week's money, once Home has at least one alarm or one
 * history row. None while the stats or the alarms are loading, or when either could not be read (never a guessed 0).
 */
internal fun HomeUiState.withHero(stats: ProgressSnapshot?): HomeUiState {
    val hero =
        stats
            ?.takeIf { !isLoading && !loadFailed && (alarms.isNotEmpty() || it.hasHistory) }
            ?.let { HomeHero(it.currentStreak, it.paidThisWeek) }
    return copy(hero = hero)
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
private sealed interface HomeMessage {
    /** The editor could not read its alarm: "Couldn't open this alarm.". */
    data object OpenFailed : HomeMessage

    /** A switch, Duplicate or Delete could not be stored: "Couldn't save the alarm. Try again.". */
    data object SaveFailed : HomeMessage

    /** The editor saved a weakening change under the lock (Story 4.6): "Saved. Takes effect after ...". */
    data class SavedUnderLock(
        val note: WeakeningNote,
    ) : HomeMessage

    /** The next ring was skipped (Story 7.11): "Skipped. No charge.". */
    data object Skipped : HomeMessage
}

/** Home state that is not in the store. */
private data class LocalState(
    val toggles: Map<String, PendingToggle> = emptyMap(),
    val deleteDialog: DeleteAlarmDialog? = null,
    /** "Turn off your {time} alarm? ..." for an alarm switched off inside its lock window (Story 4.6). */
    val disableDialog: DisableUnderLock? = null,
    /** "Skip your {time} alarm? This is logged." after a pre-alarm notification's "Skip this one" (Story 7.11). */
    val skipDialog: SkipDialog? = null,
    /** The snackbar showing, if any. */
    val message: HomeMessage? = null,
    /** The last checklist evaluation found a blocking row that is not OK (Story 5.4); false until the first one ends. */
    val reliabilityProblem: Boolean = false,
    /** Onboarding's test alarm was skipped and no locked test has passed since (Story 5.12); false until read. */
    val testNote: Boolean = false,
)

/**
 * The card of this alarm, with its [checks]' icons in order (Story 3.5; a check the app cannot show has no icon). An
 * alarm without check rows rings the default checks, so its card shows theirs (review fix: the same default as the
 * editor, Save, Duplicate and the ring). It is "skipped" while it shows on and its next occurrence after [now] (in
 * [zone]) is the skipped one (Story 7.11).
 */
private fun Alarm.toCard(
    pending: PendingToggle?,
    checks: List<CheckConfig>,
    now: Instant,
    zone: TimeZone,
): AlarmCard {
    val confirmed = pending?.confirmedAt
    val shown = if (pending == null || (confirmed != null && updatedAt >= confirmed)) enabled else pending.enabled
    val icons = checks.orderedEntries().ifEmpty { CheckConfig.DEFAULT_ENTRIES }.mapNotNull { it.type.toUi() }
    return AlarmCard(
        id = id,
        time = time,
        repeatDays = repeatDays,
        label = label,
        checks = icons,
        enabled = shown,
        skipped = shown && isNextOccurrenceSkipped(now, zone),
    )
}
