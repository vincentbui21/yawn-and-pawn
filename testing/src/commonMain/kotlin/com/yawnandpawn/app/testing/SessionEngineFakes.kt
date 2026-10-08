package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.billing.LivePrice
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.billing.PurchaseIntentStore
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.EntryEffect
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.PurchaseVerdict
import com.yawnandpawn.app.core.session.RuntimeWrite
import com.yawnandpawn.app.core.session.SessionConfig
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.session.UnlockPort
import com.yawnandpawn.app.core.session.UnlockResult
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * In-memory [ActiveSessionStore] with the rules of `RoomActiveSessionStore`: one row of [SessionJson] text, `Idle`
 * deletes it. Set [commitFailure] to make every commit fail (nothing changes), [loadFailure] for load and
 * [clearFailure] for clear (the row stays; [clears] still counts the call). Put any text in
 * [row] (an unreadable one too) to stand for what an earlier process left. [commits] lists every successful commit.
 * The writes of a commit go to [intents] (Story 4.8) and [grants] (Story 4.10) in the same step, and an intent id or a
 * token already stored there fails the whole commit, as in Room; [writes] lists the writes of every successful commit.
 */
class FakeActiveSessionStore(
    initial: SessionState = SessionState.Idle,
    val intents: FakePurchaseIntentStore = FakePurchaseIntentStore(),
    val grants: FakeGrantLedgerStore = FakeGrantLedgerStore(),
) : ActiveSessionStore {
    /** The stored JSON text; null when nothing is stored. */
    var row: String? = initial.takeIf { it != SessionState.Idle }?.let { SessionJson.encode(it) }

    var commitFailure: DomainError? = null
    var loadFailure: DomainError? = null
    var clearFailure: DomainError? = null

    /** Runs after each successful commit, for tests that look at the engine between commit and effects. */
    var onCommit: suspend (SessionState) -> Unit = {}

    private val committed = mutableListOf<SessionState>()
    private val written = mutableListOf<RuntimeWrite>()

    val commits: List<SessionState>
        get() = committed.toList()

    val writes: List<RuntimeWrite>
        get() = written.toList()

    var clears: Int = 0
        private set

    /** The stored state, Idle when nothing is stored, null when the row is unreadable. */
    val stored: SessionState?
        get() =
            when (val decoded = row?.let { SessionJson.decode(it) } ?: StoredSession.Empty) {
                StoredSession.Empty -> SessionState.Idle
                is StoredSession.Found -> decoded.state
                is StoredSession.Unreadable -> null
            }

    override suspend fun load(): Outcome<StoredSession, DomainError> {
        loadFailure?.let { return Outcome.Failure(it) }
        return Outcome.Success(row?.let { SessionJson.decode(it) } ?: StoredSession.Empty)
    }

    override suspend fun commit(
        state: SessionState,
        writes: List<RuntimeWrite>,
    ): Outcome<Unit, DomainError> {
        val newIntents = writes.filterIsInstance<RuntimeWrite.PutPurchaseIntent>().map { it.intent }
        val newGrants = writes.filterIsInstance<RuntimeWrite.PutGrant>().map { it.grant }
        val ids = newIntents.map { it.intentId }
        val tokens = newGrants.map { it.token }
        val duplicate = ids.size != ids.toSet().size || ids.any { intents.contains(it) }
        val duplicateGrant = tokens.size != tokens.toSet().size || tokens.any { grants.contains(it) }
        val failure =
            commitFailure
                ?: DomainError.StorageFailure("UNIQUE constraint failed: purchase_intent.intent_id").takeIf { duplicate }
                ?: DomainError.StorageFailure("UNIQUE constraint failed: grant_ledger.token").takeIf { duplicateGrant }
        failure?.let { return Outcome.Failure(it) }
        newIntents.forEach(intents::put)
        newGrants.forEach(grants::put)
        written += writes
        row = if (state == SessionState.Idle) null else SessionJson.encode(state)
        committed += state
        onCommit(state)
        return Outcome.Success(Unit)
    }

    override suspend fun clear(): Outcome<Unit, DomainError> {
        clears++
        clearFailure?.let { return Outcome.Failure(it) }
        row = null
        return Outcome.Success(Unit)
    }
}

/** One effect a [FakeEffectRunner] received. */
sealed interface RanEffect {
    data class OneShot(
        val effect: SessionEffect,
    ) : RanEffect

    data class Entry(
        val effect: EntryEffect,
    ) : RanEffect
}

