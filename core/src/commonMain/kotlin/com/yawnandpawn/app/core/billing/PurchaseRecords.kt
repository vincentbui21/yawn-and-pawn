package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.checks.qr.Sha256
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.PurchaseToken
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * The SHA-256 hex digest of the token (lowercase, 64 characters): the only form of a purchase token that `app.db`,
 * which is backed up, ever holds (owner-approved default 2026-09-26). The raw token stays in `runtime.db`.
 */
fun PurchaseToken.hash(): String = Sha256.hex(value.encodeToByteArray())

/**
 * One row of the grant ledger (`grant_ledger` in `runtime.db`, Story 4.10, AD-7): a snooze was granted for [token].
 * `SessionEngine` writes it in the same transaction as the Snoozed state; only `PurchaseLedger` changes it. [status] is
 * [LedgerStatus.Granted] until Play consumed the token, then [LedgerStatus.Consumed]; it is never [LedgerStatus.Absent],
 * which means "no row". Once the payment is settled (recorded and consumed, or found refunded) the row is kept as a
 * settled marker ([settledAt]) for [PurchaseLedger.SETTLED_RETENTION], so the same token can never be granted again:
 * a second insert of it fails the commit. [token] hides itself in `toString`.
 *
 * @property alarmId the alarm of the session, so a replay after the session ended can still fill the record.
 * @property snoozeNumber the snooze this payment bought (`snoozesGranted` after the grant).
 * @property orderId Play's order id, when the grant's event carried one.
 * @property settledAt when the payment was settled; null while it is still pending.
 */
data class GrantLedgerEntry(
    val token: PurchaseToken,
    val sessionId: String,
    val alarmId: String,
    val productId: String,
    val snoozeNumber: Int,
    val orderId: String?,
    val status: LedgerStatus,
    val createdAt: Instant,
    val settledAt: Instant? = null,
) {
    init {
        require(status != LedgerStatus.Absent) { "a ledger row is granted or consumed" }
    }
}

/**
 * Where a record's price came from. Only [Intent] is the amount actually charged (Play's live price at the Pay tap); the
 * others are estimates for a payment with no intent, and are never shown or used as the amount paid (Story 4.10 review).
 */
enum class PriceSource {
    /** The newest intent of the session and product: what the user confirmed and paid. */
    Intent,

    /** The cached Play price of the product (Story 4.3), possibly stale or in another currency. */
    Snapshot,

    /** The product's catalogue tier in USD (`snooze_usd_NN` is NN USD), not the local price charged. */
    Tier,

    /** Nothing known (a product outside the catalogue); the price is zero. */
    Unknown,
    ;

    /** True only when the price is the amount charged. */
    val isAmountPaid: Boolean get() = this == Intent
}

/**
 * One purchase in the user's history (`purchase_record` in `app.db`, backed up, Story 4.10, AD-7, AD-18): keyed by the
 * token's [hash], never the token. [status] is never [RecordStatus.Absent], which means "no record".
 *
 * @property sessionId the session it paid for; for a stranded payment, its profile id (null when Play gave none).
 * @property alarmId the alarm of that session; null for a stranded payment whose alarm is unknown.
 * @property snoozeNumber the snooze it bought; null for a stranded payment with no intent.
 * @property price what was charged when [priceSource] is [PriceSource.Intent]; an estimate otherwise.
 * @property consumedAt when Play consumed the token: set for [RecordStatus.Consumed], and for [RecordStatus.Reused] once
 * the reused payment is consumed; null while granted, stranded or a reuse still in progress.
 */
data class PurchaseRecord(
    val tokenHash: String,
    val orderId: String?,
    val productId: String,
    val sessionId: String?,
    val alarmId: String?,
    val snoozeNumber: Int?,
    val price: Money,
    val priceSource: PriceSource,
    val purchasedAt: Instant,
    val status: RecordStatus,
    val consumedAt: Instant?,
    val updatedAt: Instant,
) {
    init {
        require(status != RecordStatus.Absent) { "a record is granted, consumed, stranded or reused" }
    }

    /**
     * The status the reconciler (Story 4.9) sees: a reused payment not consumed yet is still owed a consume, exactly
     * like a granted one (a restored `app.db` without its ledger row), so it reads as [RecordStatus.Granted].
     */
    val reconcilerStatus: RecordStatus
        get() = if (status == RecordStatus.Reused && consumedAt == null) RecordStatus.Granted else status
}

/**
 * The grant ledger in `runtime.db` (never backed up). Rows are inserted only by `SessionEngine`, through
 * `ActiveSessionStore.commit(state, writes)` in the transaction of the paid snooze; only `PurchaseLedger` uses this port.
 */
interface GrantLedgerStore {
    /** Every row not settled yet, oldest first. */
    suspend fun all(): Outcome<List<GrantLedgerEntry>, DomainError>

    /** The row of [token], settled or not, or null when there is none. */
    suspend fun get(token: PurchaseToken): Outcome<GrantLedgerEntry?, DomainError>

    /** Sets the row of [token] to consumed; nothing when there is no row. */
    suspend fun markConsumed(token: PurchaseToken): Outcome<Unit, DomainError>

    /** Marks the row of [token] settled at [at] (kept as the marker that refuses the token again); nothing when there is none. */
    suspend fun markSettled(
        token: PurchaseToken,
        at: Instant,
    ): Outcome<Unit, DomainError>

    /** Deletes the settled rows settled before [instant]; returns how many. Pending rows are never deleted. */
    suspend fun purgeSettledBefore(instant: Instant): Outcome<Int, DomainError>
}

/**
 * The purchase records in `app.db`. `PurchaseLedger` is the only writer ([putRecord]; a scan test in `:data` enforces
 * it); Purchase history (Story 4.16) and "Problem with a charge?" (Story 4.17) read them.
 */
interface PurchaseRecordRepository {
    /** The record of [tokenHash], or null when there is none. */
    suspend fun get(tokenHash: String): Outcome<PurchaseRecord?, DomainError>

    /** Inserts [record], or replaces the record with the same token hash. */
    suspend fun putRecord(record: PurchaseRecord): Outcome<Unit, DomainError>

    /** Every record, newest purchase first. */
    suspend fun all(): Outcome<List<PurchaseRecord>, DomainError>

    /**
     * Every record, newest purchase first, again after every change (Purchase history, Story 4.16). A read only. A
     * storage failure is thrown into the flow; the collector catches it.
     */
    fun observeAll(): Flow<List<PurchaseRecord>>
}

/**
 * The cached Play price of a product (the price snapshot, Story 4.3), for a record with no intent to price it, or null
 * when nothing is cached. Never the price of a Pay: that is always the intent's live price.
 */
fun interface PriceSnapshotLookup {
    suspend fun priceOf(productId: String): Money?

    companion object {
        /** No cache: records with no intent fall back to the product's USD tier. */
        val None: PriceSnapshotLookup = PriceSnapshotLookup { null }
    }
}
