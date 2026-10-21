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
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.checkpicker.CheckPickerUiState
import com.yawnandpawn.app.ui.editor.EditorForm
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.reliability.ChecklistRow
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
 * Onboarding steps 1 to 7 (Stories 5.10 and 5.11; FR-ONB-1, FR-ONB-2, FR-ONB-5, FR-ONB-6, FR-SET-1, FR-ALM-1): the
 * mission, the alarm behaviour disclosure, the base fee, the first alarm, its checks, the reliability checklist and the
 * usage stats choice. Step 8, the test alarm, comes with Story 5.12, so its intents are ignored here.
 *
 * - **Step:** read once from the stored progress ([onboarding]); an unknown stored name opens step 1, a step past the
 *   disclosure without its consent opens the disclosure (no skip), and a step past the checks without a stored first
 *   alarm opens the first alarm. Every change is shown at once and stored through [saveStep], so it survives process
 *   death. Back goes one step back; step 1 has no in-app Back (the system leaves).
 * - **"I understand"** stores the consent ([acceptDisclosure]) and then opens the base fee; a failed write stays on the
 *   disclosure, logged.
 * - **Base fee:** the step attempts a [priceCatalog] refresh each time it opens (a failure is only logged; the cached or
 *   approximate prices show at once, [SnoozePrices]). − and + save the next tier through [setBaseFee] (the commitment
 *   lock and the session lock are 4.4's); the stepper moves at once to the requested tier. A saved tier stays shown (a
 *   lower fee may be stored as pending under the lock, like Settings, with its note "Saved. Takes effect after
 *   tomorrow's {time} alarm." in [timeZoneProvider]'s zone); a failed save is logged and the stored value comes back.
 *   "Continue" opens the first alarm.
 * - **First alarm and checks (steps 4 and 5):** the form of a child Alarm editor ([OnboardingFirstAlarm]): a new alarm with
 *   the Settings defaults, or the first alarm already stored (its id is remembered through [FirstAlarmSources.remember]),
 *   so going back never adds a second alarm. "Continue" on step 5 saves it like the editor's Save (`SaveAlarm`, enabled,
 *   the notification ask once); once stored the checklist opens and the id, then the step, are stored. A failed save
 *   stays with "Couldn't save. Try again." ([OnboardingUiState.saveFailed]); a stored alarm that cannot be read opens a
 *   new one and the id is forgotten. Check setup, "Try it" and QR registration show over step 5 ([OnboardingUiState.checkSetup]).
 * - **Checklist (step 6):** Story 5.4's own checklist ([OnboardingChecklist], built by [ChecklistScreens]) without its
 *   test alarm row, read again each time the step opens, the screen resumes and the maker guidance closes. "Fix" is the
 *   checklist screen's own (each row's system screen, the camera ask); on the maker row it opens the maker guidance over
 *   the step ([OnboardingUiState.manufacturerGuidance]).
 * - **Usage stats (step 7):** "Share" or "No thanks" is stored through [UsageStatsSources.setConsent] (asked, on or off),
 *   then onboarding completes ([afterUsageStats]); a failed write stays, logged. A user already asked skips the step.
 *   Completing ([completeOnboarding]) lets the app gate open Home. A failed "I understand", consent, completion or alarm
 *   save stays on the step, logged, with "Couldn't save. Try again." ([OnboardingUiState.saveFailed]).
 * - **No skipped step (5.10 review fix, FR-ONB-5):** a step's action ("Continue", "Share", "No thanks", ...) counts only
 *   when it comes from the step shown ([OnboardingIntent.Next.from]) and that step has been shown for
 *   [STEP_DWELL_MILLIS] on [monotonicClock], so a double tap never lands on the next step's button, also with
 *   animations off (the screen also ignores the actions while a step slides in).
 *
 * Every write runs in order through one queue, so a quick − / + / "Continue" never races itself. [state] is null until
 * the stored progress is read, so step 1 never flashes before a restored step.
 */
