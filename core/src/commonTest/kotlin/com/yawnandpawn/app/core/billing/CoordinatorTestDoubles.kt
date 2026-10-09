package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.EngineLogger
import com.yawnandpawn.app.core.session.EngineTime
import com.yawnandpawn.app.core.session.EntryEffect
import com.yawnandpawn.app.core.session.InMemoryHistory
import com.yawnandpawn.app.core.session.InMemorySessionStore
import com.yawnandpawn.app.core.session.OFFER
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionRecorder
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.StepResult
import com.yawnandpawn.app.core.session.UnlockPort
import com.yawnandpawn.app.core.session.UnlockResult
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.session.reducer
import com.yawnandpawn.app.core.session.testConfig
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Instant

// Local doubles for the Story 4.11 coordinator tests (core cannot depend on :testing, AD-1).

/**
 * The phone, Play and both databases across "processes" (Story 4.11): the session store and its grant ledger, the purchase
 * records and Play ([LedgerWorld]) survive a [newProcess]; the engine, the ledger and the coordinator are built again, as
 * a new process builds them. The coordinator runs on the test's background scope, so [settle] runs everything it launched.
 */
internal class CoordinatorWorld(
    private val test: TestScope,
    val availability: SnoozeAvailability = SnoozeAvailability.Available(OFFER),
    val check: StepResult = StepResult.ValidLast,
) {
    val time = EngineTime()
    val store = InMemorySessionStore()
    val world = LedgerWorld(store.grants)
    val play: FakePlay get() = world.play
    val history = InMemoryHistory()
    val logger = EngineLogger()
    val unlock = TestUnlockPort()
    val userLock = TestUserLock()
    val intents = StoreIntents(store)
    var installFailure: DomainError? = null

    /** False: the unlock request is lost on its way (no request waits in this process). */
    var forwardUnlock = true

    /** Every effect the runner got, one-shot and entry, in order, across processes. */
    val ran = mutableListOf<Any>()

    val oneShot: List<SessionEffect> get() = ran.filterIsInstance<SessionEffect>()

    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(time.now.wallMillis)
        }

    lateinit var engine: SessionEngine
        private set
    lateinit var ledger: PurchaseLedger
        private set
    lateinit var coordinator: PurchaseCoordinator
        private set

    private var processJob: Job? = null

    /** The app scope of the current process: [newProcess] cancels it, as a kill ends every coroutine. */
    lateinit var processScope: CoroutineScope
        private set

    init {
        newProcess()
    }

    /** A new process over the same stores and Play; nothing from the old one runs any more. */
    fun newProcess() {
        processJob?.cancel()
        val job = Job(test.backgroundScope.coroutineContext[Job])
        processJob = job
        processScope = CoroutineScope(test.backgroundScope.coroutineContext + job)
        val runner = Runner()
        engine =
            SessionEngine(
                reducer(availability, check),
                store,
                runner,
                SessionRecorder(history),
                time.clock,
                time.monotonicClock,
                time.bootCounter,
                logger,
                userLock = userLock,
                unlock = unlock,
            )
        ledger = PurchaseLedger(world.ledgerStore, world.records, intents, play, world.work, { null }, clock, world.logger)
        coordinator =
            PurchaseCoordinator(
                billing = play,
                engine = engine,
                ledger = ledger,
                intents = intents,
                installIds = { installFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(INSTALL) },
                feeLadder = UsdFeeLadder,
                userLock = userLock,
                unlock = unlock,
                scope = processScope,
                logger = logger,
            )
    }

    /** Runs everything launched so far (coordinator work, settles) as far as it goes now; background work included. */
    fun settle() = test.runCurrent()

    /** Dispatches [event], then lets everything it set off run. */
    suspend fun dispatch(event: SessionEvent): SessionState {
        engine.dispatch(event)
        settle()
        return engine.state.value
    }

    /** The first alarm of the morning rings ([sessionId] defaults to the tests' session). */
    suspend fun ring(sessionId: String = SESSION): SessionState =
        dispatch(SessionEvent.AlarmFired(sessionId, testConfig(), beforeFirstUnlock = false))

    /** The user confirmed paying for snooze 1 at [QUOTE_1] with a new intent [intentId]. */
    suspend fun pay(intentId: String = "intent-1"): SessionState = dispatch(SessionEvent.PayConfirmed(PurchaseIntentId(intentId), QUOTE_1))

    /** Play delivers [update] to the coordinator's collector. */
    suspend fun deliver(update: PurchaseUpdate): SessionState {
        coordinator.onUpdate(update)
        settle()
        return engine.state.value
    }

    /** Play now owns [snapshot] and says so in an update. */
    suspend fun buy(snapshot: PurchaseSnapshot): SessionState {
        play.own(snapshot)
        return deliver(PurchaseUpdate.Purchases(listOf(snapshot)))
    }

    /** A recovery query, as the wake screen or app start runs it. */
    suspend fun recover(): Boolean = coordinator.recover().also { settle() }

    /** The session messages the runner was asked to show, in order. */
    val outcomes: List<SessionEffect> get() =
        oneShot.filter {
            it is SessionEffect.ShowPurchaseOutcome ||
                it is SessionEffect.ShowPaymentPending ||
                it is SessionEffect.ShowReuseSheet ||
                it is SessionEffect.HideReuseSheet
        }

    /** The wake runtime's billing part: effects go to this process's coordinator, `Consume` to its ledger on the scope. */
    private inner class Runner : EffectRunner {
        override suspend fun run(effect: SessionEffect) {
            ran += effect
            when (effect) {
                is SessionEffect.LaunchBilling -> coordinator.onLaunchBilling(effect)
                is SessionEffect.RequestKeyguardDismiss -> if (forwardUnlock) coordinator.onKeyguardDismiss()
                is SessionEffect.Consume -> processScope.launch { ledger.settle(effect.token) }
                else -> Unit
            }
        }

        override suspend fun apply(effect: EntryEffect) {
            ran += effect
        }
    }
}

