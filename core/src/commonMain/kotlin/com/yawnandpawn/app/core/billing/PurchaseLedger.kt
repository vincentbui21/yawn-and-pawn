package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundTask
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.TaskResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** How settling a payment ended. */
enum class SettleResult {
    /** Nothing is left to do: recorded, consumed (or already handled) and the ledger row is gone. */
    Settled,

    /** A step failed (storage or Play); the ledger row stays and the payment is settled again later. */
    RetryLater,

    /** Nothing granted this token, so it was not consumed: consuming would keep money for nothing. */
    Refused,
}

/** What the grant ledger and the purchase records hold for one token: the reconciler's lookups (Story 4.9). */
data class PurchaseLookup(
    val ledger: LedgerStatus,
    val record: RecordStatus,
)

/**
 * The only writer of purchase records and the only caller of `Billing.consume` (AD-7, Story 4.10; scan tests in
 * `:data`). It settles every paid snooze in this order, each step safe to repeat after a crash:
 * 1. the Snoozed state and the grant ledger row are already committed together (`SessionEngine`);
 * 2. the purchase record is upserted in `app.db` (granted; a stranded record becomes reused), so the charge is in history
 *    before Play forgets the token, and nothing is consumed while the record cannot be written;
 * 3. Play consumes the token (a repeat consume of a consumed token counts as done), then the ledger row says consumed;
 * 4. the record is marked consumed (a reused record stays reused);
 * 5. the ledger row is deleted.
 *
 * A failed step leaves the ledger row, enqueues the unique [CONSUME_RETRY_JOB] (network, exponential backoff) and is
 * retried on app start and resume ([settleAll]) until it succeeds. Nothing here touches the session: the snooze started
 * at the commit, so a payment that is slow or fails to settle never delays the alarm or "I'm up". One Mutex keeps the
 * triggers (the grant, app start, resume, the background job) from interleaving.
 *
 * Tokens are never logged; records hold only their [hash].
 */