/** [EffectRunner] that records every one-shot and entry effect in the order they ran, in [ran]. */
class FakeEffectRunner : EffectRunner {
    private val recorded = mutableListOf<RanEffect>()

    val ran: List<RanEffect>
        get() = recorded.toList()

    val oneShot: List<SessionEffect>
        get() = recorded.filterIsInstance<RanEffect.OneShot>().map { it.effect }

    val entry: List<EntryEffect>
        get() = recorded.filterIsInstance<RanEffect.Entry>().map { it.effect }

    /** Forgets what ran so far. */
    fun reset() {
        recorded.clear()
    }

    override suspend fun run(effect: SessionEffect) {
        recorded += RanEffect.OneShot(effect)
    }

    override suspend fun apply(effect: EntryEffect) {
        recorded += RanEffect.Entry(effect)
    }
}

/**
 * [Billing] under test control: every launch returns [result] (by default [SessionEvent.PurchaseFailed]); use
 * [grants] for a `PurchaseGranted`, or `PurchaseCancelled` / `PurchasePending`. Every intent is kept in [launched].
 * Each consume (Story 4.10) takes the next of [consumeResults] and then [consumeResult] (by default
 * [ConsumeResult.Consumed]); every consumed token is kept in [consumed], in order.
 */
open class FakeBilling(
    var result: SessionEvent.PurchaseEvent = SessionEvent.PurchaseFailed,
    var consumeResult: ConsumeResult = ConsumeResult.Consumed,
) : Billing {
    /** Results for the next consumes, first one first; [consumeResult] once they are used up. */
    val consumeResults: ArrayDeque<ConsumeResult> = ArrayDeque()

    private val consumes = mutableListOf<PurchaseToken>()

    /** Every token passed to [consume], in order (a failed consume too). */
    val consumed: List<PurchaseToken>
        get() = consumes.toList()

    override suspend fun consume(token: PurchaseToken): ConsumeResult {
        consumes += token
        return consumeResults.removeFirstOrNull() ?: consumeResult
    }

    private val intents = mutableListOf<PurchaseIntent>()

    val launched: List<PurchaseIntent>
        get() = intents.toList()

    override suspend fun launch(intent: PurchaseIntent): SessionEvent.PurchaseEvent {
        intents += intent
        return result
    }

    /** How often [init] was called (Story 2.4: once per unlock signal path, idempotent in the real adapter). */
    var initCalls: Int = 0
        private set

    override fun init() {
        initCalls++
    }

    companion object {
        /** A `PurchaseGranted` that the reconciler granted for [productId] with [token]. */
        fun grants(
            productId: String,
            token: String = "token-1",
        ): SessionEvent.PurchaseGranted =
            SessionEvent.PurchaseGranted(
                productId = productId,
                token = PurchaseToken(token),
                verdict = PurchaseVerdict.Grant,
            )
    }
}

/**
 * In-memory [PurchaseIntentStore] with the rules of `RoomPurchaseIntentStore`: lists oldest first, purge strictly before
 * the instant. [FakeActiveSessionStore] writes into it on commit; [put] stands for a row an earlier commit left. Set
 * [failure] to make every read and purge fail with it.
 */
class FakePurchaseIntentStore : PurchaseIntentStore {
    private val intents = linkedMapOf<PurchaseIntentId, PurchaseIntent>()

    var failure: DomainError? = null

    /** Every stored intent, oldest first. */
    val saved: List<PurchaseIntent>
        get() = intents.values.sortedWith(ORDER)

    /** Stores [intent] (test setup, and the fake commit). */
    fun put(intent: PurchaseIntent) {
        intents[intent.intentId] = intent
    }

    fun contains(intentId: PurchaseIntentId): Boolean = intentId in intents

    override suspend fun get(intentId: PurchaseIntentId): Outcome<PurchaseIntent, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        return intents[intentId]?.let { Outcome.Success(it) } ?: Outcome.Failure(DomainError.NotFound(intentId.value))
    }

    override suspend fun forSession(sessionId: String): Outcome<List<PurchaseIntent>, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        return Outcome.Success(saved.filter { it.sessionId == sessionId })
    }

    override suspend fun forProduct(
        sessionId: String,
        productId: String,
    ): Outcome<List<PurchaseIntent>, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        return Outcome.Success(saved.filter { it.sessionId == sessionId && it.productId == productId })
    }

    override suspend fun purgeOlderThan(instant: Instant): Outcome<Int, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        val old = intents.values.filter { it.createdAt < instant }.map { it.intentId }
        old.forEach(intents::remove)
        return Outcome.Success(old.size)
    }

    private companion object {
        val ORDER = compareBy<PurchaseIntent>({ it.createdAt }, { it.intentId.value })
    }
}

