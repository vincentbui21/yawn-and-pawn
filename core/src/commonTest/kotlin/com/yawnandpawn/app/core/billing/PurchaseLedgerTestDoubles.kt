package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundWork
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Instant

// Local doubles for the Story 4.10 ledger tests (core cannot depend on :testing, AD-1).

/** The process dies here. Not an `Exception`, so nothing in `PurchaseLedger` catches it, as with a real kill. */
internal class ProcessDied : Throwable("process died")

/** Where a fake kills the process, right after its own write landed. */
internal enum class CrashPoint {
    /** After the purchase record is first written (granted, or reused from stranded). */
    AfterRecordUpsert,

    /** After Play consumed the token, before anything recorded it. */
    AfterConsume,

    /** After the ledger row says consumed, before the record does. */
    AfterLedgerConsumed,

    /** After the record says consumed, before the ledger row is deleted. */
    AfterRecordConsumed,
}

internal val LEDGER_T0: Instant = Instant.parse("2027-03-10T06:30:00Z")

/** The two databases and Play, kept across "processes": a test builds a new [ledger] over the same stores after a crash. */
internal class LedgerWorld {
    /** `grant_ledger` in runtime.db, by token; the engine's commit inserts into the same map in the engine tests. */
    val ledgerRows: MutableMap<PurchaseToken, GrantLedgerEntry> = linkedMapOf()

    /** `purchase_record` in app.db, by token hash. */
    val recordRows: MutableMap<String, PurchaseRecord> = linkedMapOf()

    val intents = MemoryIntents()
    val play = FakePlay()
    val work = MemoryWork()
    val logged = mutableListOf<LogEvent>()
    val logger = Logger { logged += it }
    var now: Instant = LEDGER_T0
    val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    var crashAt: CrashPoint? = null
    var ledgerFailure: DomainError? = null
    var ledgerWriteFailure: DomainError? = null
    var ledgerDeleteFailure: DomainError? = null
    var recordFailure: DomainError? = null
    var recordWriteFailure: DomainError? = null
    var cachedPrices: Map<String, Money> = emptyMap()

    val ledgerStore = MemoryLedgerStore(this)
    val records = MemoryRecords(this)

    init {
        play.world = this
    }

    /** A ledger as a new process builds it, over the same stores. */
    fun ledger(): PurchaseLedger = PurchaseLedger(ledgerStore, records, intents, play, work, { cachedPrices[it] }, clock, logger)

    fun crash(point: CrashPoint) {
        if (crashAt == point) {
            crashAt = null
            throw ProcessDied()
        }
    }

    /** The one record of [token]; fails when there is none. */
    fun recordOf(token: PurchaseToken = TOKEN_1): PurchaseRecord = recordRows.getValue(token.hash())
}

internal class MemoryLedgerStore(
    private val world: LedgerWorld,
) : GrantLedgerStore {
    override suspend fun all(): Outcome<List<GrantLedgerEntry>, DomainError> =
        world.ledgerFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(world.ledgerRows.values.sortedBy { it.createdAt })

    override suspend fun get(token: PurchaseToken): Outcome<GrantLedgerEntry?, DomainError> =
        world.ledgerFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(world.ledgerRows[token])

    override suspend fun markConsumed(token: PurchaseToken): Outcome<Unit, DomainError> {
        world.ledgerWriteFailure?.let { return Outcome.Failure(it) }
        world.ledgerRows[token]?.let { world.ledgerRows[token] = it.copy(status = LedgerStatus.Consumed) }
        world.crash(CrashPoint.AfterLedgerConsumed)
        return Outcome.Success(Unit)
    }

    override suspend fun delete(token: PurchaseToken): Outcome<Unit, DomainError> {
        world.ledgerDeleteFailure?.let { return Outcome.Failure(it) }
        world.ledgerRows.remove(token)
        return Outcome.Success(Unit)
    }
}

internal class MemoryRecords(
    private val world: LedgerWorld,
) : PurchaseRecordRepository {
    var puts = 0

    override suspend fun get(tokenHash: String): Outcome<PurchaseRecord?, DomainError> =
        world.recordFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(world.recordRows[tokenHash])

    override suspend fun put(record: PurchaseRecord): Outcome<Unit, DomainError> {
        world.recordWriteFailure?.let { return Outcome.Failure(it) }
        puts++
        world.recordRows[record.tokenHash] = record
        world.crash(if (record.status == RecordStatus.Consumed) CrashPoint.AfterRecordConsumed else CrashPoint.AfterRecordUpsert)
        return Outcome.Success(Unit)
    }

    override suspend fun all(): Outcome<List<PurchaseRecord>, DomainError> =
        world.recordFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(world.recordRows.values.sortedByDescending { it.purchasedAt })
}