@Suppress("TooManyFunctions")
class PurchaseLedger(
    private val ledger: GrantLedgerStore,
    private val records: PurchaseRecordRepository,
    private val intents: PurchaseIntentStore,
    private val billing: Billing,
    private val work: BackgroundWork,
    private val prices: PriceSnapshotLookup,
    private val clock: Clock,
    private val logger: Logger,
) {
    private val mutex = Mutex()

    /** Settles the granted payment [token] (the `Consume` effect of a paid snooze). Nothing to do without a ledger row. */
    suspend fun settle(token: PurchaseToken): SettleResult =
        mutex.withLock {
            when (val row = ledger.get(token)) {
                is Outcome.Failure -> retryLater(LOAD_LEDGER, row.error)
                is Outcome.Success -> row.value?.let { settleEntry(it) } ?: SettleResult.Settled
            }
        }

    /**
     * Settles every row of the grant ledger, oldest first (restore replay: app start, resume and the background job).
     * [SettleResult.Settled] only when every row is settled.
     */
    suspend fun settleAll(): SettleResult =
        mutex.withLock {
            when (val rows = ledger.all()) {
                is Outcome.Failure -> {
                    retryLater(LOAD_LEDGER, rows.error)
                }

                is Outcome.Success -> {
                    val results = rows.value.map { settleEntry(it) }
                    if (results.all { it == SettleResult.Settled }) SettleResult.Settled else SettleResult.RetryLater
                }
            }
        }

    /**
     * The reconciler said `ConsumeOnly` for [snapshot] (Story 4.9): the same path as a grant. With a ledger row it is
     * settled; without one, only a granted record proves this install granted it (a ledger row lost, for example after a
     * restore), and then it is consumed and the record marked consumed. Anything else is [SettleResult.Refused]: nothing
     * is consumed without a grant. A failure without a ledger row is retried by the next recovery query, not the job.
     */
    suspend fun consumeOnly(snapshot: PurchaseSnapshot): SettleResult =
        mutex.withLock {
            val row =
                when (val found = ledger.get(snapshot.token)) {
                    is Outcome.Failure -> return@withLock retryLater(LOAD_LEDGER, found.error, schedule = false)
                    is Outcome.Success -> found.value
                }
            if (row != null) return@withLock settleEntry(row)
            val record =
                when (val found = records.get(snapshot.token.hash())) {
                    is Outcome.Failure -> return@withLock retryLater(LOAD_RECORD, found.error, schedule = false)
                    is Outcome.Success -> found.value
                }
            if (record?.status != RecordStatus.Granted) {
                logger.log(LogEvent.OperationFailed(CONSUME, "refused: no grant for this token"))
                return@withLock SettleResult.Refused
            }
            if (!consumed(snapshot.token)) return@withLock SettleResult.RetryLater
            val done = record.copy(status = RecordStatus.Consumed, orderId = record.orderId ?: snapshot.orderId, updatedAt = clock.now())
            when (val put = records.put(done)) {
                is Outcome.Failure -> retryLater(WRITE_RECORD, put.error, schedule = false)
                is Outcome.Success -> SettleResult.Settled
            }
        }

    /**
     * The reconciler said `LeaveForAutoRefund` (or `OfferReuse(recordStrandedFirst)`) for [snapshot]: a record with status
     * stranded, never consumed and with no ledger row, so the charge shows in history until Google refunds it. Priced by
     * the newest intent of its profile's session, else the price snapshot. Idempotent: an existing record is returned
     * unchanged, so nothing is ever downgraded to stranded. [alarmId] is the alarm of the profile's session when the
     * caller knows it.
     */
    suspend fun recordStranded(
        snapshot: PurchaseSnapshot,
        alarmId: String? = null,
    ): Outcome<PurchaseRecord, DomainError> =
        mutex.withLock {
            val hash = snapshot.token.hash()
            val existing =
                when (val found = records.get(hash)) {
                    is Outcome.Failure -> return@withLock found.also { log(LOAD_RECORD, it.error) }
                    is Outcome.Success -> found.value
                }
            if (existing != null) return@withLock Outcome.Success(existing)
            val priced = priced(snapshot.profileId, snapshot.productId)
            val record =
                PurchaseRecord(
                    tokenHash = hash,
                    orderId = snapshot.orderId,
                    productId = snapshot.productId,
                    sessionId = snapshot.profileId,
                    alarmId = alarmId,
                    snoozeNumber = priced.snoozeNumber,
                    price = priced.price,
                    purchasedAt = snapshot.purchaseTime,
                    status = RecordStatus.Stranded,
                    updatedAt = clock.now(),
                )
            write(record)
        }

    /**
     * The user reused the stranded payment [tokenHash] for snooze [snoozeNumber] of [sessionId] (Story 4.11): the record
     * becomes reused with that session, alarm and snooze. Idempotent (the same reuse again succeeds); any other starting
     * status is refused with [DomainError.RecordNotReusable], and a missing record with [DomainError.NotFound].
     */
    suspend fun markReused(
        tokenHash: String,
        sessionId: String,
        alarmId: String,
        snoozeNumber: Int,
    ): Outcome<PurchaseRecord, DomainError> =
        mutex.withLock {
            val existing =
                when (val found = records.get(tokenHash)) {
                    is Outcome.Failure -> return@withLock found
                    is Outcome.Success -> found.value ?: return@withLock Outcome.Failure(DomainError.NotFound(tokenHash))
                }
            val sameReuse = existing.sessionId == sessionId && existing.alarmId == alarmId && existing.snoozeNumber == snoozeNumber
            when {
                existing.status == RecordStatus.Stranded -> write(existing.reusedFor(sessionId, alarmId, snoozeNumber))
                existing.status == RecordStatus.Reused && sameReuse -> Outcome.Success(existing)
                else -> Outcome.Failure(DomainError.RecordNotReusable(existing.status.name))
            }
        }

    /** The ledger and record status of [token], for the reconciler's input (Story 4.11). */
    suspend fun lookup(token: PurchaseToken): Outcome<PurchaseLookup, DomainError> {
        val row =
            when (val found = ledger.get(token)) {
                is Outcome.Failure -> return found
                is Outcome.Success -> found.value
            }
        return when (val record = records.get(token.hash())) {
            is Outcome.Failure -> {
                record
            }

            is Outcome.Success -> {
                Outcome.Success(PurchaseLookup(row?.status ?: LedgerStatus.Absent, record.value?.status ?: RecordStatus.Absent))
            }
        }
    }

    /**
     * What the newest stranded payment for [productId] actually cost, from its record: the amount "An earlier {price}
     * payment is being refunded" shows (Story 4.7/4.11). Null when there is none or the records cannot be read.
     */
    suspend fun refundingPrice(productId: String): Money? =
        records
            .all()
            .valueOrNull()
            ?.filter { it.status == RecordStatus.Stranded && it.productId == productId }
            ?.maxByOrNull { it.purchasedAt }
            ?.price

    /** Steps 2 to 5 for one ledger row; the Mutex is held. Each step runs only when the one before succeeded. */
    private suspend fun settleEntry(entry: GrantLedgerEntry): SettleResult {
        val upserted = recordGrant(entry)
        val recorded = upserted.valueOrNull() ?: return retryLater(WRITE_RECORD, (upserted as Outcome.Failure).error)
        val paid = entry.status == LedgerStatus.Consumed || consumedAndNoted(entry.token)
        val finished = paid && markRecordConsumed(recorded)
        return if (finished) deleteRow(entry.token) else retryLater()
    }

    /** Step 3: Play consumes the token, then the ledger row says so. A failed ledger write is only logged. */
    private suspend fun consumedAndNoted(token: PurchaseToken): Boolean {
        if (!consumed(token)) return false
        // The next replay then consumes again, which Play treats as done.
        ledger.markConsumed(token).let { if (it is Outcome.Failure) log(WRITE_LEDGER, it.error) }
        return true
    }

    /** Step 4: a granted record becomes consumed; consumed and reused records stay. False (logged) when the write failed. */
    private suspend fun markRecordConsumed(recorded: PurchaseRecord): Boolean {
        if (recorded.status != RecordStatus.Granted) return true
        val put = records.put(recorded.copy(status = RecordStatus.Consumed, updatedAt = clock.now()))
        if (put is Outcome.Failure) log(WRITE_RECORD, put.error)
        return put is Outcome.Success
    }

    /** Step 5: the ledger row is deleted. */
    private suspend fun deleteRow(token: PurchaseToken): SettleResult =
        when (val deleted = ledger.delete(token)) {
            is Outcome.Failure -> retryLater(WRITE_LEDGER, deleted.error)
            is Outcome.Success -> SettleResult.Settled
        }

    /**
     * Step 2, idempotent by token hash: no record gives a granted one; a stranded one becomes reused by this grant (only
     * `ReuseAccepted` grants a stranded token, Story 4.9 row C); granted, consumed and reused records stay as they are.
     */
    private suspend fun recordGrant(entry: GrantLedgerEntry): Outcome<PurchaseRecord, DomainError> {
        val existing =
            when (val found = records.get(entry.token.hash())) {
                is Outcome.Failure -> return found
                is Outcome.Success -> found.value
            }
        return when (existing?.status) {
            null -> write(grantedRecord(entry))
            RecordStatus.Stranded -> write(existing.reusedFor(entry.sessionId, entry.alarmId, entry.snoozeNumber))
            else -> Outcome.Success(existing)
        }
    }

    private suspend fun grantedRecord(entry: GrantLedgerEntry): PurchaseRecord {
        val priced = priced(entry.sessionId, entry.productId)
        return PurchaseRecord(
            tokenHash = entry.token.hash(),
            orderId = entry.orderId,
            productId = entry.productId,
            sessionId = entry.sessionId,
            alarmId = entry.alarmId,
            snoozeNumber = priced.snoozeNumber ?: entry.snoozeNumber,
            price = priced.price,
            purchasedAt = entry.createdAt,
            status = RecordStatus.Granted,
            updatedAt = clock.now(),
        )
    }

    private fun PurchaseRecord.reusedFor(
        sessionId: String,
        alarmId: String,
        snoozeNumber: Int,
    ): PurchaseRecord =
        copy(
            status = RecordStatus.Reused,
            sessionId = sessionId,
            alarmId = alarmId,
            snoozeNumber = snoozeNumber,
            updatedAt = clock.now(),
        )

    /** What a record of [productId] for [sessionId] costs, and its snooze number when an intent says so. */
    private class Priced(
        val price: Money,
        val snoozeNumber: Int?,
    )

    /**
     * The newest intent of [sessionId] for [productId] (Story 4.8 deferral: a cancelled-PIN or replaced-unlock leftover
     * must not price it), else the price snapshot, else the product's USD tier (the catalogue's own price), else zero.
     */
    private suspend fun priced(
        sessionId: String?,
        productId: String,
    ): Priced {
        val intent =
            sessionId
                ?.let { intents.forProduct(it, productId).valueOrNull() }
                ?.maxWithOrNull(compareBy<PurchaseIntent>({ it.createdAt }, { it.intentId.value }))
        if (intent != null) return Priced(intent.price, intent.snoozeNumber)
        val price =
            prices.priceOf(productId) ?: usdTierPrice(productId) ?: Money(0, USD).also {
                logger.log(LogEvent.OperationFailed(PRICE_RECORD, "no price for $productId"))
            }
        return Priced(price, null)
    }

    private fun usdTierPrice(productId: String): Money? =
        SnoozeProducts.all
            .indexOf(productId)
            .takeIf { it >= 0 }
            ?.let { Money.of(it + 1, USD) }

    /** Consumes [token]; false (logged, never the token) when Play failed or the adapter threw. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun consumed(token: PurchaseToken): Boolean {
        val result =
            try {
                billing.consume(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConsumeResult.Failed(e::class.simpleName ?: "Exception")
            }
        if (result is ConsumeResult.Failed) logger.log(LogEvent.OperationFailed(CONSUME, result.cause))
        return result == ConsumeResult.Consumed
    }

    private suspend fun write(record: PurchaseRecord): Outcome<PurchaseRecord, DomainError> =
        when (val put = records.put(record)) {
            is Outcome.Failure -> put.also { log(WRITE_RECORD, it.error) }
            is Outcome.Success -> Outcome.Success(record)
        }

    /** Logs [error] (if any) and hands the retry to the background job, unless [schedule] is false. */
    private fun retryLater(
        operation: String? = null,
        error: DomainError? = null,
        schedule: Boolean = true,
    ): SettleResult {
        if (operation != null && error != null) log(operation, error)
        if (schedule) {
            val enqueued = work.enqueue(CONSUME_RETRY_JOB)
            if (enqueued is Outcome.Failure) log(ENQUEUE_RETRY, enqueued.error)
        }
        return SettleResult.RetryLater
    }

    private fun log(
        operation: String,
        error: DomainError,
    ) = logger.log(LogEvent.OperationFailed.of(operation, error))

    companion object {
        /** The unique job that settles the grant ledger again: it waits for a network, then backs off from 30 s. */
        val CONSUME_RETRY_JOB: BackgroundJob = BackgroundJob("consume-retry", BackgroundTaskKind.ConsumeRetry, needsNetwork = true)

        const val CONSUME = "consume purchase"
        const val LOAD_LEDGER = "read grant ledger"
        const val WRITE_LEDGER = "write grant ledger"
        const val LOAD_RECORD = "read purchase record"
        const val WRITE_RECORD = "write purchase record"
        const val PRICE_RECORD = "price purchase record"
        const val ENQUEUE_RETRY = "enqueue consume retry"

        private const val USD = "USD"
    }
}

/** The "consume-retry" job (AD-17): settles the grant ledger, and asks to run again later until every row is settled. */
class ConsumeRetryTask(
    private val ledger: PurchaseLedger,
) : BackgroundTask {
    override suspend fun run(): TaskResult = if (ledger.settleAll() == SettleResult.Settled) TaskResult.Done else TaskResult.RetryLater
}

/**
 * The restore replay of the grant ledger (Story 4.10): app start and resume call it. Play is never asked before the first
 * unlock after a boot (AD-15), so it does nothing until then and returns null.
 */
class ReplayGrantLedger(
    private val ledger: PurchaseLedger,
    private val userLock: UserLockState,
) {
    suspend operator fun invoke(): SettleResult? = if (userLock.isUserUnlocked()) ledger.settleAll() else null
}
