package com.yawnandpawn.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.billing.FeeRules
import com.yawnandpawn.app.core.billing.MoneyFormatter
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.LockedField
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SetMaxSnoozes
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Settings › Snooze (Story 4.5): the base fee and max snoozes per session, set through the commitment lock.
 *
 * - **Shown values:** what the user chose, which is the global pending change from [pendingChanges] when one waits,
 *   else the live value from [globalSettings]. While a pending change waits for an occurrence still to come, its
 *   sub-screen shows "Saved. Takes effect after tomorrow's (today's) {time} alarm." ([weakeningNoteOf]). The note is
 *   re-checked on every [timeChanges] tick, so it goes once that alarm has rung.
 * - **Prices:** the cached Play prices from [priceCatalog], or USD approximations by [moneyFormatter] when a tier is
 *   missing ([SnoozePrices]). Nothing waits for a refresh, so the screen works offline.
 * - **Saving:** each step calls [setBaseFee] / [setMaxSnoozes] through one queue, in order, so a long-press repeat
 *   never races itself. The stepper moves at once. The requested value holds until the store shows it, or until its
 *   save fails: the failure is logged and the stored value comes back.
 *
 * [state] is null until the stored settings are read, so no default value flashes.
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
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val saves = Channel<SettingValue>(Channel.UNLIMITED)

    /** The stored settings and their global pending changes; an unreadable store is logged and reads as the defaults. */
    private val stored: Flow<Stored> =
        combine(
            globalSettings.observe().catch { cause ->
                logFailure(LOAD_SETTINGS, cause)
                emit(GlobalSettings())
            },
            pendingChanges.observe().catch { cause ->
                logFailure(LOAD_PENDING, cause)
                emit(emptyList())
            },
        ) { settings, pending -> Stored(settings, pending.filter { it.alarmId == null }) }

    private val ticks: Flow<Unit> = timeChanges.changes().onStart { emit(Unit) }

    val state: StateFlow<SettingsUiState?> =
        combine(stored, priceCatalog.observe(), ticks, local) { saved, prices, _, ui -> render(saved, prices, ui) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    init {
        viewModelScope.launch { for (value in saves) save(value) }
    }

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.OpenPane -> local.update { it.copy(pane = intent.pane) }

            SettingsIntent.Back -> local.update { it.copy(pane = SettingsPane.Main) }

            SettingsIntent.LowerBaseFee -> currentTier()?.let { request(SettingValue.BaseFeeTier(it - 1)) }

            SettingsIntent.RaiseBaseFee -> currentTier()?.let { request(SettingValue.BaseFeeTier(it + 1)) }

            is SettingsIntent.MaxSnoozesChanged -> request(SettingValue.MaxSnoozes(intent.value))

            // The other rows belong to Epic 5; production does not show them.
            else -> Unit
        }
    }

    /**
     * The tier a step starts from: the last one requested (set at once, so taps faster than a frame still add up), else
     * the one shown; null before the store was read.
     */
    private fun currentTier(): Int? = local.value.requestedTier ?: state.value?.baseFeeTier

    /** Shows [value] at once and queues its save; a value outside its range is ignored (the stepper end is disabled). */
    private fun request(value: SettingValue) {
        val shown: (Local) -> Local =
            when {
                value is SettingValue.BaseFeeTier && value.tier in FeeRules.BASE_FEE_TIERS -> { ui -> ui.copy(requestedTier = value.tier) }
                value is SettingValue.MaxSnoozes && value.count in FeeRules.MAX_SNOOZES -> { ui -> ui.copy(requestedMax = value.count) }
                else -> return
            }
        local.update(shown)
        saves.trySend(value)
    }

    private suspend fun save(value: SettingValue) {
        val result =
            when (value) {
                is SettingValue.BaseFeeTier -> setBaseFee(value.tier)
                is SettingValue.MaxSnoozes -> setMaxSnoozes(value.count)
                is SettingValue.GraceSeconds, is SettingValue.Checks -> return
            }
        when (result) {
            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of(SAVE_SETTING, result.error))
            }

            // Hold the requested value until the store shows it, so the stepper never flickers back.
            is Outcome.Success -> {
                withTimeoutOrNull(STORE_ECHO_TIMEOUT) { stored.first { it.chosen(value.field) == value } }
            }
        }
        local.update { it.settled(value) }
    }

    private fun render(
        saved: Stored,
        prices: PriceCatalogSnapshot,
        ui: Local,
    ): SettingsUiState {
        val now = clock.now()
        val zone = timeZoneProvider.current()
        val storedTier = (saved.chosen(LockedField.BaseFee) as SettingValue.BaseFeeTier).tier
        val storedMax = (saved.chosen(LockedField.MaxSnoozes) as SettingValue.MaxSnoozes).count
        val tier = ui.requestedTier ?: storedTier
        val max = ui.requestedMax ?: storedMax
        val fees = SnoozePrices.of(tier, max, prices, now, moneyFormatter)
        return SettingsUiState(
            baseFee = fees.baseFee,
            baseFeeTier = tier,
            feeLadder = fees.ladder,
            pricesApproximate = fees.approximate,
            maxSnoozes = max,
            pane = ui.pane,
            // While a new value is still being saved, the stored note may be the old one: wait for the store.
            baseFeeNote = weakeningNoteOf(saved.pending(LockedField.BaseFee), now, zone).takeIf { tier == storedTier },
            maxSnoozesNote = weakeningNoteOf(saved.pending(LockedField.MaxSnoozes), now, zone).takeIf { max == storedMax },
        )
    }

    private fun logFailure(
        operation: String,
        cause: Throwable,
    ) = logger.log(LogEvent.OperationFailed(operation, cause.message ?: cause::class.simpleName.orEmpty()))

    /** The stored live settings and global pending changes. */
    private data class Stored(
        val settings: GlobalSettings,
        val pending: List<PendingChange>,
    ) {
        fun pending(field: LockedField): PendingChange? = pending.firstOrNull { it.field == field }

        /** The value the user chose for a global [field]: the pending one if any, else the live one. */
        fun chosen(field: LockedField): SettingValue =
            pending(field)?.value ?: when (field) {
                LockedField.BaseFee -> SettingValue.BaseFeeTier(settings.baseFeeTier)
                else -> SettingValue.MaxSnoozes(settings.maxSnoozes)
            }
    }

    /** What only this screen holds: the pane and the values requested but not stored yet. */
    private data class Local(
        val pane: SettingsPane = SettingsPane.Main,
        val requestedTier: Int? = null,
        val requestedMax: Int? = null,
    ) {
        /** [value]'s save is over: its request goes, unless a newer one replaced it. */
        fun settled(value: SettingValue): Local =
            when (value) {
                is SettingValue.BaseFeeTier -> if (requestedTier == value.tier) copy(requestedTier = null) else this
                is SettingValue.MaxSnoozes -> if (requestedMax == value.count) copy(requestedMax = null) else this
                is SettingValue.GraceSeconds, is SettingValue.Checks -> this
            }
    }

    companion object {
        /** The operation logged when a base fee or max snoozes change could not be saved. */
        const val SAVE_SETTING = "save snooze setting"
        const val LOAD_SETTINGS = "load global settings"
        const val LOAD_PENDING = "load pending changes"

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** How long a saved value is held on screen while the store's flow catches up. */
        private val STORE_ECHO_TIMEOUT = 2.seconds
    }
}
