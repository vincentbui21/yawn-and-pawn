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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** How settling a payment ended. */
enum class SettleResult {
    /** Nothing is left to do: recorded, consumed (or found refunded) and the ledger row is a settled marker. */
    Settled,

    /** A step failed (storage or Play); the ledger row stays pending and the payment is settled again later. */
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
 * 3. Play consumes the token, then the ledger row says consumed. `NotOwned` counts as consumed while the payment is
 *    younger than [AUTO_REFUND_SAFE_AGE] (our own earlier consume); older, Google refunded it, so the record becomes
 *    stranded ("refunded automatically by Google") and nothing shows it as paid;
 * 4. the record is marked consumed (a reused record stays reused and gets its consume time);
 * 5. the ledger row becomes a settled marker, kept [SETTLED_RETENTION], so the token can never grant again.
 *
 * A failed step leaves the row pending, enqueues the unique [CONSUME_RETRY_JOB] (network, exponential backoff) and the
 * periodic [CONSUME_RETRY_PERIODIC] (so retries go on after the one-time job gives up), and the ledger is replayed on app
 * start, at every unlock signal and wake screen resume, and on `MainActivity` start ([settleAll]). Nothing here touches
 * the session: the snooze started at the commit, so a payment that is slow or fails to settle never delays the alarm or
 * "I'm up". One Mutex keeps those triggers from interleaving.
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

    /** Hashes of the rows already reported as unsettled after [UNSETTLED_ALERT_AGE], so each is logged once per process. */
    private val alerted = mutableSetOf<String>()

    /** Settles the granted payment [token] (the `Consume` effect of a paid snooze). Nothing to do without a pending row. */
    suspend fun settle(token: PurchaseToken): SettleResult =
        mutex.withLock {
            when (val row = ledger.get(token)) {
                is Outcome.Failure -> retryLater(LOAD_LEDGER, row.error)
                is Outcome.Success -> row.value?.let { settleEntry(it) } ?: SettleResult.Settled
            }
        }

    /**
     * Settles every pending row of the grant ledger, oldest first (restore replay and the background jobs).
     * [SettleResult.Settled] only when every row is settled. A row still pending after [UNSETTLED_ALERT_AGE] is logged
     * once per process.
     */
    suspend fun settleAll(): SettleResult =
        mutex.withLock {
            when (val rows = ledger.all()) {
                is Outcome.Failure -> {
                    retryLater(LOAD_LEDGER, rows.error)
                }

                is Outcome.Success -> {
                    rows.value.forEach(::alertIfOld)
                    val results = rows.value.map { settleEntry(it) }
                    if (results.all { it == SettleResult.Settled }) SettleResult.Settled else SettleResult.RetryLater
                }
            }
        }

    /** App start: deletes the settled markers older than [SETTLED_RETENTION]. A failure is logged only. */
    suspend fun purgeSettled(): Outcome<Int, DomainError> =
        mutex.withLock {
            ledger.purgeSettledBefore(clock.now() - SETTLED_RETENTION).also { if (it is Outcome.Failure) log(WRITE_LEDGER, it.error) }
        }

