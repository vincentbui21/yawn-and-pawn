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
import com.yawnandpawn.app.core.time.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
 * through [PurchaseLedger] (the only caller of `Billing.consume`), and every snooze through the engine's commit. It owns
 * "the wake screen resumed while the session waits for the unlock" ([resolveLostUnlock]) and the reuse answers
 * ([acceptReuse], [declineReuse]); the confirm sheet (Story 4.13) only calls them.
 *
 * **Alarm safety:** the wake runtime only calls the non-suspending entry points ([onLaunchBilling], [onKeyguardDismiss],
 * [onWakeScreenResumed], [onAppResumed]), which launch the work on [scope] and return at once, so nothing here ever runs
 * inside the engine's Mutex or delays "I'm up", a check or the sound. A late or lost result leaves `paying` set; any
 * result, a restore, the ring's end, the 30-minute timeout or a stale sheet found on resume clears it.
 *
 * **Money safety:**
 * - only a reconciler `Grant` dispatches `PurchaseGranted`;
 * - a `Grant` or reuse offer found while launching cancels the launch, so the user is never charged twice; one sheet is
 *   open at a time and each intent is launched once;
 * - "No charge." is said only for results that are not a payment: a PURCHASED answer that gives no snooze now keeps the
 *   payment in flight, and a later update or recovery grants it;
 * - a stranded token is only ever recorded, unless the user accepts its reuse;
 * - one Mutex serialises the reconciling of launches, updates and recoveries, and the engine refuses a second grant
 *   ledger row for a token, so a duplicate delivery gives one snooze;
 * - nothing is reconciled before the stored session is restored, so a payment for the ringing session is never taken
 *   for a stranded one.
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
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    /** One reconciling at a time: launches, updates and recoveries never decide the same token concurrently. */
    private val mutex = Mutex()

    /** One recovery query at a time; a trigger while one runs is dropped (it would find the same purchases). */
    private val recoveryGate = Mutex()

    private val started = MutableStateFlow(false)

    /** The launch attempt whose sheet is open (or opening). */
    private val inFlight = MutableStateFlow<InFlight?>(null)

    /** Intents whose launch has started in this process: each one opens Play at most once (plus its one retry). */
    private val launchedIntents = MutableStateFlow<Set<PurchaseIntentId>>(emptySet())

    /** The newest unlock request still waiting for its callback in this process. */
    private val unlockWait = MutableStateFlow<UnlockWait?>(null)

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

    /**
     * One launch attempt of [intent]. [opened]: `Billing.launch` returned `Launched`, so the sheet is up and only it can be
     * answered by a cancel, failure or `ITEM_ALREADY_OWNED` update; a result `launch` returned itself is never taken twice.
     * Compared by identity: each attempt (and its opening) is a new object.
     */
    private class InFlight(
        val intent: PurchaseIntent,
        val installId: String,
        val retried: Boolean,
        val opened: Boolean = false,
        val openedAt: Long = 0,
    ) {
        fun opened(at: Long): InFlight = InFlight(intent, installId, retried, opened = true, openedAt = at)
    }

    /** An unlock request for [intentId], made at [since] (monotonic). */
    private class UnlockWait(
        val intentId: PurchaseIntentId,
        val since: Long,
    )

    /**
     * Collects Play's purchase updates on [scope] for the life of the process. Idempotent. One update that fails is logged
     * and the next one is still handled; a stream that fails is collected again after a backoff, so a later purchase is
     * never left ungranted. A stream that ends (no billing adapter) is not collected again.
     */
    fun start() {
        if (!started.compareAndSet(expect = false, update = true)) return
        scope.launch {
            var backoff = COLLECT_RETRY_FIRST
            while (isActive && collectUpdates()) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(COLLECT_RETRY_MAX)
            }
        }
    }

    /** Collects the updates until the stream ends (false) or fails (true, logged). */
    private suspend fun collectUpdates(): Boolean =
        try {
            billing.purchaseUpdates.collect { onUpdateGuarded(it) }
            false
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.log(LogEvent.OperationFailed(UPDATES, e::class.simpleName.orEmpty()))
            true
        }

    private suspend fun onUpdateGuarded(update: PurchaseUpdate) {
        try {
            onUpdate(update)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.log(LogEvent.OperationFailed(UPDATES, e::class.simpleName.orEmpty()))
        }
    }

    /** The `LaunchBilling` effect: launched on [scope], never awaited (the engine's Mutex is held). */
    fun onLaunchBilling(effect: SessionEffect.LaunchBilling) {
        scope.launch { launch(effect.intentId) }
    }

    /** The `RequestKeyguardDismiss` effect: the unlock request for its intent is launched on [scope], never awaited. */
    fun onKeyguardDismiss(effect: SessionEffect.RequestKeyguardDismiss) {
        scope.launch { requestUnlock(effect.intentId) }
    }

    /**
     * The wake screen opened or came back (from Play's sheet, the PIN prompt, Home): settle an unlock whose callback was
     * lost, run a recovery query, then cancel a sheet whose result never came ([STALE_SHEET], default taken).
     */
    fun onWakeScreenResumed() {
        scope.launch {
            resolveLostUnlock()
            val sheet = inFlight.value?.takeIf { it.opened }
            if (recover() && sheet != null) cancelIfStale(sheet)
        }
    }

    /** App start or `MainActivity` resumed: a recovery query (it waits for the session restore). */
    fun onAppResumed() {
        scope.launch { recover() }
    }

    /**
     * Launches the purchase of the committed intent [intentId] (Story 4.11), once per intent:
     * 1. the intent and the install id are read (missing: `PurchaseFailed(Error)`);
     * 2. nothing happens when the session no longer pays this intent;
     * 3. the grant ledger is settled, so a granted token Play still owns is consumed first;
     * 4. the owned purchases are reconciled with `PreLaunch`: a grant, a reuse offer or a pending payment of this product
     *    for this session ends it without opening Play;
     * 5. otherwise Play opens with the install id and the session id.
     * Anything that throws on the way ends the payment with "No charge." (the sheet never opened).
     */
    suspend fun launch(intentId: PurchaseIntentId) {
        if (launchedIntents.getAndUpdate { it + intentId }.contains(intentId)) return
        try {
            prepare(intentId)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.log(LogEvent.OperationFailed(LAUNCH, e::class.simpleName.orEmpty()))
            inFlight.update { it?.takeUnless { open -> open.intent.intentId == intentId } }
            if (paysFor(intentId)) dispatch(SessionEvent.PurchaseFailed(PurchaseFailureKind.Error))
        }
    }

    private suspend fun prepare(intentId: PurchaseIntentId) {
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

    /** Opens Play's sheet for [launch] while the session still pays it and no other sheet is open for it. */
    private suspend fun openSheet(launch: InFlight) {
        val open = inFlight.value
        val anotherOpen = open != null && open.intent.intentId != launch.intent.intentId && paysFor(open.intent.intentId)
        if (!paysFor(launch.intent.intentId) || anotherOpen) return
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
        // An update Play sent while `launch` ran is received now, against this attempt before it counts as open: a result
        // `launch` returned and Play also delivered as an update is handled once (review 12).
        yield()
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
            LaunchResult.Launched -> inFlight.compareAndSet(launch, launch.opened(clock.elapsedMillis()))
            LaunchResult.Cancelled -> finish(launch.intent, SessionEvent.PurchaseCancelled)
            is LaunchResult.Failed -> finish(launch.intent, SessionEvent.PurchaseFailed(result.kind))
            LaunchResult.ItemAlreadyOwned -> alreadyOwned(launch)
        }
    }

    /**
     * `ITEM_ALREADY_OWNED` for [launch]: the owned purchases are reconciled with `AlreadyOwned`. A grant, a reuse offer or
     * a pending payment answers it; a granted token consumed now retries the launch once; anything else (a second
     * `ITEM_ALREADY_OWNED` included, without another query) is `PurchaseFailed(Error)`: nothing was bought.
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
     * One purchase update from Play (collected by [start]). Purchases are reconciled with `Update`, once the stored session
     * is restored. A cancel, a failure or `ITEM_ALREADY_OWNED` answers the sheet that was open when the update arrived, and
     * nothing else. A PURCHASED answer that gives no snooze now (a lookup or commit failure) keeps the payment in flight:
     * it is never called "No charge.", and a later update or recovery grants it (Decision 8).
     */
    suspend fun onUpdate(update: PurchaseUpdate) {
        val sheet = inFlight.value?.takeIf { it.opened }
        when (update) {
            is PurchaseUpdate.Purchases -> {
                engine.restored.first { it }
                onPurchases(update.purchases)
            }

            PurchaseUpdate.Cancelled -> {
                sheet?.let { answer(it, SessionEvent.PurchaseCancelled) }
            }

            is PurchaseUpdate.Failed -> {
                sheet?.let { answer(it, SessionEvent.PurchaseFailed(update.kind)) }
            }

            PurchaseUpdate.ItemAlreadyOwned -> {
                sheet?.takeIf { inFlight.value === it }?.let { alreadyOwned(it) }
            }
        }
        dropStaleInFlight()
    }

    /** The open [sheet] ended with [event], unless another attempt replaced it meanwhile. */
    private suspend fun answer(
        sheet: InFlight,
        event: SessionEvent,
    ) {
        if (inFlight.value === sheet) finish(sheet.intent, event)
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
     * Review 15 (default taken, owner can change): the wake screen is back, a recovery query found nothing for the open
     * [sheet], and it opened [STALE_SHEET] ago or more: its result was lost, so it is cancelled ("Payment cancelled. No
     * charge."), and Snooze can be bought again. A PURCHASED that still comes later grants as any update (row 1).
     */
    private suspend fun cancelIfStale(sheet: InFlight) {
        val stale = clock.elapsedMillis() - sheet.openedAt >= STALE_SHEET.inWholeMilliseconds
        if (stale) answer(sheet, SessionEvent.PurchaseCancelled)
    }

    /**
     * Asks the user to unlock before Play opens for [intentId] (Spike S1) and dispatches `UnlockSucceeded` or
     * `UnlockFailed`; a port that throws counts as failed. A result that comes once the session waits for another intent's
     * unlock (a newer Pay), or for none, is dropped, so it can never apply to a newer payment.
     */
    suspend fun requestUnlock(intentId: PurchaseIntentId) {
        val wait = UnlockWait(intentId, clock.elapsedMillis())
        unlockWait.value = wait
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
            if (unlockingIntent() == intentId) dispatch(result.event())
        } finally {
            unlockWait.compareAndSet(wait, null)
        }
    }

    /**
     * The wake screen resumed while the session waits for the unlock (Story 4.8 deferral; this is the one owner of the
     * rule, Story 4.13 dispatches no unlock result itself): a lost callback, or the user unlocked another way, for example
     * with a fingerprint.
     * - Unlocked now: Play opens (`UnlockSucceeded`); a callback that still comes is dropped, so it opens once.
     * - Locked: the keyguard state can lag the resume (ColorOS), so it is read again after [UNLOCK_SETTLE]. Unlocked then:
     *   `UnlockSucceeded`. Still locked, and no request for this intent waits (lost, or waiting longer than
     *   [UNLOCK_GRACE], so the PIN prompt is not what is up): "Phone still locked. No charge." (`UnlockFailed`). Otherwise
     *   nothing: the PIN prompt may still be up. A new Pay replaces a pending unlock, and "I'm up" never waits.
     */
    suspend fun resolveLostUnlock() {
        val intentId = unlockingIntent() ?: return
        if (keyguardLocked()) delay(UNLOCK_SETTLE)
        if (unlockingIntent() != intentId) return
        val wait = unlockWait.value?.takeIf { it.intentId == intentId }
        val requestWaits = wait != null && clock.elapsedMillis() - wait.since < UNLOCK_GRACE.inWholeMilliseconds
        when {
            !keyguardLocked() -> dispatch(SessionEvent.UnlockSucceeded)
            !requestWaits -> dispatch(SessionEvent.UnlockFailed)
            else -> Unit
        }
    }

    private fun keyguardLocked(): Boolean = runCatching { unlock.isKeyguardLocked() }.getOrDefault(true)

    /** The intent whose unlock the ringing session waits for, or null. */
    private fun unlockingIntent(): PurchaseIntentId? = (engine.state.value as? SessionState.Ring)?.session?.takeIf { it.unlocking }?.paying

    /**
     * "Use it" on the "already paid" sheet (Story 4.13 calls it): `ReuseAccepted` with the offered token, for the session
     * it was offered to. The reducer writes the grant ledger row in the Snoozed commit, and settling it turns the stranded
     * record into reused (Story 4.10). Null when nothing is offered to the session ringing now.
     */
    suspend fun acceptReuse(): Outcome<SessionState, DomainError>? =
        takeOffer()?.let { dispatch(SessionEvent.ReuseAccepted(it.productId, it.token)) }

    /** "Not now", Back or a swipe on the "already paid" sheet (Story 4.13): `ReuseDeclined`. Null when nothing is offered. */
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

        /** The first wait before Play's update stream is collected again after it failed; it doubles up to [COLLECT_RETRY_MAX]. */
        val COLLECT_RETRY_FIRST: Duration = 1.seconds
        val COLLECT_RETRY_MAX: Duration = 1.minutes

        /**
         * How long the keyguard state may lag the wake screen's resume (Story 4.13 device finding on ColorOS) before a
         * locked phone counts as still locked.
         */
        val UNLOCK_SETTLE: Duration = 1_500.milliseconds

        /** An unlock request waiting longer than this is taken as lost: the PIN prompt is not what is up on resume. */
        val UNLOCK_GRACE: Duration = 30.seconds

        /** Review 15 (default taken): a sheet open this long whose result never came is cancelled on the next resume. */
        val STALE_SHEET: Duration = 2.minutes
    }
}
