package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.PurchaseFailureKind
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.PurchaseVerdict
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.UnlockPort
import com.yawnandpawn.app.core.session.UnlockResult
import com.yawnandpawn.app.core.session.UserLockState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * A stranded payment offered for this snooze (FR-RNG-10): Snooze's "already paid" sheet shows [productId]'s price. The
 * token stays here; [PurchaseCoordinator.acceptReuse] sends it back, so it never reaches the UI.
 */
class ReuseOffer internal constructor(
    val sessionId: String,
    val productId: String,
    internal val token: PurchaseToken,
) {
    override fun toString(): String = "ReuseOffer(sessionId=$sessionId, productId=$productId)"
}

/**
 * The billing orchestration (AD-7, Story 4.11): it carries out the session's `LaunchBilling` and `RequestKeyguardDismiss`
 * effects, follows Play's purchase updates and runs the recovery queries, and turns every result into a session event
 * or a ledger write. Every purchase goes through [PurchaseReconciler] (the only reader of its state), every consume
 * through [PurchaseLedger] (the only caller of `Billing.consume`), and every snooze through the engine's commit.
 *
 * **Alarm safety:** the wake runtime only calls the non-suspending entry points ([onLaunchBilling], [onKeyguardDismiss],
 * [onWakeScreenResumed], [onAppResumed]), which launch the work on [scope] and return at once, so nothing here ever runs
 * inside the engine's Mutex or delays "I'm up", a check or the sound. A late or lost result leaves `paying` set; any
 * result, a restore, the ring's end or the 30-minute timeout clears it, and the free path never depends on it.
 *
 * **Money safety:**
 * - only a reconciler `Grant` dispatches `PurchaseGranted`;
 * - a `Grant` or reuse offer found while launching cancels the launch, so the user is never charged twice;
 * - a stranded token is only ever recorded, unless the user accepts its reuse;
 * - one Mutex serialises the reconciling of launches, updates and recoveries, and the engine refuses a second grant
 *   ledger row for a token, so a duplicate delivery gives one snooze.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class PurchaseCoordinator(
    private val billing: Billing,
    private val engine: SessionEngine,
    private val ledger: PurchaseLedger,
    private val intents: PurchaseIntentStore,
    private val installIds: InstallIdProvider,
    private val feeLadder: FeeLadder,
    private val userLock: UserLockState,
    private val unlock: UnlockPort,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    /** One reconciling at a time: launches, updates and recoveries never decide the same token concurrently. */
    private val mutex = Mutex()

    /** One recovery query at a time; a trigger while one runs is dropped (it would find the same purchases). */
    private val recoveryGate = Mutex()

    private val started = MutableStateFlow(false)

    /** The launch whose sheet is open (or opening), with its install id and whether it is the one retry. */
    private val inFlight = MutableStateFlow<InFlight?>(null)

    /** Unlock requests waiting for their callback in this process. */
    private val unlockRequests = MutableStateFlow(0)

    private val offered = MutableStateFlow<ReuseOffer?>(null)

    private val stranded = MutableStateFlow<Set<String>>(emptySet())

    /** The stranded payment offered for this snooze, if any (Story 4.13's "already paid" sheet). */
    val reuseOffer: StateFlow<ReuseOffer?> = offered.asStateFlow()

    /**
     * The products of this install's unspent payments Play listed in the latest full query (recovery, before a launch,
     * after `ITEM_ALREADY_OWNED`): stranded, waiting for Google's refund or a reuse. Story 4.7's availability shows
     * "An earlier {price} payment is being refunded" for a declined product only while it is in here.
     */
    val strandedProducts: StateFlow<Set<String>> = stranded.asStateFlow()

    private class InFlight(
        val intent: PurchaseIntent,
        val installId: String,
        val retried: Boolean,
    )

    /** Collects Play's purchase updates on [scope] for the life of the process. Idempotent. */
    fun start() {
        if (!started.compareAndSet(expect = false, update = true)) return
        scope.launch {
            billing.purchaseUpdates
                .catch { e -> logger.log(LogEvent.OperationFailed(UPDATES, e::class.simpleName.orEmpty())) }
                .collect { onUpdate(it) }
        }
    }

    /** The `LaunchBilling` effect: launched on [scope], never awaited (the engine's Mutex is held). */
    fun onLaunchBilling(effect: SessionEffect.LaunchBilling) {
        scope.launch { launch(effect.intentId) }
    }

    /** The `RequestKeyguardDismiss` effect: the unlock request is launched on [scope], never awaited. */
    fun onKeyguardDismiss() {
        scope.launch { requestUnlock() }
    }

    /**
     * The wake screen opened or came back (from Play's sheet, the PIN prompt, Home): settle an unlock whose callback was
     * lost, then run a recovery query.
     */
    fun onWakeScreenResumed() {
        scope.launch {
            resolveLostUnlock()
            recover()
        }
    }

    /** App start or `MainActivity` resumed: a recovery query (it waits for the session restore). */
    fun onAppResumed() {
        scope.launch { recover() }
    }

    /**
     * Launches the purchase of the committed intent [intentId] (Story 4.11):
     * 1. the intent and the install id are read (missing: `PurchaseFailed(Error)`);
     * 2. nothing happens when the session no longer pays this intent;
     * 3. the grant ledger is settled, so a granted token Play still owns is consumed first;
     * 4. the owned purchases are reconciled with `PreLaunch`: a grant, a reuse offer or a pending payment of this product
     *    for this session ends it without opening Play;
     * 5. otherwise Play opens with the install id and the session id.
     */
    suspend fun launch(intentId: PurchaseIntentId) {
        val intent =
            when (val found = intents.get(intentId)) {
                is Outcome.Failure -> {
                    log(READ_INTENT, found.error)
                    if (paysFor(intentId)) dispatch(SessionEvent.PurchaseFailed(PurchaseFailureKind.Error))
                    return
                }

                is Outcome.Success -> {
                    found.value
                }
            }
        val installId = installId()
        when {
            installId == null -> finish(intent, SessionEvent.PurchaseFailed(PurchaseFailureKind.Error))
            paysFor(intentId) -> prepareAndLaunch(intent, installId)
            else -> Unit
        }
    }

    private suspend fun prepareAndLaunch(
        intent: PurchaseIntent,
        installId: String,
    ) {
        // A granted payment Play still owns blocks buying the same product again: consume it first (Story 4.10's path).
        ledger.settleAll()
        val found = mutex.withLock { reconcileOwned(ReconcileContext.PreLaunch(intent.productId), intent, installId) }
        if (!found.stopsLaunch) openSheet(InFlight(intent, installId, retried = false))
    }

    /** Opens Play's sheet for [launch] while the session still pays it. */
    private suspend fun openSheet(launch: InFlight) {
        if (!paysFor(launch.intent.intentId)) return
        inFlight.value = launch
        val result =
            try {
                billing.launch(launch.intent, launch.installId)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Outcome.Failure(DomainError.BillingUnavailable(e::class.simpleName ?: "Exception"))
            }
        when (result) {
            is Outcome.Failure -> {
                log(LAUNCH, result.error)
                finish(launch.intent, SessionEvent.PurchaseFailed(PurchaseFailureKind.Error))
            }

            is Outcome.Success -> {
                onLaunchResult(launch, result.value)
            }
        }
    }

    private suspend fun onLaunchResult(
        launch: InFlight,
        result: LaunchResult,
    ) {
        when (result) {
            LaunchResult.Launched -> Unit
            LaunchResult.Cancelled -> finish(launch.intent, SessionEvent.PurchaseCancelled)
            is LaunchResult.Failed -> finish(launch.intent, SessionEvent.PurchaseFailed(result.kind))
            LaunchResult.ItemAlreadyOwned -> alreadyOwned(launch)
        }
    }

    /**
     * `ITEM_ALREADY_OWNED` for [launch]: the owned purchases are reconciled with `AlreadyOwned`. A grant, a reuse offer or
     * a pending payment answers it; a granted token consumed now retries the launch once; anything else (a second
     * `ITEM_ALREADY_OWNED` included) is `PurchaseFailed(Error)`.
     */
    private suspend fun alreadyOwned(launch: InFlight) {
        if (launch.retried) return finish(launch.intent, SessionEvent.PurchaseFailed(PurchaseFailureKind.Error))
        if (!paysFor(launch.intent.intentId)) return dropStaleInFlight()
        val found =
            mutex.withLock {
                reconcileOwned(
                    ReconcileContext.AlreadyOwned(launch.intent.productId),
                    launch.intent,
                    launch.installId,
                )
            }
        when {
            found.stopsLaunch -> dropStaleInFlight()
            found.consumedForRetry -> openSheet(InFlight(launch.intent, launch.installId, retried = true))
            else -> finish(launch.intent, SessionEvent.PurchaseFailed(PurchaseFailureKind.Error))
        }
    }

    /**
     * One purchase update from Play (collected by [start]). Purchases are reconciled with `Update`; a cancel, a failure or
     * `ITEM_ALREADY_OWNED` answers the launch in flight, and nothing else.
     */
    suspend fun onUpdate(update: PurchaseUpdate) {
        val launch = inFlight.value
        when (update) {
            is PurchaseUpdate.Purchases -> {
                onPurchases(update.purchases)
                // Play answered the open sheet with a purchase that gave no session event: never leave `paying` stuck.
                if (launch != null && paysFor(launch.intent.intentId) && update.purchases.any { it.answers(launch.intent) }) {
                    finish(launch.intent, SessionEvent.PurchaseFailed(PurchaseFailureKind.Error))
                }
            }

            PurchaseUpdate.Cancelled -> {
                launch?.let { finish(it.intent, SessionEvent.PurchaseCancelled) }
            }

            is PurchaseUpdate.Failed -> {
                launch?.let { finish(it.intent, SessionEvent.PurchaseFailed(update.kind)) }
            }

            PurchaseUpdate.ItemAlreadyOwned -> {
                launch?.let { alreadyOwned(it) }
            }
        }
        dropStaleInFlight()
    }

    private suspend fun onPurchases(purchases: List<PurchaseSnapshot>) {
        mutex.withLock {
            val installId = installId() ?: return
            val pass = Pass(launching = null)
            purchases.forEach { reconcileOne(it, ReconcileContext.Update, pass, installId) }
        }
    }

    /**
     * The recovery query (app start, `MainActivity` resume, every wake screen resume): every purchase Play lists is
     * reconciled with `Recovery`. Never before the first unlock (AD-15), and only once the engine has restored the stored
     * session, so a lost callback for the ringing session is granted, never recorded stranded (Story 4.9 deferral). One at
     * a time. True when a query was reconciled.
     */
    suspend fun recover(): Boolean {
        if (!userLock.isUserUnlocked()) return false
        engine.restored.first { it }
        return recoveryGate.tryLock() &&
            try {
                installId()?.let { id ->
                    mutex.withLock { reconcileOwned(ReconcileContext.Recovery, launching = null, installId = id) }.queried
                } == true
            } finally {
                recoveryGate.unlock()
                dropStaleInFlight()
            }
    }

    /**
     * Asks the user to unlock before Play opens (Spike S1) and dispatches `UnlockSucceeded` or `UnlockFailed`; a port that
     * throws counts as failed.
     */
    suspend fun requestUnlock() {
        unlockRequests.update { it + 1 }
        try {
            val result =
                try {
                    unlock.requestUnlock()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    logger.log(LogEvent.OperationFailed(UNLOCK, e::class.simpleName.orEmpty()))
                    UnlockResult.Failed
                }
            dispatch(result.event())
        } finally {
            unlockRequests.update { it - 1 }
        }
    }

    /**
     * The wake screen resumed while the session waits for the unlock (Story 4.8 deferral: a lost callback, or the user
     * unlocked another way, for example with a fingerprint):
     * - unlocked now: Play opens (`UnlockSucceeded`); a callback that still comes is ignored, so it opens once;
     * - still locked, and no request in this process waits for its callback: "Phone still locked. No charge."
     *   (`UnlockFailed`);
     * - still locked while a request waits (the PIN prompt may be up): nothing. A new Pay replaces a pending unlock, and
     *   "I'm up" never waits for it.
     */
    suspend fun resolveLostUnlock() {
        val ring = engine.state.value as? SessionState.Ring ?: return
        if (!ring.session.unlocking) return
        val locked = runCatching { unlock.isKeyguardLocked() }.getOrDefault(true)
        when {
            !locked -> dispatch(SessionEvent.UnlockSucceeded)
            unlockRequests.value == 0 -> dispatch(SessionEvent.UnlockFailed)
            else -> Unit
        }
    }

    /**
     * "Use it" on the "already paid" sheet: `ReuseAccepted` with the offered token, for the session it was offered to.
     * The reducer writes the grant ledger row in the Snoozed commit, and settling it turns the stranded record into
     * reused (Story 4.10). Null when nothing is offered to the session ringing now.
     */
    suspend fun acceptReuse(): Outcome<SessionState, DomainError>? =
        takeOffer()?.let { dispatch(SessionEvent.ReuseAccepted(it.productId, it.token)) }

    /** "Not now", Back or a swipe on the "already paid" sheet: `ReuseDeclined`. Null when nothing is offered. */
    suspend fun declineReuse(): Outcome<SessionState, DomainError>? =
        takeOffer()?.let { dispatch(SessionEvent.ReuseDeclined(it.productId)) }

    private fun takeOffer(): ReuseOffer? {
        val offer = offered.getAndUpdate { null } ?: return null
        val ringing = (engine.state.value as? SessionState.Ring)?.session?.sessionId
        return offer.takeIf { it.sessionId == ringing }
    }

    /** What one reconciling pass did. */
    private class Pass(
        val launching: PurchaseIntent?,
    ) {
        /** A grant, a reuse offer or a pending payment answered the launch: Play must not open. */
        var stopsLaunch = false

        /** `ConsumeOnly(retryLaunch)` settled, so the launch is retried once. */
        var consumedForRetry = false

        var offered = false
        val strandedProducts = mutableSetOf<String>()
    }

    /** The outcome of a full query: [queried] false when Play could not be asked. */
    private class Reconciled(
        val queried: Boolean,
        val stopsLaunch: Boolean,
        val consumedForRetry: Boolean,
    )

    /**
     * Queries Play and reconciles every owned purchase with [context]; [launching] is the intent being launched, if any.
     * The stranded set is refreshed from this full list. The Mutex is held.
     */
    private suspend fun reconcileOwned(
        context: ReconcileContext,
        launching: PurchaseIntent?,
        installId: String,
    ): Reconciled {
        val owned = query() ?: return Reconciled(queried = false, stopsLaunch = false, consumedForRetry = false)
        val pass = Pass(launching)
        owned.forEach { reconcileOne(it, context, pass, installId) }
        stranded.value = pass.strandedProducts.toSet()
        return Reconciled(queried = true, stopsLaunch = pass.stopsLaunch, consumedForRetry = pass.consumedForRetry)
    }

    /** Decides [snapshot] against the ledger, the record and the session now, and carries the decision out. */
    private suspend fun reconcileOne(
        snapshot: PurchaseSnapshot,
        context: ReconcileContext,
        pass: Pass,
        installId: String,
    ) {
        val lookup =
            when (val found = ledger.lookup(snapshot.token)) {
                // Never decide without knowing what the ledger holds: the next update or recovery tries again.
                is Outcome.Failure -> return log(LOOKUP, found.error)

                is Outcome.Success -> found.value
            }
        val state = engine.state.value
        val expectedNext = (state as? SessionState.Active)?.session?.let { feeLadder.expectedNextProduct(it) }
        val input = ReconcileInput(snapshot, ActiveSessionSummary.of(state, expectedNext), lookup.ledger, lookup.record, context, installId)
        apply(snapshot, PurchaseReconciler.decide(input), state, pass)
    }

    private suspend fun apply(
        snapshot: PurchaseSnapshot,
        decision: PurchaseDecision,
        state: SessionState,
        pass: Pass,
    ) {
        when (decision) {
            is PurchaseDecision.Grant -> {
                dispatch(SessionEvent.PurchaseGranted(snapshot.productId, snapshot.token, PurchaseVerdict.Grant, snapshot.orderId))
                // The snooze is paid by this token: opening Play now would charge a second time (Story 4.9 row A).
                if (decision.abortLaunch) pass.stopsLaunch = true
            }

            is PurchaseDecision.ConsumeOnly -> {
                val settled = ledger.consumeOnly(snapshot) == SettleResult.Settled
                if (decision.retryLaunch && settled) pass.consumedForRetry = true
            }

            PurchaseDecision.LeaveForAutoRefund -> {
                pass.strandedProducts += snapshot.productId
                recordStranded(snapshot, state)
            }

            is PurchaseDecision.OfferReuse -> {
                pass.strandedProducts += snapshot.productId
                offerReuse(snapshot, decision, state, pass)
            }

            is PurchaseDecision.Ignore -> {
                if (decision.reason == IgnoreReason.Pending) pending(snapshot, state, pass)
            }
        }
    }

    /**
     * The first reusable token of a launch is offered (`ReuseOffered`), recorded stranded first when it has no record
     * (Story 4.9 row 11), and the launch stops; any further one is only recorded.
     */
    private suspend fun offerReuse(
        snapshot: PurchaseSnapshot,
        decision: PurchaseDecision.OfferReuse,
        state: SessionState,
        pass: Pass,
    ) {
        val session = (state as? SessionState.Ring)?.session
        if (pass.offered || pass.launching == null || session == null) {
            recordStranded(snapshot, state)
            return
        }
        // A record that cannot be written now does not stop the offer: settling the reuse writes a granted record then.
        if (decision.recordStrandedFirst) recordStranded(snapshot, state)
        offered.value = ReuseOffer(session.sessionId, snapshot.productId, snapshot.token)
        pass.offered = true
        pass.stopsLaunch = true
        dispatch(SessionEvent.ReuseOffered(snapshot.productId, snapshot.token, PurchaseVerdict.OfferReuse))
    }

    /**
     * A pending payment for the ringing session: `PurchasePending` ("Payment not confirmed yet"). Once per session unless
     * it answers the launch (or the open sheet) of that product, which it then stops.
     */
    private suspend fun pending(
        snapshot: PurchaseSnapshot,
        state: SessionState,
        pass: Pass,
    ) {
        val session = (state as? SessionState.Ring)?.session ?: return
        if (snapshot.profileId != session.sessionId) return
        val answersLaunch = pass.launching?.let { it.productId == snapshot.productId } == true
        val answersSheet = inFlight.value?.let { paysFor(it.intent.intentId) && snapshot.answers(it.intent) } == true
        if (answersLaunch) pass.stopsLaunch = true
        if (answersLaunch || answersSheet || !session.paymentPending) dispatch(SessionEvent.PurchasePending)
    }

    private suspend fun recordStranded(
        snapshot: PurchaseSnapshot,
        state: SessionState,
    ) {
        val alarmId =
            (state as? SessionState.Active)
                ?.session
                ?.takeIf { it.sessionId == snapshot.profileId }
                ?.config
                ?.alarmId
        val recorded = ledger.recordStranded(snapshot, alarmId)
        if (recorded is Outcome.Failure) log(RECORD_STRANDED, recorded.error)
    }

    /** Ends the launch of [intent] with [event] while the session still pays it. */
    private suspend fun finish(
        intent: PurchaseIntent,
        event: SessionEvent,
    ) {
        if (inFlight.value?.intent == intent) inFlight.value = null
        if (paysFor(intent.intentId)) dispatch(event)
    }

    /** Forgets a launch the session no longer pays (a result, a grant, a restore or the ring's end came in). */
    private fun dropStaleInFlight() {
        inFlight.update { launch -> launch?.takeIf { paysFor(it.intent.intentId) } }
    }

    /** The session rings and pays [intentId] with Play's sheet (not only the unlock) under way. */
    private fun paysFor(intentId: PurchaseIntentId): Boolean =
        (engine.state.value as? SessionState.Ring)?.session?.let { it.paying == intentId && !it.unlocking } == true

    /** The purchase is the one [intent] launched: the same session and product. */
    private fun PurchaseSnapshot.answers(intent: PurchaseIntent): Boolean = profileId == intent.sessionId && productId == intent.productId

    @Suppress("TooGenericExceptionCaught")
    private suspend fun query(): List<PurchaseSnapshot>? {
        val listed =
            try {
                billing.queryPurchases()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failure(DomainError.BillingUnavailable(e::class.simpleName ?: "Exception"))
            }
        return when (listed) {
            is Outcome.Failure -> null.also { log(QUERY, listed.error) }
            is Outcome.Success -> listed.value
        }
    }

    private suspend fun installId(): String? =
        when (val id = installIds.installId()) {
            is Outcome.Failure -> null.also { log(INSTALL_ID, id.error) }
            is Outcome.Success -> id.value
        }

    /** Dispatches [event]; a failed commit is logged by the engine and here, and the next update or recovery retries. */
    private suspend fun dispatch(event: SessionEvent): Outcome<SessionState, DomainError> =
        engine.dispatch(event).also { if (it is Outcome.Failure) log(DISPATCH, it.error) }

    private fun log(
        operation: String,
        error: DomainError,
    ) = logger.log(LogEvent.OperationFailed.of(operation, error))

    companion object {
        const val READ_INTENT = "read purchase intent"
        const val INSTALL_ID = "read install id"
        const val LAUNCH = "launch billing"
        const val QUERY = "query purchases"
        const val LOOKUP = "look up purchase"
        const val RECORD_STRANDED = "record stranded purchase"
        const val DISPATCH = "dispatch billing result"
        const val UNLOCK = "request unlock"
        const val UPDATES = "purchase updates"
    }
}