    /**
     * The reconciler said `ConsumeOnly` for [snapshot] (Story 4.9): the same path as a grant. With a ledger row it is
     * settled; without one, only a record still owed a consume (granted, or reused and not consumed) proves this install
     * granted it (a ledger row lost, for example after a restore), and then it is consumed and the record marked so.
     * Anything else is [SettleResult.Refused]: nothing is consumed without a grant. A failure without a ledger row is
     * retried by the next recovery query, not the job.
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
            if (record?.reconcilerStatus != RecordStatus.Granted) {
                logger.log(LogEvent.OperationFailed(CONSUME, "refused: no grant for this token"))
                return@withLock SettleResult.Refused
            }
            val withOrder = record.copy(orderId = record.orderId ?: snapshot.orderId)
            val done =
                when (consume(snapshot.token, record)) {
                    Paid.Yes -> markRecordConsumed(withOrder)
                    Paid.Refunded -> markRefunded(withOrder)
                    Paid.Failed -> false
                }
            if (done) SettleResult.Settled else SettleResult.RetryLater
        }

    /**
     * The reconciler said `LeaveForAutoRefund` (or `OfferReuse(recordStrandedFirst)`) for [snapshot]: a record with status
     * stranded, never consumed and with no ledger row, so the charge shows in history until Google refunds it. Priced by
     * the newest intent of its profile's session; otherwise the price is only an estimate ([PriceSource]). Idempotent: an
     * existing record is returned unchanged, so nothing is ever downgraded to stranded. [alarmId] is the alarm of the
     * profile's session when the caller knows it.
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
                    priceSource = priced.source,
                    purchasedAt = snapshot.purchaseTime,
                    status = RecordStatus.Stranded,
                    consumedAt = null,
                    updatedAt = clock.now(),
                )
            write(record)
        }

    /**
     * The stranded payment [tokenHash] was reused for snooze [snoozeNumber] of [sessionId] (Story 4.11): the record
     * becomes reused with that session, alarm and snooze. Only once the reuse is committed: the grant ledger must hold a
     * pending row for this token and session (`ReuseAccepted` wrote it), otherwise it is refused with
     * [DomainError.NotFound] and the record stays stranded, so a crash before the commit can never leave a "reused" record
     * for nothing. Settling that row makes the record reused anyway; this is for a caller that needs it earlier.
     * Idempotent (the same reuse again succeeds); any starting status other than stranded is [DomainError.RecordNotReusable].
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
                existing.status == RecordStatus.Reused && sameReuse -> Outcome.Success(existing)
                existing.status != RecordStatus.Stranded -> Outcome.Failure(DomainError.RecordNotReusable(existing.status.name))
                !hasPendingGrant(tokenHash, sessionId) -> Outcome.Failure(DomainError.NotFound("grant for the reuse"))
                else -> write(existing.reusedFor(sessionId, alarmId, snoozeNumber))
            }
        }

    /**
     * The ledger and record status of [token], for the reconciler's input (Story 4.11). A settled marker reads as
     * consumed (already handled), and a reused record still owed a consume reads as granted ([PurchaseRecord.reconcilerStatus]).
     */
    suspend fun lookup(token: PurchaseToken): Outcome<PurchaseLookup, DomainError> {
        val row =
            when (val found = ledger.get(token)) {
                is Outcome.Failure -> return found
                is Outcome.Success -> found.value
            }
        val ledgerStatus =
            when {
                row == null -> LedgerStatus.Absent
                row.settledAt != null -> LedgerStatus.Consumed
                else -> row.status
            }
        return when (val record = records.get(token.hash())) {
            is Outcome.Failure -> record
            is Outcome.Success -> Outcome.Success(PurchaseLookup(ledgerStatus, record.value?.reconcilerStatus ?: RecordStatus.Absent))
        }
    }

    /**
     * What the newest stranded payment for [productId] actually cost, from its record: the amount "An earlier {price}
     * payment is being refunded" shows (Story 4.7/4.11). Null when there is none, when its price is only an estimate (no
     * intent priced it, so the label must not name an amount), or when the records cannot be read.
     */
    suspend fun refundingPrice(productId: String): Money? =
        records
            .all()
            .valueOrNull()
            ?.filter { it.status == RecordStatus.Stranded && it.productId == productId }
            ?.maxByOrNull { it.purchasedAt }
            ?.takeIf { it.priceSource.isAmountPaid }
            ?.price

    /** Steps 2 to 5 for one ledger row; the Mutex is held. Each step runs only when the one before succeeded. */
    private suspend fun settleEntry(entry: GrantLedgerEntry): SettleResult {
        if (entry.settledAt != null) return SettleResult.Settled
        return when (val upserted = recordGrant(entry)) {
            is Outcome.Failure -> retryLater(WRITE_RECORD, upserted.error)
            is Outcome.Success -> settleRecorded(entry, upserted.value)
        }
    }

    /** Steps 3 to 5, once the record is written. */
    private suspend fun settleRecorded(
        entry: GrantLedgerEntry,
        recorded: PurchaseRecord,
    ): SettleResult {
        val owed = entry.status == LedgerStatus.Granted
        val paid = if (owed) consume(entry.token, recorded) else Paid.Yes
        // A failed ledger write is only logged: the next replay consumes again, which Play answers with NotOwned.
        if (owed && paid == Paid.Yes) ledger.markConsumed(entry.token).let { if (it is Outcome.Failure) log(WRITE_LEDGER, it.error) }
        val finished =
            when (paid) {
                Paid.Yes -> markRecordConsumed(recorded)
                Paid.Refunded -> markRefunded(recorded)
                Paid.Failed -> false
            }
        return if (finished) markSettled(entry.token) else retryLater()
    }