/** Play's live price of snooze 1 ([PRODUCT_1]) at the Pay tap. */
internal val QUOTE_1 = LivePrice(PRODUCT_1, Money(1_190_000, "EUR"), "€1.19")

/** The engine's intents ([InMemorySessionStore.intents]) as the store port the coordinator and the ledger read. */
internal class StoreIntents(
    private val store: InMemorySessionStore,
) : PurchaseIntentStore {
    var failure: DomainError? = null

    override suspend fun get(intentId: PurchaseIntentId): Outcome<PurchaseIntent, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        return store.intents[intentId]?.let { Outcome.Success(it) } ?: Outcome.Failure(DomainError.NotFound(intentId.value))
    }

    override suspend fun forSession(sessionId: String): Outcome<List<PurchaseIntent>, DomainError> =
        Outcome.Success(store.intents.values.filter { it.sessionId == sessionId })

    override suspend fun forProduct(
        sessionId: String,
        productId: String,
    ): Outcome<List<PurchaseIntent>, DomainError> =
        Outcome.Success(
            store.intents.values.filter {
                it.sessionId == sessionId &&
                    it.productId == productId
            },
        )

    override suspend fun purgeOlderThan(instant: Instant): Outcome<Int, DomainError> = Outcome.Success(0)
}

/** The keyguard: [locked], and each request answers [result], or waits for [release] while [hold] is set. */
internal class TestUnlockPort : UnlockPort {
    var locked = false
    var result = UnlockResult.Succeeded
    var hold = false
    var throwing: Exception? = null
    var requests = 0
    private var pending = CompletableDeferred<UnlockResult>()

    override fun isKeyguardLocked(): Boolean = locked

    override suspend fun requestUnlock(): UnlockResult {
        requests++
        throwing?.let { throw it }
        return if (hold) pending.await() else result
    }

    fun release(with: UnlockResult) {
        pending.complete(with)
        pending = CompletableDeferred()
    }
}

/** The user lock: unlocked unless told otherwise. */
internal class TestUserLock : UserLockState {
    var unlocked = true

    override fun isUserUnlocked(): Boolean = unlocked

    override fun observe(): Flow<Boolean> = flowOf(unlocked)
}

/** A Play purchase of [productId] by this install for [profileId]: PURCHASED unless [pending]. */
internal fun purchase(
    token: String = "tok-1",
    productId: String = PRODUCT_1,
    profileId: String? = SESSION,
    accountId: String? = INSTALL,
    pending: Boolean = false,
    orderId: String? = "GPA.1",
    purchaseTime: Instant = Instant.fromEpochMilliseconds(1_800_000_000_000),
): PurchaseSnapshot =
    PurchaseSnapshot(
        PurchaseToken(token),
        productId,
        if (pending) PlayPurchaseState.Pending else PlayPurchaseState.Purchased,
        profileId,
        accountId,
        orderId,
        purchaseTime,
    )
