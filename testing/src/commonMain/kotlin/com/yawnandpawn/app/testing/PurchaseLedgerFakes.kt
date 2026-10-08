package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.billing.GrantLedgerEntry
import com.yawnandpawn.app.core.billing.GrantLedgerStore
import com.yawnandpawn.app.core.billing.LedgerStatus
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PlayPurchaseState
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.billing.PurchaseSnapshot
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.billing.hash
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundWork
import kotlin.time.Instant

/**
 * In-memory [GrantLedgerStore] with the rules of `RoomGrantLedgerStore` (Story 4.10): rows by token, oldest first.
 * [FakeActiveSessionStore] inserts into it on commit ([put]); set [failure] to make every call fail with it.
 */
class FakeGrantLedgerStore : GrantLedgerStore {
    private val rows = linkedMapOf<PurchaseToken, GrantLedgerEntry>()

    var failure: DomainError? = null

    /** Every row, oldest first. */
    val entries: List<GrantLedgerEntry>
        get() = rows.values.sortedBy { it.createdAt }

    /** Stores [entry] (test setup, and the fake commit). */
    fun put(entry: GrantLedgerEntry) {
        rows[entry.token] = entry
    }

    fun contains(token: PurchaseToken): Boolean = token in rows

    override suspend fun all(): Outcome<List<GrantLedgerEntry>, DomainError> = guarded { entries }

    override suspend fun get(token: PurchaseToken): Outcome<GrantLedgerEntry?, DomainError> = guarded { rows[token] }

    override suspend fun markConsumed(token: PurchaseToken): Outcome<Unit, DomainError> =
        guarded { rows[token]?.let { rows[token] = it.copy(status = LedgerStatus.Consumed) } }

    override suspend fun delete(token: PurchaseToken): Outcome<Unit, DomainError> = guarded { rows.remove(token) }

    private fun <T> guarded(block: () -> T): Outcome<T, DomainError> = failure?.let { Outcome.Failure(it) } ?: Outcome.Success(block())
}

/**
 * In-memory [PurchaseRecordRepository] with the rules of `RoomPurchaseRecordRepository` (Story 4.10): one record per
 * token hash, [all] newest purchase first. Set [failure] to make every call fail with it; [puts] counts the writes.
 */
class FakePurchaseRecordRepository : PurchaseRecordRepository {
    private val rows = linkedMapOf<String, PurchaseRecord>()

    var failure: DomainError? = null

    var puts: Int = 0
        private set

    /** Every record, newest purchase first. */
    val records: List<PurchaseRecord>
        get() = rows.values.sortedByDescending { it.purchasedAt }

    override suspend fun get(tokenHash: String): Outcome<PurchaseRecord?, DomainError> =
        failure?.let { Outcome.Failure(it) } ?: Outcome.Success(rows[tokenHash])

    override suspend fun put(record: PurchaseRecord): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        puts++
        rows[record.tokenHash] = record
        return Outcome.Success(Unit)
    }

    override suspend fun all(): Outcome<List<PurchaseRecord>, DomainError> =
        failure?.let { Outcome.Failure(it) } ?: Outcome.Success(records)
}

/**
 * [BackgroundWork] that keeps every enqueued job in [jobs]; set [failure] to refuse them (for example before the first
 * unlock). Named apart from Story 4.3's `FakeBackgroundWork`, which it can be replaced by once both are merged.
 */
class RecordingBackgroundWork : BackgroundWork {
    private val enqueued = mutableListOf<BackgroundJob>()

    var failure: DomainError? = null

    val jobs: List<BackgroundJob>
        get() = enqueued.toList()

    override fun enqueue(job: BackgroundJob): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        enqueued += job
        return Outcome.Success(Unit)
    }
}

/** A grant ledger row: snooze 1 of [sessionId] for [productId], granted at [createdAt]. */
fun aGrant(
    token: String = "token-1",
    sessionId: String = "session-1",
    alarmId: String = "alarm-1",
    productId: String = "snooze_usd_01",
    snoozeNumber: Int = 1,
    orderId: String? = null,
    status: LedgerStatus = LedgerStatus.Granted,
    createdAt: Instant = DEFAULT_FAKE_INSTANT,
): GrantLedgerEntry = GrantLedgerEntry(PurchaseToken(token), sessionId, alarmId, productId, snoozeNumber, orderId, status, createdAt)

/** A purchase record of [token] (stored by its hash): snooze 1 of [sessionId] at $1.00, [status] granted. */
fun aPurchaseRecord(
    token: String = "token-1",
    status: RecordStatus = RecordStatus.Granted,
    sessionId: String? = "session-1",
    alarmId: String? = "alarm-1",
    snoozeNumber: Int? = 1,
    productId: String = "snooze_usd_01",
    price: Money = Money.of(1, "USD"),
    orderId: String? = null,
    purchasedAt: Instant = DEFAULT_FAKE_INSTANT,
): PurchaseRecord =
    PurchaseRecord(
        tokenHash = PurchaseToken(token).hash(),
        orderId = orderId,
        productId = productId,
        sessionId = sessionId,
        alarmId = alarmId,
        snoozeNumber = snoozeNumber,
        price = price,
        purchasedAt = purchasedAt,
        status = status,
        updatedAt = purchasedAt,
    )

/** A Play purchase of [productId] with [token], launched for the session [profileId]; PURCHASED unless told otherwise. */
fun aPurchaseSnapshot(
    token: String = "token-1",
    productId: String = "snooze_usd_01",
    profileId: String? = "session-1",
    accountId: String? = null,
    orderId: String? = "GPA.1234-5678",
    purchaseTime: Instant = DEFAULT_FAKE_INSTANT,
    pending: Boolean = false,
): PurchaseSnapshot =
    PurchaseSnapshot(
        token = PurchaseToken(token),
        productId = productId,
        purchaseState = if (pending) PlayPurchaseState.Pending else PlayPurchaseState.Purchased,
        profileId = profileId,
        accountId = accountId,
        orderId = orderId,
        purchaseTime = purchaseTime,
    )