    /** How step 3 ended. */
    private enum class Paid { Yes, Refunded, Failed }

    /**
     * Step 3: Play consumes [token]. `NotOwned` is our own earlier consume while [record] is younger than
     * [AUTO_REFUND_SAFE_AGE], and Google's refund after that (logged). A failure (or a throwing adapter) is logged, never
     * with the token.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun consume(
        token: PurchaseToken,
        record: PurchaseRecord,
    ): Paid {
        val result =
            try {
                billing.consume(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConsumeResult.Failed(e::class.simpleName ?: "Exception")
            }
        return when (result) {
            ConsumeResult.Consumed -> {
                Paid.Yes
            }

            ConsumeResult.NotOwned -> {
                val refunded = clock.now() - record.purchasedAt >= AUTO_REFUND_SAFE_AGE
                if (refunded) logger.log(LogEvent.OperationFailed(CONSUME, "not owned after ${AUTO_REFUND_SAFE_AGE}: refunded by Google"))
                if (refunded) Paid.Refunded else Paid.Yes
            }

            is ConsumeResult.Failed -> {
                logger.log(LogEvent.OperationFailed(CONSUME, result.cause))
                Paid.Failed
            }
        }
    }

    /** Step 4: a granted record becomes consumed, a reused one gets its consume time. False (logged) when the write failed. */
    private suspend fun markRecordConsumed(recorded: PurchaseRecord): Boolean {
        val now = clock.now()
        val done =
            when {
                recorded.status == RecordStatus.Granted -> recorded.copy(status = RecordStatus.Consumed, consumedAt = now, updatedAt = now)
                recorded.status == RecordStatus.Reused && recorded.consumedAt == null -> recorded.copy(consumedAt = now, updatedAt = now)
                else -> return true
            }
        return write(done) is Outcome.Success
    }

    /** Google refunded the payment before it was consumed: the record says stranded, so history never shows it as paid. */
    private suspend fun markRefunded(recorded: PurchaseRecord): Boolean {
        if (recorded.status == RecordStatus.Stranded) return true
        return write(recorded.copy(status = RecordStatus.Stranded, consumedAt = null, updatedAt = clock.now())) is Outcome.Success
    }

    /** Step 5: the ledger row becomes the settled marker. */
    private suspend fun markSettled(token: PurchaseToken): SettleResult =
        when (val settled = ledger.markSettled(token, clock.now())) {
            is Outcome.Failure -> retryLater(WRITE_LEDGER, settled.error)
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
            priceSource = priced.source,
            purchasedAt = entry.createdAt,
            status = RecordStatus.Granted,
            consumedAt = null,
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
            consumedAt = null,
            updatedAt = clock.now(),
        )

    private suspend fun hasPendingGrant(
        tokenHash: String,
        sessionId: String,
    ): Boolean =
        ledger
            .all()
            .valueOrNull()
            .orEmpty()
            .any { it.token.hash() == tokenHash && it.sessionId == sessionId }

    private fun alertIfOld(row: GrantLedgerEntry) {
        val hash = row.token.hash()
        if (clock.now() - row.createdAt >= UNSETTLED_ALERT_AGE && alerted.add(hash)) {
            logger.log(LogEvent.OperationFailed(UNSETTLED, "a granted payment is unsettled after $UNSETTLED_ALERT_AGE"))
        }
    }

    /** What a record of [productId] for [sessionId] costs, where that came from, and its snooze number when an intent says so. */
    private class Priced(
        val price: Money,
        val source: PriceSource,
        val snoozeNumber: Int?,
    )

