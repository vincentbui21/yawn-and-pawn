package com.yawnandpawn.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.billing.FeeRules
import com.yawnandpawn.app.core.billing.MoneyFormatter
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.onboarding.AcceptDisclosure
import com.yawnandpawn.app.core.onboarding.CompleteOnboarding
import com.yawnandpawn.app.core.onboarding.OnboardingProgress
import com.yawnandpawn.app.core.onboarding.OnboardingRepository
import com.yawnandpawn.app.core.onboarding.SaveOnboardingStep
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.settings.SnoozePrices
import com.yawnandpawn.app.ui.settings.weakeningNoteOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * Onboarding steps 1 to 3 (Story 5.10, FR-ONB-1, FR-ONB-5, FR-SET-1): the mission, the alarm behaviour disclosure and the
 * base fee. Steps 4 to 8 come with Stories 5.11 and 5.12, so their intents are ignored here.
 *
 * - **Step:** read once from the stored progress ([onboarding]); an unknown stored name opens step 1, and a step past the
 *   disclosure without its consent opens the disclosure (no skip). Every change is shown at once and stored through
 *   [saveStep], so it survives process death. Back goes one step back; step 1 has no in-app Back (the system leaves).
 * - **"I understand"** stores the consent ([acceptDisclosure]) and then opens the base fee; a failed write stays on the
 *   disclosure, logged.
 * - **Base fee:** the step attempts a [priceCatalog] refresh each time it opens (a failure is only logged; the cached or
 *   approximate prices show at once, [SnoozePrices]). − and + save the next tier through [setBaseFee] (the commitment
 *   lock and the session lock are 4.4's); the stepper moves at once to the requested tier. A saved tier stays shown (a
 *   lower fee may be stored as pending under the lock, like Settings); a failed save is logged and the stored value comes
 *   back.
 * - **"Continue"** on the base fee completes onboarding ([completeOnboarding]) once the fee saves before it are done; the
 *   app gate then opens Home. A failed "I understand" or "Continue" stays on the step, logged, and shows
 *   "Couldn't save. Try again." ([OnboardingUiState.saveFailed]).
 * - **No skipped step (review fix, FR-ONB-5):** the step's action counts only when it comes from the step shown
 *   ([OnboardingIntent.Next.from]) and that step has been shown for [STEP_DWELL_MILLIS] on [monotonicClock], so a double
 *   tap never lands on the next step's identical button, also with animations off.
 * - **Lowered fee under the lock (review fix):** when the save waits for an alarm, the step shows Settings' note "Saved.
 *   Takes effect after tomorrow's {time} alarm." ([OnboardingUiState.baseFeeNote], in [timeZoneProvider]'s zone).
 *
 * Every write runs in order through one queue, so a quick − / + / "Continue" never races itself. [state] is null until
 * the stored progress is read, so step 1 never flashes before a restored step 3.
 */
