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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 *   never races itself. The stepper moves at once to the requested value. A saved request is let go only by the same
 *   render that sees the store hold it (review fix 1), so the stepper never shows the old value in between and the next
 *   step starts from the new one. A failed save is logged and the stored value comes back, unless a newer request
 *   replaced it.
 *
 * [state] is null until the stored settings are read, so no default value flashes. Saves still queued when the
 * ViewModel is cleared are logged as dropped.
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

    /** The save running now, if any (logged as dropped when the ViewModel is cleared under it). */
    private var saving: SettingValue? = null

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
        combine(stored, priceCatalog.observe(), ticks, local) { saved, prices, _, ui ->
            forgetEchoed(saved, ui)
            render(saved, prices, ui)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    init {
        viewModelScope.launch {
            for (value in saves) {
                saving = value
                save(value)
                saving = null
            }
        }
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

    override fun onCleared() {
        val dropped = listOfNotNull(saving) + generateSequence { saves.tryReceive().getOrNull() }.toList()
        if (dropped.isNotEmpty()) logger.log(LogEvent.OperationFailed(SAVE_SETTING, "dropped ${dropped.size} on close"))
        saves.close()
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
        saves.trySend(value)
    }

    private suspend fun save(value: SettingValue) {
        val result =
            when (value) {
                is SettingValue.BaseFeeTier -> setBaseFee(value.tier)
                is SettingValue.MaxSnoozes -> setMaxSnoozes(value.count)
                is SettingValue.GraceSeconds, is SettingValue.Checks -> return
            }
        if (result is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(SAVE_SETTING, result.error))
        local.update { it.settled(value, saved = result is Outcome.Success) }
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
        if (feeEchoed != null || maxEchoed != null) {
            // Only the very request that was seen: a newer one made meanwhile stays.
            local.update {
                it.copy(
                    fee = it.fee.takeUnless { request -> request == feeEchoed },
                    max = it.max.takeUnless { request -> request == maxEchoed },
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
        return SettingsUiState(
            baseFee = fees.baseFee,
            baseFeeTier = tier,
            feeLadder = fees.ladder,
            pricesApproximate = fees.approximate,
            maxSnoozes = max,
            pane = ui.pane,
            // While a new value is still being saved, the stored note may be the old one: wait for the store.
            baseFeeNote = weakeningNoteOf(saved.pending(LockedField.BaseFee), now, zone).takeIf { tier == saved.tier },
            maxSnoozesNote = weakeningNoteOf(saved.pending(LockedField.MaxSnoozes), now, zone).takeIf { max == saved.maxSnoozes },
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

    /** What only this screen holds: the pane and the values requested but not shown by the store yet. */
    private data class Local(
        val pane: SettingsPane = SettingsPane.Main,
        val fee: Request? = null,
        val max: Request? = null,
    ) {
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
        const val LOAD_SETTINGS = "load global settings"
        const val LOAD_PENDING = "load pending changes"

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