    /**
     * The newest intent of [sessionId] for [productId] (Story 4.8 deferral: a cancelled-PIN or replaced-unlock leftover
     * must not price it), else the estimates: the price snapshot, the product's USD tier, zero.
     */
    private suspend fun priced(
        sessionId: String?,
        productId: String,
    ): Priced {
        val intent =
            sessionId
                ?.let { intents.forProduct(it, productId).valueOrNull() }
                ?.maxWithOrNull(compareBy<PurchaseIntent>({ it.createdAt }, { it.intentId.value }))
        return intent?.let { Priced(it.price, PriceSource.Intent, it.snoozeNumber) }
            ?: prices.priceOf(productId)?.let { Priced(it, PriceSource.Snapshot, null) }
            ?: usdTierPrice(productId)?.let { Priced(it, PriceSource.Tier, null) }
            ?: Priced(Money(0, USD), PriceSource.Unknown, null).also {
                logger.log(LogEvent.OperationFailed(PRICE_RECORD, "no price for $productId"))
            }
    }

    private fun usdTierPrice(productId: String): Money? =
        SnoozeProducts.all
            .indexOf(productId)
            .takeIf { it >= 0 }
            ?.let { Money.of(it + 1, USD) }

    private suspend fun write(record: PurchaseRecord): Outcome<PurchaseRecord, DomainError> =
        when (val put = records.putRecord(record)) {
            is Outcome.Failure -> put.also { log(WRITE_RECORD, it.error) }
            is Outcome.Success -> Outcome.Success(record)
        }

    /** Logs [error] (if any) and hands the retry to the background jobs, unless [schedule] is false. */
    private fun retryLater(
        operation: String? = null,
        error: DomainError? = null,
        schedule: Boolean = true,
    ): SettleResult {
        if (operation != null && error != null) log(operation, error)
        if (schedule) {
            listOf(CONSUME_RETRY_JOB, CONSUME_RETRY_PERIODIC).forEach { job ->
                val enqueued = work.enqueue(job)
                if (enqueued is Outcome.Failure) log(ENQUEUE_RETRY, enqueued.error)
            }
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

        /** How often the ledger is settled again once the one-time job gave up (a phone offline overnight). */
        val CONSUME_RETRY_PERIOD: Duration = 6.hours

        /** The periodic job behind [CONSUME_RETRY_JOB]: it keeps retrying while any row is pending (a no-op once none is). */
        val CONSUME_RETRY_PERIODIC: BackgroundJob =
            BackgroundJob(
                uniqueName = "consume-retry-periodic",
                task = BackgroundTaskKind.ConsumeRetry,
                needsNetwork = true,
                repeatEvery = CONSUME_RETRY_PERIOD,
            )

        /**
         * A `NotOwned` consume of a payment younger than this is our own earlier consume; older, Google may have refunded
         * it (it refunds a purchase left unconsumed for 3 days), so it is treated as refunded.
         */
        val AUTO_REFUND_SAFE_AGE: Duration = 60.hours

        /** A pending row older than this is logged once per process: Google refunds it after 3 days. */
        val UNSETTLED_ALERT_AGE: Duration = 48.hours

        /** How long a settled marker refuses its token again. Play never redelivers a consumed purchase that late. */
        val SETTLED_RETENTION: Duration = 30.days

        const val CONSUME = "consume purchase"
        const val LOAD_LEDGER = "read grant ledger"
        const val WRITE_LEDGER = "write grant ledger"
        const val LOAD_RECORD = "read purchase record"
        const val WRITE_RECORD = "write purchase record"
        const val PRICE_RECORD = "price purchase record"
        const val ENQUEUE_RETRY = "enqueue consume retry"
        const val UNSETTLED = "settle granted payment"

        private const val USD = "USD"
    }
}

/** The "consume-retry" jobs (AD-17): settle the grant ledger, and ask to run again later until every row is settled. */
class ConsumeRetryTask(
    private val ledger: PurchaseLedger,
) : BackgroundTask {
    override suspend fun run(): TaskResult = if (ledger.settleAll() == SettleResult.Settled) TaskResult.Done else TaskResult.RetryLater
}

/**
 * The restore replay of the grant ledger (Story 4.10): app start, every unlock signal (the first unlock and each wake
 * screen resume) and `MainActivity` start call it. Play is never asked before the first unlock after a boot (AD-15), so
 * it does nothing until then and returns null.
 */
class ReplayGrantLedger(
    private val ledger: PurchaseLedger,
    private val userLock: UserLockState,
) {
    suspend operator fun invoke(): SettleResult? = if (userLock.isUserUnlocked()) ledger.settleAll() else null
}
