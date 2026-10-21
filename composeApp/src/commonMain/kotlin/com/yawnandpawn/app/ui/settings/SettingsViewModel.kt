package com.yawnandpawn.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.billing.FeeRules
import com.yawnandpawn.app.core.billing.MoneyFormatter
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.config.DefaultSetting
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.LockedField
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.config.SaveDefaultSetting
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SetDefaultGrace
import com.yawnandpawn.app.core.config.SetDefaultSnoozeLength
import com.yawnandpawn.app.core.config.SetDefaultVibrateInGrace
import com.yawnandpawn.app.core.config.SetMaxSnoozes
import com.yawnandpawn.app.core.config.SetThemeMode
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.config.with
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.EvaluateReliability
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.core.telemetry.ConsentStore
import com.yawnandpawn.app.core.telemetry.SetAnalyticsConsent
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.reliability.AlarmBlockingCheck
import com.yawnandpawn.app.ui.theme.toCore
import com.yawnandpawn.app.ui.theme.toPps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The Story 5.1 setters of Settings: the new-alarm defaults and the theme, each through its own core use case (no
 * commitment lock).
 */
class DefaultSettingSetters(
    private val setDefaultGrace: SetDefaultGrace,
    private val setDefaultVibrateInGrace: SetDefaultVibrateInGrace,
    private val setDefaultSnoozeLength: SetDefaultSnoozeLength,
    private val setThemeMode: SetThemeMode,
) {
    /** The four setters over one [save]. */
    constructor(save: SaveDefaultSetting) : this(
        SetDefaultGrace(save),
        SetDefaultVibrateInGrace(save),
        SetDefaultSnoozeLength(save),
        SetThemeMode(save),
    )

    suspend fun save(value: DefaultSetting): Outcome<Unit, DomainError> =
        when (value) {
            is DefaultSetting.GraceSeconds -> setDefaultGrace(value.seconds)
            is DefaultSetting.VibrateInGrace -> setDefaultVibrateInGrace(value.on)
            is DefaultSetting.SnoozeLength -> setDefaultSnoozeLength(value.minutes)
            is DefaultSetting.Theme -> setThemeMode(value.mode)
        }
}

/**
 * Settings: the Snooze card's base fee and max snoozes per session, set through the commitment lock (Story 4.5), and the
 * new-alarm defaults and the theme, applied at once (Story 5.1).
 *
 * - **Shown values:** what the user chose, which is the global pending change from [pendingChanges] when one waits,
 *   else the live value from [globalSettings]. While a pending change waits for an occurrence still to come, its
 *   sub-screen shows "Saved. Takes effect after tomorrow's (today's) {time} alarm." ([weakeningNoteOf]). The note is
 *   re-checked on every [timeChanges] tick, so it goes once that alarm has rung.
 * - **Prices:** the cached Play prices from [priceCatalog], or USD approximations by [moneyFormatter] when a tier is
 *   missing ([SnoozePrices]). Nothing waits for a refresh, so the screen works offline.
 * - **Saving:** each step, switch, radio or slider value calls its use case ([setBaseFee], [setMaxSnoozes], or one of
 *   [defaults]) through one queue, in order, so a long-press repeat or a slider drag never races itself. The control
 *   moves at once to the requested value (the requests overlay the stored values). A saved request is let go only by the
 *   same render that sees the store hold it (review fix 1), so the control never shows the old value in between and the
 *   next step starts from the new one. A failed save is logged and the stored value comes back, unless a newer request
 *   replaced it. A value outside its range, or one already shown, is not saved, and a default superseded in the queue
 *   by a newer value of its field is skipped (a slider drag saves its first and last steps, not every one).
 *
 * Usage stats (Story 5.6): the switch shows the stored choice from [consents]; a tap stores it at once through
 * [setAnalyticsConsent] (no Save). A failed write is logged and the switch stays at the stored value; an unreadable
 * choice is logged and shows off.
 *
 * The reliability banner (Story 5.4) follows the checklist's `alarmBlocking`, evaluated on every start; "Fix" and the
 * "Reliability checklist" row open the checklist through [effects].
 *
 * [state] is null until the stored settings are read, so no default value flashes. When the ViewModel is cleared, the
 * base fee and max snoozes saves still queued are logged as dropped, and the defaults and the theme are finished in
 * [outlivingScope] ([onCleared]).
 */