internal class MemoryIntents : PurchaseIntentStore {
    val rows = mutableListOf<PurchaseIntent>()
    var failure: DomainError? = null

    override suspend fun get(intentId: PurchaseIntentId): Outcome<PurchaseIntent, DomainError> =
        rows.firstOrNull { it.intentId == intentId }?.let { Outcome.Success(it) } ?: Outcome.Failure(DomainError.NotFound(intentId.value))

    override suspend fun forSession(sessionId: String): Outcome<List<PurchaseIntent>, DomainError> =
        Outcome.Success(rows.filter { it.sessionId == sessionId }.sortedBy { it.createdAt })

    override suspend fun forProduct(
        sessionId: String,
        productId: String,
    ): Outcome<List<PurchaseIntent>, DomainError> =
        failure?.let { Outcome.Failure(it) }
            ?: Outcome.Success(rows.filter { it.sessionId == sessionId && it.productId == productId }.sortedBy { it.createdAt })

    override suspend fun purgeOlderThan(instant: Instant): Outcome<Int, DomainError> = Outcome.Success(0)
}

/**
 * Play as far as consuming goes: [owned] tokens are bought and not consumed. A consume takes the next of [results] (then
 * [Consumed][ConsumeResult.Consumed]); a successful one removes the token from [owned], and a repeat consume of a
 * consumed token succeeds, as the adapter maps it. [calls] counts every consume; [hold] makes the next consume wait.
 */
internal class FakePlay : Billing {
    val owned = mutableSetOf<PurchaseToken>()
    val results = ArrayDeque<ConsumeResult>()
    val calls = mutableListOf<PurchaseToken>()
    var throwing: Exception? = null
    var hold: CompletableDeferred<Unit>? = null
    lateinit var world: LedgerWorld

    override suspend fun launch(intent: PurchaseIntent): SessionEvent.PurchaseEvent = SessionEvent.PurchaseFailed

    override suspend fun consume(token: PurchaseToken): ConsumeResult {
        calls += token
        hold?.await()
        throwing?.let { throw it }
        val result = results.removeFirstOrNull() ?: ConsumeResult.Consumed
        if (result == ConsumeResult.Consumed) {
            owned -= token
            if (::world.isInitialized) world.crash(CrashPoint.AfterConsume)
        }
        return result
    }
}

internal class MemoryWork : BackgroundWork {
    val jobs = mutableListOf<BackgroundJob>()
    var failure: DomainError? = null

    override fun enqueue(job: BackgroundJob): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        jobs += job
        return Outcome.Success(Unit)
    }
}

internal val TOKEN_1 = PurchaseToken("play-token-1-secret")
internal val TOKEN_2 = PurchaseToken("play-token-2-secret")
internal const val SESSION = "session-1"
internal const val ALARM = "alarm-1"
internal const val PRODUCT_1 = "snooze_usd_01"
internal const val PRODUCT_3 = "snooze_usd_03"

internal fun grant(
    token: PurchaseToken = TOKEN_1,
    productId: String = PRODUCT_1,
    snoozeNumber: Int = 1,
    status: LedgerStatus = LedgerStatus.Granted,
    orderId: String? = null,
    createdAt: Instant = LEDGER_T0,
): GrantLedgerEntry = GrantLedgerEntry(token, SESSION, ALARM, productId, snoozeNumber, orderId, status, createdAt)

internal fun intent(
    id: String,
    price: Money,
    createdAt: Instant,
    productId: String = PRODUCT_1,
    snoozeNumber: Int = 1,
    sessionId: String = SESSION,
): PurchaseIntent = PurchaseIntent(PurchaseIntentId(id), sessionId, productId, snoozeNumber, price, "x", createdAt)

internal fun record(
    status: RecordStatus,
    token: PurchaseToken = TOKEN_1,
    productId: String = PRODUCT_1,
    sessionId: String? = "other-session",
    alarmId: String? = null,
    snoozeNumber: Int? = null,
    price: Money = Money.of(1, "EUR"),
    purchasedAt: Instant = LEDGER_T0,
): PurchaseRecord =
    PurchaseRecord(token.hash(), "GPA.1", productId, sessionId, alarmId, snoozeNumber, price, purchasedAt, status, purchasedAt)

internal fun paidSnapshot(
    token: PurchaseToken = TOKEN_1,
    productId: String = PRODUCT_1,
    profileId: String? = SESSION,
    orderId: String? = "GPA.9",
    purchaseTime: Instant = LEDGER_T0,
): PurchaseSnapshot = PurchaseSnapshot(token, productId, PlayPurchaseState.Purchased, profileId, null, orderId, purchaseTime)