@Suppress("TooManyFunctions", "LargeClass") // One small handler per onboarding action and queued write.
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
    private val firstAlarm: FirstAlarmSources,
    checklist: ChecklistScreens,
    private val usageStats: UsageStatsSources,
    /** The app binds it (`UiModule`); null (an older call site) leaves out the dwell time, never the step check. */
    private val monotonicClock: MonotonicClock? = null,
    /** The app binds it (`UiModule`); null leaves out the pending fee note. */
    private val timeZoneProvider: TimeZoneProvider? = null,
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val ops = Channel<Op>(Channel.UNLIMITED)

    /** Numbers the fee requests, so a save settles only its own request. */
    private var nextRequest = 0L

    /** Step 6's checklist: Story 5.4's own; its maker row opens the guidance over the step. */
    private val reliability = OnboardingChecklist(checklist, viewModelScope) { openGuidance() }

    /** The first alarm's editor (steps 4 and 5), opened when one of them opens. */
    private val alarm =
        OnboardingFirstAlarm(
            editors = firstAlarm.editors,
            scope = viewModelScope,
            events =
                object : OnboardingFirstAlarm.Events {
                    override fun saved(id: String) = alarmSaved(id)

                    override fun openFailed() = alarmUnreadable()

                    override fun saveFailed() {
                        local.update { it.copy(saveFailed = true) }
                    }
                },
        )

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
        combine(local, settings, prices, alarm.state, reliability.rows) { ui, stored, prices, editor, rows ->
            ui.step?.let { step -> render(step, ui, stored, prices, editor, rows) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    init {
        addCloseable(alarm)
        addCloseable(reliability)
        // The stored step first, then the write queue, in order.
        viewModelScope.launch {
            restore()
            for (op in ops) run(op)
        }
    }

    fun onIntent(intent: OnboardingIntent) {
        val ui = local.value
        val step = ui.step ?: return
        when (intent) {
            // Never held back: a re-check or closing the guidance changes nothing stored.
            OnboardingIntent.Resumed -> if (step == OnboardingStep.Reliability) reliability.refresh()

            OnboardingIntent.GuidanceClosed -> closeGuidance()

            else -> if (!ui.busy && !alarm.saving) onStepIntent(step, intent)
        }
    }

    private fun onStepIntent(
        step: OnboardingStep,
        intent: OnboardingIntent,
    ) {
        when (intent) {
            // A tap on a step already left (a double tap, the outgoing step) never skips the next one (FR-ONB-5), and a
            // step's action counts only once the step has been shown for STEP_DWELL_MILLIS.
            is OnboardingIntent.Next -> {
                if (intent.from == step && settled()) next(step)
            }

            OnboardingIntent.Back -> {
                if (step.ordinal > 0 && step <= LAST_BUILT) goTo(OnboardingStep.entries[step.ordinal - 1])
            }

            OnboardingIntent.LowerBaseFee -> {
                stepFee(step, -1)
            }

            OnboardingIntent.RaiseBaseFee -> {
                stepFee(step, 1)
            }

            else -> {
                onSetupIntent(step, intent)
            }
        }
    }

    /** Steps 6 and 7's own taps ("Fix", "Share" / "No thanks"), with the same dwell as "Continue"; then steps 4 and 5. */
    private fun onSetupIntent(
        step: OnboardingStep,
        intent: OnboardingIntent,
    ) {
        when (intent) {
            is OnboardingIntent.FixClicked -> {
                if (step == OnboardingStep.Reliability && settled()) reliability.fix(intent.item)
            }

            is OnboardingIntent.UsageStatsChosen -> {
                if (step == OnboardingStep.Analytics && settled()) queueBusy(Op.UsageStats(intent.share))
            }

            else -> {
                onAlarmIntent(step, intent)
            }
        }
    }

    /** Steps 4 and 5 go to the editor, each only on its own step (a tap on the outgoing step during a slide is ignored). */
    private fun onAlarmIntent(
        step: OnboardingStep,
        intent: OnboardingIntent,
    ) {
        val firstAlarmStep = step == OnboardingStep.FirstAlarm
        val checksStep = step == OnboardingStep.Checks
        when (intent) {
            is OnboardingIntent.TimeChanged -> if (firstAlarmStep) alarm.send(EditorIntent.TimeChanged(intent.time))

            is OnboardingIntent.RepeatChosen -> if (firstAlarmStep) alarm.send(EditorIntent.RepeatChosen(intent.choice))

            is OnboardingIntent.DayToggled -> if (firstAlarmStep) alarm.send(EditorIntent.DayToggled(intent.day))

            is OnboardingIntent.Checks -> if (checksStep) alarm.send(intent.intent.toEditor())

            // Only while Check setup, "Try it" or QR registration shows: a tap on the overlay sliding out after Back
            // never reaches the editor's main screen (where a Back would close it).
            is OnboardingIntent.CheckSetup -> if (checksStep && state.value?.checkSetup != null) alarm.send(intent.intent)

            // The test alarm belongs to Story 5.12.
            else -> Unit
        }
    }

    /** The step on screen has been shown for [STEP_DWELL_MILLIS] (a restored step counts from when it was read). */
    private fun settled(): Boolean {
        val clock = monotonicClock ?: return true
        val shownAt = local.value.shownAt
        return shownAt == null || clock.elapsedMillis() - shownAt >= STEP_DWELL_MILLIS
    }

    /** Shows [step] from now: its dwell starts again, an earlier save failure note and the maker guidance go. */
    private fun Local.showing(step: OnboardingStep): Local =
        copy(step = step, shownAt = monotonicClock?.elapsedMillis(), saveFailed = false, guidance = false)

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
            OnboardingStep.Mission -> {
                goTo(OnboardingStep.Disclosure)
            }

            OnboardingStep.Disclosure -> {
                queueBusy(Op.Accept)
            }

            OnboardingStep.BaseFee -> {
                goTo(OnboardingStep.FirstAlarm)
            }

            OnboardingStep.FirstAlarm -> {
                goTo(OnboardingStep.Checks)
            }

            // The editor's Save: with no check it refuses, with QR/Barcode and no code it opens Check setup.
            OnboardingStep.Checks -> {
                local.update { it.copy(saveFailed = false) }
                alarm.send(EditorIntent.SaveClicked)
            }

            OnboardingStep.Reliability -> {
                queueBusy(Op.LeaveChecklist)
            }

            // Step 7 has its two choices; step 8 belongs to Story 5.12.
            OnboardingStep.Analytics, OnboardingStep.TestAlarm -> {
                Unit
            }
        }
    }

    /** Queues a write that decides the next step; until it is over a second tap, Back and the step's controls are ignored. */
    private fun queueBusy(op: Op) {
        local.update { it.copy(busy = true, saveFailed = false) }
        ops.trySend(op)
    }

    /** Shows [step] at once and queues storing it, then does what the step does as it opens. */
    private fun goTo(step: OnboardingStep) {
        local.update { it.showing(step) }
        ops.trySend(Op.Step(step))
        opened(step)
    }

    /**
     * What a step does as it opens: the base fee refreshes the prices; the first alarm and its checks open the editor
     * (on the stored first alarm, if any) and step 5 its Wake-up check pane; the checklist is read again.
     */
    private fun opened(step: OnboardingStep) {
        if (step == OnboardingStep.BaseFee) refreshPrices()
        if (step == OnboardingStep.FirstAlarm || step == OnboardingStep.Checks) alarm.ensureOpen(local.value.alarmId)
        if (step == OnboardingStep.Checks) alarm.openChecks() else alarm.leaveChecks()
        if (step == OnboardingStep.Reliability) reliability.refresh()
    }

    /** The first alarm is stored as [id]: the checklist opens at once; the id, then the step, are stored. */
    private fun alarmSaved(id: String) {
        local.update { it.showing(OnboardingStep.Reliability).copy(alarmId = id) }
        ops.trySend(Op.RememberAlarm(id))
        ops.trySend(Op.Step(OnboardingStep.Reliability))
        opened(OnboardingStep.Reliability)
    }

    /** The stored first alarm cannot be read (or is gone): a new alarm opens, and the id is forgotten. */
    private fun alarmUnreadable() {
        local.update { it.copy(alarmId = null) }
        ops.trySend(Op.RememberAlarm(null))
        alarm.open(null)
    }

    /** "Fix" on the maker row: the guidance over step 6 (a late report after the step was left is ignored). */
    private fun openGuidance() {
        local.update { if (it.step == OnboardingStep.Reliability) it.copy(guidance = true) else it }
    }

    private fun closeGuidance() {
        if (!local.value.guidance) return
        local.update { it.copy(guidance = false) }
        reliability.refresh()
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

    /**
     * Opens the stored step: step 1 for an unknown name, the disclosure for a later step without its consent, the first
     * alarm for a step past the checks without a stored first alarm.
     */
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
        val step =
            when {
                built > OnboardingStep.Disclosure && !progress.consented -> OnboardingStep.Disclosure
                built > OnboardingStep.Checks && progress.alarmId == null -> OnboardingStep.FirstAlarm
                else -> built
            }
        local.update { it.showing(step).copy(alarmId = progress.alarmId) }
        opened(step)
        // The choice was stored before the process died, but not the step after it: never asked again (FR-ONB-6).
        if (step == OnboardingStep.Analytics && asked()) {
            local.update { it.copy(busy = true) }
            afterUsageStats()
        }
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
                    show(OnboardingStep.BaseFee)
                } else {
                    local.update { it.copy(busy = false, saveFailed = true) }
                }
            }

            is Op.Fee -> {
                saveFee(op.request)
            }

            is Op.RememberAlarm -> {
                logIfFailed(REMEMBER_ALARM, firstAlarm.remember(op.id))
            }

            Op.LeaveChecklist -> {
                // A user already asked (any choice, here or in Settings) is not asked again.
                if (asked()) afterUsageStats() else show(OnboardingStep.Analytics)
            }

            is Op.UsageStats -> {
                val result = usageStats.setConsent(op.share)
                logIfFailed(SAVE_CONSENT, result)
                if (result is Outcome.Success) afterUsageStats() else local.update { it.copy(busy = false, saveFailed = true) }
            }
        }
    }

    /** A queued write decided [step]: it shows, opens and is stored. */
    private suspend fun show(step: OnboardingStep) {
        local.update { it.showing(step).copy(busy = false) }
        opened(step)
        logIfFailed(SAVE_STEP, saveStep(step.name))
    }

    /**
     * What follows the usage stats choice (or its skip): onboarding completes until Story 5.12 raises [LAST_BUILT], then
     * the test alarm opens.
     */
    private suspend fun afterUsageStats() {
        if (LAST_BUILT > OnboardingStep.Analytics) show(OnboardingStep.TestAlarm) else complete()
    }

    private suspend fun complete() {
        val result = completeOnboarding()
        logIfFailed(COMPLETE, result)
        // Once stored the app gate opens Home and clears this ViewModel; a failure stays on the step.
        if (result is Outcome.Failure) local.update { it.copy(busy = false, saveFailed = true) }
    }

    /** Whether the user was asked about usage stats; an unreadable choice is logged and counts as not asked. */
    private suspend fun asked(): Boolean =
        usageStats.consents
            .observe()
            .catch { cause ->
                logFailure(LOAD_CONSENT, cause)
                emit(AnalyticsConsent())
            }.first()
            .asked

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
        editor: EditorUiState?,
        checklistRows: List<ChecklistRow>,
    ): OnboardingUiState {
        val tier = ui.request?.tier ?: ui.chosen ?: stored.baseFeeTier
        val now = clock.now()
        val fees = SnoozePrices.of(tier, stored.maxSnoozes, prices, now, moneyFormatter)
        val form = editor?.form ?: EditorForm()
        return OnboardingUiState(
            step = step,
            baseFeeTier = tier,
            baseFee = fees.baseFee,
            feeLadder = fees.ladder,
            pricesApproximate = fees.approximate,
            baseFeeNote = timeZoneProvider?.takeIf { ui.request == null }?.let { weakeningNoteOf(ui.pendingAfter, now, it.current()) },
            saveFailed = ui.saveFailed,
            alarmTime = form.time,
            repeatDays = form.repeatDays,
            customRepeat = editor?.customRepeat == true,
            ringsTomorrowAt = editor?.ringsTomorrowAt,
            // Story 4.6 under the commitment lock (an edit of the stored first alarm close to its ring), as in the editor.
            firstAlarmNote = editor?.full?.weakeningNote,
            checksNote = editor?.full?.checksNote,
            checks = editor?.full?.toPicker() ?: CheckPickerUiState(checks = form.checks),
            checkSetup = editor?.takeIf { step == OnboardingStep.Checks && it.pane in OVERLAY_PANES },
            reliability = checklistRows,
            manufacturerGuidance = ui.guidance && step == OnboardingStep.Reliability,
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

        /** The first alarm's id, or null to forget it. */
        data class RememberAlarm(
            val id: String?,
        ) : Op

        /** "Continue" on the checklist: the usage stats choice, or (already asked) what follows it. */
        data object LeaveChecklist : Op

        data class UsageStats(
            val share: Boolean,
        ) : Op
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
        /** A write that decides the next step is being stored: a second tap and Back are ignored. */
        val busy: Boolean = false,
        /** The first alarm onboarding stored, if any. */
        val alarmId: String? = null,
        /** The maker guidance shows over step 6. */
        val guidance: Boolean = false,
        /** When [step] was shown, on the monotonic clock; null until the stored step is read. */
        val shownAt: Long? = null,
        /** The last write that decides the next step (or the first alarm's save) failed. */
        val saveFailed: Boolean = false,
        /** The ring the last saved fee waits for under the commitment lock; null when it applied at once. */
        val pendingAfter: Instant? = null,
    )

    companion object {
        /** The last step built so far; step 8, the test alarm, comes with Story 5.12. */
        val LAST_BUILT: OnboardingStep = OnboardingStep.Analytics

        /** The editor panes shown over step 5: Check setup, its "Try it" and QR registration. */
        private val OVERLAY_PANES = setOf(EditorPane.CheckSetup, EditorPane.TryIt, EditorPane.ScanCode)

        const val LOAD_PROGRESS = "load onboarding progress"
        const val LOAD_SETTINGS = "load global settings"
        const val SAVE_STEP = "save onboarding step"
        const val ACCEPT_DISCLOSURE = "accept disclosure"
        const val COMPLETE = "complete onboarding"
        const val SAVE_FEE = "save base fee"
        const val REFRESH_PRICES = "refresh prices"
        const val LOAD_PRICES = "load prices"
        const val REMEMBER_ALARM = "remember onboarding alarm"
        const val LOAD_CONSENT = "load usage stats choice"
        const val SAVE_CONSENT = "save usage stats choice"

        /** How long a step shows before its action counts (review fix): longer than the 250 ms slide and a double tap. */
        const val STEP_DWELL_MILLIS = 500L

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