/**
 * [UnlockPort] under test control: [locked] is the keyguard, every [requestUnlock] is counted in [requests] and returns
 * [result]. With [hold] set, a request waits for [release] (a PIN prompt still up, or a lost callback when never
 * released).
 */
class FakeUnlockPort(
    var locked: Boolean = true,
    var result: UnlockResult = UnlockResult.Succeeded,
) : UnlockPort {
    var hold: Boolean = false
    var requests: Int = 0
        private set

    private var pending = CompletableDeferred<UnlockResult>()

    override fun isKeyguardLocked(): Boolean = locked

    override suspend fun requestUnlock(): UnlockResult {
        requests++
        return if (hold) pending.await() else result
    }

    /** Ends a held request with [with]. */
    fun release(with: UnlockResult = result) {
        pending.complete(with)
        pending = CompletableDeferred()
    }
}

/** Play's live price of [productId] at a Pay tap: $1.00 unless told otherwise. */
fun aLivePrice(
    productId: String = "snooze_usd_01",
    price: Money = Money.of(1, "USD"),
    formattedPrice: String = "$1.00",
): LivePrice = LivePrice(productId, price, formattedPrice)

/** The `PayConfirmed` of a Pay tap for [intentId] at [livePrice]. */
fun aPayConfirmed(
    intentId: String = "intent-1",
    livePrice: LivePrice = aLivePrice(),
): SessionEvent.PayConfirmed = SessionEvent.PayConfirmed(PurchaseIntentId(intentId), livePrice)

/** A stored [PurchaseIntent]: snooze 1 of [sessionId] for [productId] at a live $1.00, made at [createdAt]. */
fun aPurchaseIntent(
    intentId: String = "intent-1",
    sessionId: String = "session-1",
    productId: String = "snooze_usd_01",
    snoozeNumber: Int = 1,
    price: Money = Money.of(1, "USD"),
    formattedPrice: String = "$1.00",
    createdAt: Instant = DEFAULT_FAKE_INSTANT,
): PurchaseIntent = PurchaseIntent(PurchaseIntentId(intentId), sessionId, productId, snoozeNumber, price, formattedPrice, createdAt)

/** Builds a [SessionConfig] with the alarm defaults (9 min snooze, 20 s grace, one placeholder check step). */
fun aSessionConfig(
    alarmId: String = FakeIdGenerator.fakeUuid(1),
    label: String? = "Work",
    scheduledAt: Instant = DEFAULT_FAKE_INSTANT,
    testMode: Boolean = false,
): SessionConfig =
    SessionConfig(
        alarmId = alarmId,
        label = label,
        scheduledAt = scheduledAt,
        testMode = testMode,
        baseFeeTier = 1,
        maxSnoozes = 5,
        snoozeLengthMinutes = 9,
        graceSeconds = 20,
        vibrateInGrace = false,
        volumePercent = 80,
        gradualVolume = true,
        rampStartPercent = 20,
        soundRef = "builtin:default",
        vibration = true,
        checkPlan = CheckPlan.placeholder(),
    )

/** Builds the [SessionData] of a first ring that started at [startedAt]: interaction deadline 30 min after it. */
fun aSession(
    sessionId: String = FakeIdGenerator.fakeUuid(n = 100),
    config: SessionConfig = aSessionConfig(),
    startedAt: TimeSnapshot = TimeSnapshot(DEFAULT_FAKE_INSTANT.toEpochMilliseconds(), elapsedMillis = 0, bootCount = 1),
): SessionData =
    SessionData(
        sessionId = sessionId,
        config = config,
        ringIndex = 1,
        snoozesGranted = 0,
        checkRun = CheckRun(plan = config.checkPlan, seeds = listOf(1L)),
        interactionDeadline = Deadline.after(startedAt, 30.minutes),
    )

/** One of every [SessionState]: Idle and each active state holding [session]. */
fun everySessionState(session: SessionData = aSession()): List<SessionState> =
    listOf(
        SessionState.Idle,
        SessionState.Ringing(session),
        SessionState.Grace(session.copy(graceEnd = session.interactionDeadline)),
        SessionState.Loud(session),
        SessionState.Snoozed(session.copy(interactionDeadline = null, snoozeEnd = session.interactionDeadline, snoozesGranted = 1)),
        SessionState.Completed(session.copy(interactionDeadline = null)),
        SessionState.Missed(session.copy(interactionDeadline = null)),
    )