class SettingsViewModel(
    globalSettings: GlobalSettingsRepository,
    pendingChanges: PendingChangeRepository,
    priceCatalog: PriceCatalog,
    private val setBaseFee: SetBaseFee,
    private val setMaxSnoozes: SetMaxSnoozes,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    timeChanges: TimeChangeSignal,
    private val moneyFormatter: MoneyFormatter,
    private val logger: Logger,
    private val defaults: DefaultSettingSetters,
    consents: ConsentStore,
    private val setAnalyticsConsent: SetAnalyticsConsent,
    /**
     * Where the latest default or theme still waiting when the screen closes is saved (the app's scope, review fix):
     * leaving the tab right after a change never loses it. Null (tests that never close mid-save) drops it, logged.
     */
    private val outlivingScope: CoroutineScope? = null,
    /**
     * The reliability checklist behind the banner (Story 5.4), evaluated on every [SettingsIntent.Started]; null (tests
     * of the other rows) shows no banner.
     */
    reliability: EvaluateReliability? = null,
    /** Where the banner's evaluation runs (system service calls): off the main thread in the app, the test's in tests. */
    evaluationContext: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val saves = Channel<Save>(Channel.UNLIMITED)

    private val banner: AlarmBlockingCheck? =
        reliability?.let { evaluate ->
            AlarmBlockingCheck(evaluate, viewModelScope, evaluationContext) { blocking ->
                local.update { it.copy(reliabilityProblem = blocking) }
            }
        }

    private val mutableEffects = Channel<SettingsEffect>(Channel.BUFFERED)

    /** Navigation out of Settings: the reliability checklist (Story 5.4). */
    val effects: Flow<SettingsEffect> = mutableEffects.receiveAsFlow()

    /** The save running now, if any (logged as dropped when the ViewModel is cleared under it). */
    private var saving: Save? = null

    /** Numbers the default requests, so a save settles only its own request even when a toggle comes back to a value. */
    private var nextRequest = 0L

    /** The stored settings and their global pending changes; an unreadable store is logged and reads as the defaults. */
    private val stored: Flow<Stored> =
        combine(
            globalSettings.observe().catch { cause ->
                logger.logFailure(LOAD_SETTINGS, cause)
                emit(GlobalSettings())
            },
            pendingChanges.observe().catch { cause ->
                logger.logFailure(LOAD_PENDING, cause)
                emit(emptyList())
            },
        ) { settings, pending -> Stored(settings, pending.filter { it.alarmId == null }) }

    private val ticks: Flow<Unit> = timeChanges.changes().onStart { emit(Unit) }

    /** The stored usage stats choice; an unreadable store is logged and reads as off. */
    private val consent: Flow<Boolean> =
        consents
            .observe()
            .catch { cause ->
                logger.logFailure(LOAD_CONSENT, cause)
                emit(AnalyticsConsent())
            }.map { it.consent }

    val state: StateFlow<SettingsUiState?> =
        combine(stored, priceCatalog.observe(), ticks, local, consent) { saved, prices, _, ui, usageStats ->
            forgetEchoed(saved, ui)
            render(saved, prices, ui).copy(usageStats = usageStats)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** The save queue, run in order. */
    private val loop: Job =
        viewModelScope.launch {
            for (save in saves) {
                saving = save
                save(save)
                saving = null
            }
        }

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.OpenPane -> {
                local.update { it.copy(pane = intent.pane) }
            }

            SettingsIntent.Back -> {
                local.update { it.copy(pane = SettingsPane.Main) }
            }

            SettingsIntent.LowerBaseFee -> {
                currentTier()?.let { request(SettingValue.BaseFeeTier(it - 1)) }
            }

            SettingsIntent.RaiseBaseFee -> {
                currentTier()?.let { request(SettingValue.BaseFeeTier(it + 1)) }
            }

            is SettingsIntent.MaxSnoozesChanged -> {
                request(SettingValue.MaxSnoozes(intent.value))
            }

            is SettingsIntent.SnoozeLengthSelected -> {
                request(DefaultSetting.SnoozeLength(intent.minutes))
            }

            is SettingsIntent.QuietTimeChanged -> {
                request(DefaultSetting.GraceSeconds(intent.seconds))
            }

            is SettingsIntent.VibrateDuringQuietTimeToggled -> {
                request(DefaultSetting.VibrateInGrace(intent.on))
            }

            is SettingsIntent.ThemeSelected -> {
                request(DefaultSetting.Theme(intent.mode.toCore()))
            }

            is SettingsIntent.UsageStatsToggled, SettingsIntent.Started, SettingsIntent.FixSettings, SettingsIntent.ReliabilityClicked -> {
                onGeneralIntent(intent)
            }

            // The other rows belong to later Epic 5 stories; production does not show them.
            else -> {
                Unit
            }
        }
    }

    /**
     * The usage stats switch (Story 5.6) and the reliability checklist (Story 5.4): a start evaluates the checklist again
     * (back from it, too); "Fix" and its row open it.
     */
    private fun onGeneralIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.UsageStatsToggled -> setUsageStats(intent.on)
            SettingsIntent.Started -> banner?.refresh()
            else -> mutableEffects.trySend(SettingsEffect.OpenReliability)
        }
    }

    /**
     * The screen closes (a tab switch clears it). A base fee or max snoozes save still queued or running is dropped and
     * logged (Story 4.5). A default or theme save is not lost (review fix): the running one finishes ([NonCancellable]),
     * and the latest queued value of each field is saved in [outlivingScope] after it, in order.
     */
    override fun onCleared() {
        val queued = generateSequence { saves.tryReceive().getOrNull() }.toList()
        saves.close()
        val lostLocked = (listOfNotNull(saving) + queued).filterIsInstance<Save.Locked>()
        if (lostLocked.isNotEmpty()) logger.log(LogEvent.OperationFailed(SAVE_SETTING, "dropped ${lostLocked.size} on close"))
        val latest = queued.filterIsInstance<Save.Default>().filter { local.value.holds(it.request) }.map { it.request.value }
        if (latest.isEmpty()) return
        val scope = outlivingScope
        if (scope == null) {
            logger.log(LogEvent.OperationFailed(SAVE_DEFAULT, "dropped ${latest.size} on close"))
        } else {
            scope.launch {
                loop.join()
                latest.forEach { value -> logger.logDefaultFailure(defaults.save(value)) }
            }
        }
    }

    /** Stores the usage stats choice at once; the switch follows the store, so a failed write leaves it as stored. */
    private fun setUsageStats(on: Boolean) {
        viewModelScope.launch {
            val result = setAnalyticsConsent(on)
            if (result is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(SAVE_CONSENT, result.error))
        }
    }

    /**
     * The tier a step starts from: the last one requested (set at once, so taps faster than a frame still add up), else
     * the one shown; null before the store was read.
     */
    private fun currentTier(): Int? = local.value.fee?.value ?: state.value?.baseFeeTier

    /** Shows [value] at once and queues its save; a value outside its range is ignored (the stepper end is disabled). */
    private fun request(value: SettingValue) {
        val shown: (Local) -> Local =
            when {
                value is SettingValue.BaseFeeTier && value.tier in FeeRules.BASE_FEE_TIERS -> { ui -> ui.copy(fee = Request(value.tier)) }
                value is SettingValue.MaxSnoozes && value.count in FeeRules.MAX_SNOOZES -> { ui -> ui.copy(max = Request(value.count)) }
                else -> return
            }
        local.update(shown)
        saves.trySend(Save.Locked(value))
    }

    /**
     * Shows the default [value] at once and queues its save. A value outside its range (the controls never offer one)
     * or the value already shown (a slider drag repeats it every frame) is not saved.
     */
    private fun request(value: DefaultSetting) {
        if (!value.isValid() || shows(value)) return
        val request = DefaultRequest(value, nextRequest++)
        local.update { ui -> ui.copy(defaults = ui.defaults.filterNot { it.sameField(value) } + request) }
        saves.trySend(Save.Default(request))
    }

    /** Whether [value] is what the screen shows now: the latest request of its field, else the stored value. */
    private fun shows(value: DefaultSetting): Boolean {
        val requested = local.value.defaults.firstOrNull { it.sameField(value) }
        val shown = state.value
        return when {
            requested != null -> requested.value == value
            shown == null -> false
            else -> shown.shows(value)
        }
    }

    private suspend fun save(save: Save) {
        when (save) {
            is Save.Locked -> {
                val value = save.value
                val result =
                    when (value) {
                        is SettingValue.BaseFeeTier -> setBaseFee(value.tier)
                        is SettingValue.MaxSnoozes -> setMaxSnoozes(value.count)
                        is SettingValue.GraceSeconds, is SettingValue.Checks -> return
                    }
                if (result is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(SAVE_SETTING, result.error))
                local.update { it.settled(value, saved = result is Outcome.Success) }
            }

            is Save.Default -> {
                // A newer value of the same field is queued behind it: only that one is saved.
                if (!local.value.holds(save.request)) return
                // Finishes even when the screen closes meanwhile, so a value the user saw applied is stored.
                val result = withContext(NonCancellable) { defaults.save(save.request.value) }
                logger.logDefaultFailure(result)
                local.update { it.settled(save.request, saved = result is Outcome.Success) }
            }
        }
    }

    /**
     * Lets go of a saved request once the store holds it, in the same pass that renders the store's value, so the two
     * never disagree on screen. A request still being saved, or one the store does not show yet, stays.
     */
    private fun forgetEchoed(
        saved: Stored,
        ui: Local,
    ) {
        val feeEchoed = ui.fee?.takeIf { it.saved && saved.tier == it.value }
        val maxEchoed = ui.max?.takeIf { it.saved && saved.maxSnoozes == it.value }
        val defaultsEchoed = ui.defaults.filter { it.saved && saved.settings.with(it.value) == saved.settings }
        if (feeEchoed != null || maxEchoed != null || defaultsEchoed.isNotEmpty()) {
            // Only the very request that was seen: a newer one made meanwhile stays.
            local.update {
                it.copy(
                    fee = it.fee.takeUnless { request -> request == feeEchoed },
                    max = it.max.takeUnless { request -> request == maxEchoed },
                    defaults = it.defaults - defaultsEchoed.toSet(),
                )
            }
        }
    }

    private fun render(
        saved: Stored,
        prices: PriceCatalogSnapshot,
        ui: Local,
    ): SettingsUiState {
        val now = clock.now()
        val zone = timeZoneProvider.current()
        val tier = ui.fee?.value ?: saved.tier
        val max = ui.max?.value ?: saved.maxSnoozes
        val fees = SnoozePrices.of(tier, max, prices, now, moneyFormatter)
        val chosen = ui.defaults.fold(saved.settings) { settings, request -> settings.with(request.value) }
        return SettingsUiState(
            baseFee = fees.baseFee,
            baseFeeTier = tier,
            feeLadder = fees.ladder,
            pricesApproximate = fees.approximate,
            maxSnoozes = max,
            defaultSnoozeMinutes = chosen.defaultSnoozeLengthMinutes,
            defaultQuietSeconds = chosen.defaultGraceSeconds,
            vibrateDuringQuietTime = chosen.defaultVibrateInGrace,
            theme = chosen.themeMode.toPps(),
            reliabilityProblem = ui.reliabilityProblem,
            pane = ui.pane,
            // While a new value is still being saved, the stored note may be the old one: wait for the store.
            baseFeeNote = weakeningNoteOf(saved.pending(LockedField.BaseFee), now, zone).takeIf { tier == saved.tier },
            maxSnoozesNote = weakeningNoteOf(saved.pending(LockedField.MaxSnoozes), now, zone).takeIf { max == saved.maxSnoozes },
        )
    }

    /** One save in the queue: a commitment-locked setting (Story 4.5) or a default (Story 5.1). */
    private sealed interface Save {
        data class Locked(
            val value: SettingValue,
        ) : Save

        data class Default(
            val request: DefaultRequest,
        ) : Save
    }

    /** The stored live settings and global pending changes. */
    private data class Stored(
        val settings: GlobalSettings,
        val pending: List<PendingChange>,
    ) {
        fun pending(field: LockedField): PendingChange? = pending.firstOrNull { it.field == field }

        /** The base fee tier the user chose: the pending one if any, else the live one. */
        val tier: Int
            get() = (pending(LockedField.BaseFee)?.value as? SettingValue.BaseFeeTier)?.tier ?: settings.baseFeeTier

        /** Max snoozes the user chose: the pending value if any, else the live one. */
        val maxSnoozes: Int
            get() = (pending(LockedField.MaxSnoozes)?.value as? SettingValue.MaxSnoozes)?.count ?: settings.maxSnoozes
    }

    /** A value the user asked for; [saved] once its save succeeded (it goes when the store shows it). */
    private data class Request(
        val value: Int,
        val saved: Boolean = false,
    )

    /** A default the user asked for, the [id]th request; [saved] once its save succeeded (it goes when the store shows it). */
    private data class DefaultRequest(
        val value: DefaultSetting,
        val id: Long,
        val saved: Boolean = false,
    ) {
        fun sameField(other: DefaultSetting): Boolean = value::class == other::class
    }

    /**
     * What only this screen holds: the pane, the values requested but not shown by the store yet, and whether the last
     * checklist evaluation found a blocking row (Story 5.4).
     */
    private data class Local(
        val pane: SettingsPane = SettingsPane.Main,
        val fee: Request? = null,
        val max: Request? = null,
        /** At most one per field, the latest. */
        val defaults: List<DefaultRequest> = emptyList(),
        val reliabilityProblem: Boolean = false,
    ) {
        /** [request] is still the latest of its field: one superseded by a newer value is never saved (a drag coalesces). */
        fun holds(request: DefaultRequest): Boolean = defaults.any { it.id == request.id }

        /**
         * [value]'s save is over. A newer request of the same field stays as it is. Otherwise a saved request waits for
         * the store ([forgetEchoed]) and a failed one goes, so the stored value comes back.
         */
        fun settled(
            value: SettingValue,
            saved: Boolean,
        ): Local =
            when (value) {
                is SettingValue.BaseFeeTier -> copy(fee = fee.settled(value.tier, saved))
                is SettingValue.MaxSnoozes -> copy(max = max.settled(value.count, saved))
                is SettingValue.GraceSeconds, is SettingValue.Checks -> this
            }

        /** [request]'s save is over: as for [settled], with a newer request of its field told apart by its id. */
        fun settled(
            request: DefaultRequest,
            saved: Boolean,
        ): Local =
            copy(
                defaults =
                    defaults.mapNotNull { shown ->
                        when {
                            shown.id != request.id -> shown
                            saved -> shown.copy(saved = true)
                            else -> null
                        }
                    },
            )

        private fun Request?.settled(
            value: Int,
            saved: Boolean,
        ): Request? =
            when {
                this == null || this.value != value -> this
                saved -> copy(saved = true)
                else -> null
            }
    }

    companion object {
        /** The operation logged when a base fee or max snoozes change could not be saved. */
        const val SAVE_SETTING = "save snooze setting"

        /** The operation logged when a default or the theme could not be saved (Story 5.1). */
        const val SAVE_DEFAULT = "save default setting"

        /** The Koin name of the app scope that finishes the default saves left when Settings closes. */
        const val SAVE_SCOPE = "settings save scope"
        const val LOAD_SETTINGS = "load global settings"
        const val LOAD_PENDING = "load pending changes"

        /** The operations logged when the usage stats choice could not be read or saved (Story 5.6). */
        const val LOAD_CONSENT = "load usage stats consent"
        const val SAVE_CONSENT = "save usage stats consent"

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Whether this state shows [value]. */
private fun SettingsUiState.shows(value: DefaultSetting): Boolean =
    when (value) {
        is DefaultSetting.GraceSeconds -> defaultQuietSeconds == value.seconds
        is DefaultSetting.VibrateInGrace -> vibrateDuringQuietTime == value.on
        is DefaultSetting.SnoozeLength -> defaultSnoozeMinutes == value.minutes
        is DefaultSetting.Theme -> theme == value.mode.toPps()
    }

/** Whether [this] is a value its use case accepts (the ViewModel never queues one it would refuse). */
private fun DefaultSetting.isValid(): Boolean =
    when (this) {
        is DefaultSetting.GraceSeconds -> seconds in SaveDefaultSetting.GRACE_SECONDS
        is DefaultSetting.SnoozeLength -> minutes in SaveDefaultSetting.SNOOZE_LENGTHS
        is DefaultSetting.VibrateInGrace, is DefaultSetting.Theme -> true
    }

/** Logs a default or theme save that failed (Story 5.1). */
private fun Logger.logDefaultFailure(result: Outcome<Unit, DomainError>) {
    if (result is Outcome.Failure) log(LogEvent.OperationFailed.of(SettingsViewModel.SAVE_DEFAULT, result.error))
}

/** Logs a store read that failed with [cause] as [operation]. */
private fun Logger.logFailure(
    operation: String,
    cause: Throwable,
) = log(LogEvent.OperationFailed(operation, cause.message ?: cause::class.simpleName.orEmpty()))