@Suppress("TooManyFunctions") // One small handler per onboarding action and queued write.
class OnboardingViewModel(
    private val onboarding: OnboardingRepository,
    private val saveStep: SaveOnboardingStep,
    private val acceptDisclosure: AcceptDisclosure,
    private val completeOnboarding: CompleteOnboarding,
    globalSettings: GlobalSettingsRepository,
    private val priceCatalog: PriceCatalog,
    private val setBaseFee: SetBaseFee,
    private val clock: Clock,
    private val moneyFormatter: MoneyFormatter,
    private val logger: Logger,
    /** The app binds it (`UiModule`); null (an older call site) leaves out the dwell time, never the step check. */
    private val monotonicClock: MonotonicClock? = null,
    /** The app binds it (`UiModule`); null leaves out the pending fee note. */
    private val timeZoneProvider: TimeZoneProvider? = null,
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val ops = Channel<Op>(Channel.UNLIMITED)

    /** Numbers the fee requests, so a save settles only its own request. */
    private var nextRequest = 0L

    /** The stored settings; an unreadable store is logged and reads as the defaults. */
    private val settings: Flow<GlobalSettings> =
        globalSettings.observe().catch { cause ->
            logFailure(LOAD_SETTINGS, cause)
            emit(GlobalSettings())
        }

    /** The cached prices; an unreadable cache is logged and reads as empty (USD with the approximate note). */
    private val prices: Flow<PriceCatalogSnapshot> =
        priceCatalog.observe().catch { cause ->
            logFailure(LOAD_PRICES, cause)
            emit(PriceCatalogSnapshot.EMPTY)
        }

    val state: StateFlow<OnboardingUiState?> =
        combine(local, settings, prices) { ui, stored, prices ->
            ui.step?.let { step -> render(step, ui, stored, prices) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    init {
        // The stored step first, then the write queue, in order.
        viewModelScope.launch {
            restore()
            for (op in ops) run(op)
        }
    }

    fun onIntent(intent: OnboardingIntent) {
        val ui = local.value
        val step = ui.step ?: return
        if (ui.busy) return
        when (intent) {
            // A tap on a step already left (a double tap, the outgoing step) never skips the next one (FR-ONB-5).
            is OnboardingIntent.Next -> if (intent.from == step && settled(ui)) next(step)

            OnboardingIntent.Back -> if (step.ordinal > 0 && step <= LAST_BUILT) goTo(OnboardingStep.entries[step.ordinal - 1])

            OnboardingIntent.LowerBaseFee -> stepFee(step, -1)

            OnboardingIntent.RaiseBaseFee -> stepFee(step, 1)

            // Steps 4 to 8 belong to Stories 5.11 and 5.12.
            else -> Unit
        }
    }

    /** The step on screen has been shown for [STEP_DWELL_MILLIS] (a restored step counts from when it was read). */
    private fun settled(ui: Local): Boolean {
        val clock = monotonicClock ?: return true
        return ui.shownAt == null || clock.elapsedMillis() - ui.shownAt >= STEP_DWELL_MILLIS
    }

    /** Shows [step] from now: its dwell starts again, and an earlier save failure note goes. */
    private fun Local.showing(step: OnboardingStep): Local =
        copy(step = step, shownAt = monotonicClock?.elapsedMillis(), saveFailed = false)

    /** − or + ([by] -1 or 1), only on the base fee step (a tap on the outgoing step during a slide saves nothing). */
    private fun stepFee(
        step: OnboardingStep,
        by: Int,
    ) {
        if (step == OnboardingStep.BaseFee) currentTier()?.let { requestFee(it + by) }
    }

    override fun onCleared() {
        ops.close()
    }

    private fun next(step: OnboardingStep) {
        when (step) {
            OnboardingStep.Mission -> goTo(OnboardingStep.Disclosure)

            OnboardingStep.Disclosure -> queueBusy(Op.Accept)

            OnboardingStep.BaseFee -> queueBusy(Op.Complete)

            // Steps 4 to 8 belong to Stories 5.11 and 5.12.
            else -> Unit
        }
    }

    /** Queues "I understand" or "Continue"; until it is over a second tap, Back and the stepper are ignored. */
    private fun queueBusy(op: Op) {
        local.update { it.copy(busy = true, saveFailed = false) }
        ops.trySend(op)
    }

    /** Shows [step] at once and queues storing it; the base fee step refreshes the prices as it opens. */
    private fun goTo(step: OnboardingStep) {
        local.update { it.showing(step) }
        ops.trySend(Op.Step(step))
        if (step == OnboardingStep.BaseFee) refreshPrices()
    }

    /** The tier a step starts from: the last one requested (taps faster than a frame add up), else the one shown. */
    private fun currentTier(): Int? = local.value.request?.tier ?: state.value?.baseFeeTier

    /** Shows [tier] at once and queues its save; a tier outside 1 to 10 is ignored (the stepper end is disabled). */
    private fun requestFee(tier: Int) {
        if (tier !in FeeRules.BASE_FEE_TIERS) return
        val request = FeeRequest(tier, nextRequest++)
        local.update { it.copy(request = request) }
        ops.trySend(Op.Fee(request))
    }

    /** Opens the stored step: step 1 for an unknown name, the disclosure for a later step without its consent. */
    private suspend fun restore() {
        val progress =
            onboarding
                .observe()
                .catch { cause ->
                    logFailure(LOAD_PROGRESS, cause)
                    emit(OnboardingProgress())
                }.first()
        val stored = OnboardingStep.entries.firstOrNull { it.name == progress.step } ?: OnboardingStep.Mission
        val built = minOf(stored, LAST_BUILT)
        val step = if (built > OnboardingStep.Disclosure && !progress.consented) OnboardingStep.Disclosure else built
        local.update { it.showing(step) }
        if (step == OnboardingStep.BaseFee) refreshPrices()
    }

    private suspend fun run(op: Op) {
        when (op) {
            is Op.Step -> {
                logIfFailed(SAVE_STEP, saveStep(op.step.name))
            }

            Op.Accept -> {
                val result = acceptDisclosure()
                logIfFailed(ACCEPT_DISCLOSURE, result)
                if (result is Outcome.Success) {
                    local.update { it.showing(OnboardingStep.BaseFee).copy(busy = false) }
                    refreshPrices()
                    logIfFailed(SAVE_STEP, saveStep(OnboardingStep.BaseFee.name))
                } else {
                    local.update { it.copy(busy = false, saveFailed = true) }
                }
            }

            is Op.Fee -> {
                saveFee(op.request)
            }

            Op.Complete -> {
                val result = completeOnboarding()
                logIfFailed(COMPLETE, result)
                // Once stored the app gate opens Home and clears this ViewModel; a failure stays on the step.
                if (result is Outcome.Failure) local.update { it.copy(busy = false, saveFailed = true) }
            }
        }
    }

    private suspend fun saveFee(request: FeeRequest) {
        val result = setBaseFee(request.tier)
        logIfFailed(SAVE_FEE, result)
        local.update { ui ->
            val latest = ui.request?.id == request.id
            when {
                result is Outcome.Success && latest -> {
                    ui.copy(chosen = request.tier, request = null, pendingAfter = result.value.pendingUntil?.scheduledAt)
                }

                result is Outcome.Success -> {
                    ui.copy(chosen = request.tier)
                }

                latest -> {
                    ui.copy(request = null)
                }

                else -> {
                    ui
                }
            }
        }
    }

    private fun refreshPrices() {
        viewModelScope.launch { logIfFailed(REFRESH_PRICES, priceCatalog.refresh()) }
    }

    private fun render(
        step: OnboardingStep,
        ui: Local,
        stored: GlobalSettings,
        prices: PriceCatalogSnapshot,
    ): OnboardingUiState {
        val tier = ui.request?.tier ?: ui.chosen ?: stored.baseFeeTier
        val now = clock.now()
        val fees = SnoozePrices.of(tier, stored.maxSnoozes, prices, now, moneyFormatter)
        return OnboardingUiState(
            step = step,
            baseFeeTier = tier,
            baseFee = fees.baseFee,
            feeLadder = fees.ladder,
            pricesApproximate = fees.approximate,
            baseFeeNote = timeZoneProvider?.takeIf { ui.request == null }?.let { weakeningNoteOf(ui.pendingAfter, now, it.current()) },
            saveFailed = ui.saveFailed,
        )
    }

    private fun logIfFailed(
        operation: String,
        result: Outcome<*, DomainError>,
    ) {
        if (result is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(operation, result.error))
    }

    private fun logFailure(
        operation: String,
        cause: Throwable,
    ) = logger.log(LogEvent.OperationFailed(operation, cause.message ?: cause::class.simpleName.orEmpty()))

    /** One write in the queue. */
    private sealed interface Op {
        data class Step(
            val step: OnboardingStep,
        ) : Op

        data object Accept : Op

        data class Fee(
            val request: FeeRequest,
        ) : Op

        data object Complete : Op
    }

    /** The [id]th base fee tier the user asked for. */
    private data class FeeRequest(
        val tier: Int,
        val id: Long,
    )

    /** What only this screen holds. */
    private data class Local(
        /** Null until the stored progress is read. */
        val step: OnboardingStep? = null,
        /** The latest tier asked for, until its save is over. */
        val request: FeeRequest? = null,
        /** The last tier saved (live, or pending under the commitment lock): shown while no request is in flight. */
        val chosen: Int? = null,
        /** "I understand" or "Continue" is being stored: a second tap and Back are ignored. */
        val busy: Boolean = false,
        /** When [step] was shown, on the monotonic clock; null until the stored step is read. */
        val shownAt: Long? = null,
        /** The last "I understand" or "Continue" could not be stored. */
        val saveFailed: Boolean = false,
        /** The ring the last saved fee waits for under the commitment lock; null when it applied at once. */
        val pendingAfter: Instant? = null,
    )

    companion object {
        /** The last step this story builds; 4 to 8 come with Stories 5.11 and 5.12. */
        val LAST_BUILT: OnboardingStep = OnboardingStep.BaseFee

        const val LOAD_PROGRESS = "load onboarding progress"
        const val LOAD_SETTINGS = "load global settings"
        const val SAVE_STEP = "save onboarding step"
        const val ACCEPT_DISCLOSURE = "accept disclosure"
        const val COMPLETE = "complete onboarding"
        const val SAVE_FEE = "save base fee"
        const val REFRESH_PRICES = "refresh prices"
        const val LOAD_PRICES = "load prices"

        /** How long a step shows before its action counts (review fix): longer than the 250 ms slide and a double tap. */
        const val STEP_DWELL_MILLIS = 500L

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
