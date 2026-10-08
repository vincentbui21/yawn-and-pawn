package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.TaskResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Story 4.10: `PurchaseLedger` records, consumes and settles every paid snooze exactly once, and never consumes without a grant. */
class PurchaseLedgerTest {
    private val world = LedgerWorld()
    private val ledger = world.ledger()
    private val storageDown = DomainError.StorageFailure("disk I/O error")

    private fun owned(vararg tokens: PurchaseToken) = world.play.owned.addAll(tokens)

    /** The rows still pending (a settled row stays as a marker). */
    private fun LedgerWorld.pending(): List<GrantLedgerEntry> = ledgerRows.values.filter { it.settledAt == null }

    private fun LedgerWorld.isSettled(token: PurchaseToken = TOKEN_1): Boolean = ledgerRows[token]?.settledAt != null

    @Test
    fun `a token is stored only as its SHA-256 hex digest`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PurchaseToken("abc").hash())
        assertEquals(64, TOKEN_1.hash().length)
        assertFalse(TOKEN_1.hash().contains(TOKEN_1.value))
    }

    @Test
    fun `a ledger row and a record are never Absent, which means no row`() {
        assertFailsWith<IllegalArgumentException> { grant(status = LedgerStatus.Absent) }
        assertFailsWith<IllegalArgumentException> { record(RecordStatus.Absent) }
    }

    /** One settle of a ledger row against what the record already said. */
    private data class SettleCase(
        val record: RecordStatus?,
        val ledger: LedgerStatus,
        val expected: RecordStatus,
        val consumes: Int,
    )

    @Test
    fun `settling a ledger row records, consumes when still granted, and ends with the row settled (every starting status)`() =
        runTest {
            val cases =
                listOf(
                    SettleCase(null, LedgerStatus.Granted, RecordStatus.Consumed, consumes = 1),
                    SettleCase(null, LedgerStatus.Consumed, RecordStatus.Consumed, consumes = 0),
                    SettleCase(RecordStatus.Granted, LedgerStatus.Granted, RecordStatus.Consumed, consumes = 1),
                    SettleCase(RecordStatus.Granted, LedgerStatus.Consumed, RecordStatus.Consumed, consumes = 0),
                    SettleCase(RecordStatus.Consumed, LedgerStatus.Granted, RecordStatus.Consumed, consumes = 1),
                    SettleCase(RecordStatus.Consumed, LedgerStatus.Consumed, RecordStatus.Consumed, consumes = 0),
                    // Only ReuseAccepted grants a stranded token: the record becomes reused by this snooze.
                    SettleCase(RecordStatus.Stranded, LedgerStatus.Granted, RecordStatus.Reused, consumes = 1),
                    SettleCase(RecordStatus.Stranded, LedgerStatus.Consumed, RecordStatus.Reused, consumes = 0),
                    // A consumed reuse stays reused (history still shows the reuse) and gets its consume time.
                    SettleCase(RecordStatus.Reused, LedgerStatus.Granted, RecordStatus.Reused, consumes = 1),
                    SettleCase(RecordStatus.Reused, LedgerStatus.Consumed, RecordStatus.Reused, consumes = 0),
                )
            cases.forEach { case ->
                val world = LedgerWorld()
                world.now = LEDGER_T0 + 1.minutes
                world.ledgerRows[TOKEN_1] = grant(snoozeNumber = 2, status = case.ledger)
                case.record?.let { world.recordRows[TOKEN_1.hash()] = record(it) }

                assertEquals(SettleResult.Settled, world.ledger().settle(TOKEN_1), "$case")

                val stored = world.recordOf()
                assertEquals(case.expected, stored.status, "$case")
                assertNotNull(stored.consumedAt, "$case: consumed")
                assertEquals(case.consumes, world.play.calls.size, "$case")
                assertEquals(LEDGER_T0 + 1.minutes, world.ledgerRows.getValue(TOKEN_1).settledAt, "$case: the row is a settled marker")
                assertTrue(world.pending().isEmpty(), "$case")
                assertEquals(1, world.recordRows.size, "$case: one record")
                if (case.record == RecordStatus.Stranded) {
                    val reuse = Triple(stored.sessionId, stored.alarmId, stored.snoozeNumber)
                    assertEquals(Triple<String?, String?, Int?>(SESSION, ALARM, 2), reuse)
                }
                // Settled: the next replay does nothing.
                assertEquals(SettleResult.Settled, world.ledger().settleAll())
                assertEquals(case.consumes, world.play.calls.size, "$case: never consumed again")
            }
        }

    @Test
    fun `a new record takes the session, alarm, order and grant time, and the newest intent's price and snooze number`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant(productId = PRODUCT_3, snoozeNumber = 3, orderId = "GPA.7")
            world.intents.rows += intent("older", Money(2_990_000, "EUR"), LEDGER_T0 - 5.minutes, PRODUCT_3, snoozeNumber = 3)
            world.intents.rows += intent("newest", Money(3_490_000, "EUR"), LEDGER_T0 - 1.minutes, PRODUCT_3, snoozeNumber = 3)
            world.intents.rows += intent("other product", Money(9_000_000, "EUR"), LEDGER_T0, PRODUCT_1)
            world.now = LEDGER_T0 + 1.minutes

            ledger.settle(TOKEN_1)

            assertEquals(
                PurchaseRecord(
                    tokenHash = TOKEN_1.hash(),
                    orderId = "GPA.7",
                    productId = PRODUCT_3,
                    sessionId = SESSION,
                    alarmId = ALARM,
                    snoozeNumber = 3,
                    price = Money(3_490_000, "EUR"),
                    priceSource = PriceSource.Intent,
                    purchasedAt = LEDGER_T0,
                    status = RecordStatus.Consumed,
                    consumedAt = LEDGER_T0 + 1.minutes,
                    updatedAt = LEDGER_T0 + 1.minutes,
                ),
                world.recordOf(),
            )
        }

    /** How a record is priced. */
    private data class PriceCase(
        val name: String,
        val productId: String,
        val intent: PurchaseIntent?,
        val cached: Money?,
        val intentFails: Boolean,
        val expected: Money,
        val source: PriceSource,
        val snoozeNumber: Int,
    )

    @Test
    fun `a record is priced by the intent first, then only estimated from the snapshot, the USD tier or zero (logged)`() =
        runTest {
            val eur = intent("i", Money(3_490_000, "EUR"), LEDGER_T0, PRODUCT_3, snoozeNumber = 2)
            val cached = Money(3_190_000, "EUR")
            listOf(
                PriceCase("intent wins over the snapshot", PRODUCT_3, eur, cached, false, eur.price, PriceSource.Intent, 2),
                PriceCase("snapshot", PRODUCT_3, null, cached, false, cached, PriceSource.Snapshot, 3),
                PriceCase("intents unreadable", PRODUCT_3, eur, cached, true, cached, PriceSource.Snapshot, 3),
                PriceCase("no snapshot", PRODUCT_3, null, null, false, Money.of(3, "USD"), PriceSource.Tier, 3),
                PriceCase("unknown product", "spike_s1_test", null, null, false, Money(0, "USD"), PriceSource.Unknown, 3),
            ).forEach { case ->
                val world = LedgerWorld()
                world.ledgerRows[TOKEN_1] = grant(productId = case.productId, snoozeNumber = 3)
                case.intent?.let { world.intents.rows += it }
                case.cached?.let { world.cachedPrices = mapOf(case.productId to it) }
                if (case.intentFails) world.intents.failure = storageDown

                world.ledger().settle(TOKEN_1)

                assertEquals(case.expected, world.recordOf().price, case.name)
                assertEquals(case.source, world.recordOf().priceSource, case.name)
                assertEquals(case.snoozeNumber, world.recordOf().snoozeNumber, "${case.name}: the intent's, else the ledger's")
                val unknown = world.logged.any { it == LogEvent.OperationFailed(PurchaseLedger.PRICE_RECORD, "no price for spike_s1_test") }
                assertEquals(case.productId == "spike_s1_test", unknown, case.name)
            }
            assertEquals(listOf(PriceSource.Intent), PriceSource.entries.filter { it.isAmountPaid })
        }

    @Test
    fun `a failed consume keeps the row and the record granted and enqueues the consume-retry jobs`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()
            owned(TOKEN_1)
            world.play.results += ConsumeResult.Failed("SERVICE_UNAVAILABLE")

            assertEquals(SettleResult.RetryLater, ledger.settle(TOKEN_1))

            assertEquals(LedgerStatus.Granted, world.ledgerRows.getValue(TOKEN_1).status)
            assertFalse(world.isSettled())
            assertEquals(RecordStatus.Granted, world.recordOf().status, "the charge is in history while it waits")
            assertEquals(listOf(PurchaseLedger.CONSUME_RETRY_JOB, PurchaseLedger.CONSUME_RETRY_PERIODIC), world.work.jobs)
            val job = PurchaseLedger.CONSUME_RETRY_JOB
            assertEquals("consume-retry", job.uniqueName)
            assertEquals(BackgroundTaskKind.ConsumeRetry, job.task)
            assertTrue(job.needsNetwork)
            assertNull(job.repeatEvery, "one-time, with backoff")
            val periodic = PurchaseLedger.CONSUME_RETRY_PERIODIC
            assertEquals(BackgroundTaskKind.ConsumeRetry, periodic.task)
            assertTrue(periodic.needsNetwork)
            assertEquals(6.hours, periodic.repeatEvery, "retries go on after the one-time job gave up")
            assertTrue(world.logged.contains(LogEvent.OperationFailed(PurchaseLedger.CONSUME, "SERVICE_UNAVAILABLE")))
            assertTrue(TOKEN_1 in world.play.owned, "Play still holds it")
        }

    @Test
    fun `the consume-retry job fails twice, then succeeds and settles`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()
            owned(TOKEN_1)
            world.play.results += listOf(ConsumeResult.Failed("offline"), ConsumeResult.Failed("SERVICE_UNAVAILABLE"))
            val task = ConsumeRetryTask(ledger)

            assertEquals(listOf(TaskResult.RetryLater, TaskResult.RetryLater, TaskResult.Done), List(3) { task.run() })

            assertEquals(3, world.play.calls.size)
            assertEquals(RecordStatus.Consumed, world.recordOf().status)
            assertTrue(world.isSettled())
            assertTrue(world.play.owned.isEmpty())
            assertEquals(TaskResult.Done, task.run(), "the periodic job finds nothing left")
        }

    @Test
    fun `concurrent triggers are serialised, so one payment is recorded and consumed once`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()
            val play = CompletableDeferred<Unit>()
            world.play.hold = play

            // The Consume effect and a cold start's replay (app start and MainActivity start) at once.
            val fromGrant = async { ledger.settle(TOKEN_1) }
            val fromStart = async { ledger.settleAll() }
            runCurrent()
            assertEquals(listOf(TOKEN_1), world.play.calls, "the second trigger waits for the first")

            play.complete(Unit)
            assertEquals(SettleResult.Settled, fromGrant.await())
            assertEquals(SettleResult.Settled, fromStart.await())
            assertEquals(1, world.play.calls.size, "one consume in total")
            assertEquals(2, world.records.puts, "granted, then consumed")
            assertEquals(RecordStatus.Consumed, world.recordOf().status)
        }

    @Test
    fun `nothing is consumed while the record cannot be written, so a charge is in history before Play forgets the token`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()
            world.recordWriteFailure = storageDown

            assertEquals(SettleResult.RetryLater, ledger.settle(TOKEN_1))
            assertEquals(emptyList(), world.play.calls)
            assertEquals(LedgerStatus.Granted, world.ledgerRows.getValue(TOKEN_1).status)

            world.recordFailure = storageDown
            assertEquals(SettleResult.RetryLater, ledger.settle(TOKEN_1), "unreadable records: same")
            assertEquals(emptyList(), world.play.calls)
        }

    @Test
    fun `a failed settle leaves a consumed row, whose replay settles without consuming again`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()
            world.ledgerDeleteFailure = storageDown

            assertEquals(SettleResult.RetryLater, ledger.settle(TOKEN_1))
            assertEquals(LedgerStatus.Consumed, world.ledgerRows.getValue(TOKEN_1).status)
            assertFalse(world.isSettled())

            world.ledgerDeleteFailure = null
            assertEquals(SettleResult.Settled, ledger.settleAll())
            assertEquals(1, world.play.calls.size)
            assertEquals(RecordStatus.Consumed, world.recordOf().status)
            assertTrue(world.isSettled())
        }

    @Test
    fun `a ledger that cannot mark consumed is logged only, and a record that cannot be marked consumed is retried`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()
            world.ledgerWriteFailure = storageDown
            assertEquals(SettleResult.Settled, ledger.settle(TOKEN_1))
            assertTrue(world.logged.contains(LogEvent.OperationFailed.of(PurchaseLedger.WRITE_LEDGER, storageDown)))

            val second = LedgerWorld()
            second.ledgerRows[TOKEN_1] = grant()
            second.recordRows[TOKEN_1.hash()] = record(RecordStatus.Granted)
            second.recordWriteFailure = storageDown
            assertEquals(SettleResult.RetryLater, second.ledger().settle(TOKEN_1))
            assertEquals(1, second.play.calls.size)
            assertEquals(LedgerStatus.Consumed, second.ledgerRows.getValue(TOKEN_1).status)
        }

    @Test
    fun `an unreadable ledger is retried later, and a job that cannot be enqueued is logged`() =
        runTest {
            world.ledgerFailure = storageDown
            world.work.failure = DomainError.NotFound("user locked")

            assertEquals(SettleResult.RetryLater, ledger.settle(TOKEN_1))
            assertEquals(SettleResult.RetryLater, ledger.settleAll())

            assertTrue(world.logged.contains(LogEvent.OperationFailed.of(PurchaseLedger.LOAD_LEDGER, storageDown)))
            val notEnqueued = LogEvent.OperationFailed.of(PurchaseLedger.ENQUEUE_RETRY, DomainError.NotFound("user locked"))
            assertTrue(world.logged.contains(notEnqueued))
        }

    @Test
    fun `an adapter that throws counts as a failed consume, and a token with no row has nothing to settle`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()
            world.play.throwing = IllegalStateException("billing client died")

            assertEquals(SettleResult.RetryLater, ledger.settle(TOKEN_1))
            assertTrue(world.logged.contains(LogEvent.OperationFailed(PurchaseLedger.CONSUME, "IllegalStateException")))

            assertEquals(SettleResult.Settled, ledger.settle(TOKEN_2))
            assertEquals(1, world.play.calls.size)
        }

    @Test
    fun `settling everything settles each row, oldest first, and says retry while any row is left`() =
        runTest {
            world.ledgerRows[TOKEN_2] = grant(TOKEN_2, createdAt = LEDGER_T0 + 9.minutes)
            world.ledgerRows[TOKEN_1] = grant(TOKEN_1)
            world.play.results += ConsumeResult.Consumed
            world.play.results += ConsumeResult.Failed("offline")

            assertEquals(SettleResult.RetryLater, ledger.settleAll())
            assertEquals(listOf(TOKEN_1, TOKEN_2), world.play.calls)
            assertEquals(listOf(TOKEN_2), world.pending().map { it.token })

            assertEquals(SettleResult.Settled, ledger.settleAll())
            assertTrue(world.pending().isEmpty())
        }

    /** A `NotOwned` consume of a payment of this age. */
    private data class NotOwnedCase(
        val name: String,
        val age: kotlin.time.Duration,
        val expected: RecordStatus,
    )

    @Test
    fun `NotOwned is our own earlier consume while the payment is young, and Google's refund once it is older than 60 h`() =
        runTest {
            listOf(
                NotOwnedCase("an hour old: consumed before a crash", 1.hours, RecordStatus.Consumed),
                NotOwnedCase("just under 60 h", 59.hours, RecordStatus.Consumed),
                NotOwnedCase("60 h", 60.hours, RecordStatus.Stranded),
                NotOwnedCase("3 days: auto-refunded", 3.days, RecordStatus.Stranded),
            ).forEach { case ->
                val world = LedgerWorld()
                world.ledgerRows[TOKEN_1] = grant()
                world.now = LEDGER_T0 + case.age
                world.play.results += ConsumeResult.NotOwned

                assertEquals(SettleResult.Settled, world.ledger().settle(TOKEN_1), case.name)

                val stored = world.recordOf()
                assertEquals(case.expected, stored.status, case.name)
                val paid = stored.consumedAt != null
                assertEquals(case.expected == RecordStatus.Consumed, paid, "${case.name}: shown as paid only if consumed")
                assertTrue(world.isSettled(), "${case.name}: settled, never consumed again")
                assertEquals(case.expected == RecordStatus.Stranded, world.logged.any { "refunded by Google" in it.toString() }, case.name)
            }
        }

    @Test
    fun `a refunded reuse becomes stranded again, and a NotOwned ConsumeOnly follows the same age rule`() =
        runTest {
            world.recordRows[TOKEN_1.hash()] = record(RecordStatus.Stranded, purchasedAt = LEDGER_T0 - 3.days)
            world.ledgerRows[TOKEN_1] = grant()
            world.play.results += ConsumeResult.NotOwned
            assertEquals(SettleResult.Settled, ledger.settle(TOKEN_1))
            assertEquals(RecordStatus.Stranded, world.recordOf().status, "the reused payment was refunded: not paid")

            val second = LedgerWorld()
            second.recordRows[TOKEN_1.hash()] = record(RecordStatus.Granted, purchasedAt = LEDGER_T0 - 4.days)
            second.play.results += ConsumeResult.NotOwned
            assertEquals(SettleResult.Settled, second.ledger().consumeOnly(paidSnapshot()))
            assertEquals(RecordStatus.Stranded, second.recordOf().status)
        }

    /** A `ConsumeOnly` decision with no ledger row, by what the record says. */
    private data class ConsumeOnlyCase(
        val name: String,
        val record: PurchaseRecord?,
        val expected: SettleResult,
        val consumes: Int,
        val after: RecordStatus?,
    )

    @Test
    fun `ConsumeOnly without a ledger row consumes only a record still owed a consume, and refuses everything else`() =
        runTest {
            val reusedAndConsumed = record(RecordStatus.Reused, consumedAt = LEDGER_T0)
            listOf(
                ConsumeOnlyCase("granted", record(RecordStatus.Granted), SettleResult.Settled, 1, RecordStatus.Consumed),
                // A restored app.db: the reuse was granted, its ledger row was not restored.
                ConsumeOnlyCase("reused, not consumed", record(RecordStatus.Reused), SettleResult.Settled, 1, RecordStatus.Reused),
                ConsumeOnlyCase("absent", null, SettleResult.Refused, 0, null),
                ConsumeOnlyCase("stranded", record(RecordStatus.Stranded), SettleResult.Refused, 0, RecordStatus.Stranded),
                ConsumeOnlyCase("consumed", record(RecordStatus.Consumed), SettleResult.Refused, 0, RecordStatus.Consumed),
                ConsumeOnlyCase("reused, consumed", reusedAndConsumed, SettleResult.Refused, 0, RecordStatus.Reused),
            ).forEach { case ->
                val world = LedgerWorld()
                case.record?.let { world.recordRows[TOKEN_1.hash()] = it.copy(orderId = null) }

                assertEquals(case.expected, world.ledger().consumeOnly(paidSnapshot(orderId = "GPA.42")), case.name)

                assertEquals(case.consumes, world.play.calls.size, case.name)
                assertEquals(case.after, world.recordRows[TOKEN_1.hash()]?.status, case.name)
                if (case.consumes == 1) {
                    assertEquals("GPA.42", world.recordOf().orderId, "${case.name}: the order id is filled in")
                    assertNotNull(world.recordOf().consumedAt, case.name)
                }
                assertTrue(world.work.jobs.isEmpty(), "${case.name}: no ledger row, so no job")
            }
        }

    @Test
    fun `ConsumeOnly with a ledger row goes through the same consume path as the grant`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()

            assertEquals(SettleResult.Settled, ledger.consumeOnly(paidSnapshot()))

            assertEquals(listOf(TOKEN_1), world.play.calls)
            assertEquals(RecordStatus.Consumed, world.recordOf().status)
            assertTrue(world.isSettled())
            assertEquals(SettleResult.Settled, ledger.consumeOnly(paidSnapshot()), "a settled marker: nothing to do")
            assertEquals(1, world.play.calls.size)
        }

    @Test
    fun `ConsumeOnly retries later when a store or Play fails`() =
        runTest {
            world.ledgerFailure = storageDown
            assertEquals(SettleResult.RetryLater, ledger.consumeOnly(paidSnapshot()))

            world.ledgerFailure = null
            world.recordFailure = storageDown
            assertEquals(SettleResult.RetryLater, ledger.consumeOnly(paidSnapshot()))

            world.recordFailure = null
            world.recordRows[TOKEN_1.hash()] = record(RecordStatus.Granted)
            world.play.results += ConsumeResult.Failed("offline")
            assertEquals(SettleResult.RetryLater, ledger.consumeOnly(paidSnapshot()))
            assertEquals(RecordStatus.Granted, world.recordOf().status)

            world.recordWriteFailure = storageDown
            assertEquals(SettleResult.RetryLater, ledger.consumeOnly(paidSnapshot()))
            assertEquals(emptyList(), world.work.jobs)
        }

    @Test
    fun `a stranded record is priced by its session's newest intent, never consumed, and has no ledger row`() =
        runTest {
            world.intents.rows += intent("a", Money(1_190_000, "EUR"), LEDGER_T0 - 2.minutes)
            world.intents.rows += intent("b", Money(1_290_000, "EUR"), LEDGER_T0 - 1.minutes, snoozeNumber = 2)

            val stranded = ledger.recordStranded(paidSnapshot(orderId = "GPA.5", purchaseTime = LEDGER_T0 - 30.minutes), alarmId = ALARM)

            val expected =
                PurchaseRecord(
                    TOKEN_1.hash(),
                    "GPA.5",
                    PRODUCT_1,
                    SESSION,
                    ALARM,
                    2,
                    Money(1_290_000, "EUR"),
                    PriceSource.Intent,
                    LEDGER_T0 - 30.minutes,
                    RecordStatus.Stranded,
                    null,
                    LEDGER_T0,
                )
            assertEquals(Outcome.Success(expected), stranded)
            assertEquals(expected, world.recordOf())
            assertEquals(emptyList(), world.play.calls)
            assertTrue(world.ledgerRows.isEmpty())
            assertEquals(Money(1_290_000, "EUR"), ledger.refundingPrice(PRODUCT_1), "what was actually paid")
        }

    @Test
    fun `a stranded payment with no intent is only an estimate, and the refunding label names no amount for it`() =
        runTest {
            val promo = paidSnapshot(productId = PRODUCT_3, profileId = null)
            val stranded = assertIs<Outcome.Success<PurchaseRecord>>(ledger.recordStranded(promo)).value

            assertNull(stranded.sessionId)
            assertNull(stranded.alarmId)
            assertNull(stranded.snoozeNumber)
            assertEquals(Money.of(3, "USD"), stranded.price)
            assertEquals(PriceSource.Tier, stranded.priceSource)
            assertFalse(stranded.priceSource.isAmountPaid, "a 79,000₫ payment must never read as \$3.00")
            assertNull(ledger.refundingPrice(PRODUCT_3))
        }

    @Test
    fun `recording stranded is idempotent and never downgrades an existing record`() =
        runTest {
            RecordStatus.entries.filter { it != RecordStatus.Absent }.forEach { status ->
                val world = LedgerWorld()
                val existing = record(status)
                world.recordRows[TOKEN_1.hash()] = existing

                assertEquals(Outcome.Success(existing), world.ledger().recordStranded(paidSnapshot()), "$status")
                assertEquals(existing, world.recordOf())
                assertEquals(0, world.records.puts)
            }
            ledger.recordStranded(paidSnapshot())
            assertEquals(Outcome.Success(world.recordOf()), ledger.recordStranded(paidSnapshot()))
            assertEquals(1, world.records.puts)
        }

    @Test
    fun `recording stranded fails, logged, when the records cannot be read or written`() =
        runTest {
            world.recordFailure = storageDown
            assertEquals(Outcome.Failure(storageDown), ledger.recordStranded(paidSnapshot()))

            world.recordFailure = null
            world.recordWriteFailure = storageDown
            assertEquals(Outcome.Failure(storageDown), ledger.recordStranded(paidSnapshot()))
            assertTrue(world.logged.contains(LogEvent.OperationFailed.of(PurchaseLedger.WRITE_RECORD, storageDown)))
        }

    /** markReused from a starting record, with or without the reuse's grant row committed. */
    private data class ReuseCase(
        val name: String,
        val existing: PurchaseRecord?,
        val expected: Outcome<RecordStatus, DomainError>,
        val granted: Boolean = true,
    )

    private fun notReusable(status: String): Outcome<RecordStatus, DomainError> = Outcome.Failure(DomainError.RecordNotReusable(status))

    @Test
    fun `markReused changes only a stranded record whose reuse is committed, is idempotent, and refuses the rest`() =
        runTest {
            val reusedHere = record(RecordStatus.Reused, sessionId = SESSION, alarmId = ALARM, snoozeNumber = 2)
            val notCommitted: Outcome<RecordStatus, DomainError> = Outcome.Failure(DomainError.NotFound("grant for the reuse"))
            listOf(
                ReuseCase("absent", null, Outcome.Failure(DomainError.NotFound(TOKEN_1.hash()))),
                ReuseCase("stranded", record(RecordStatus.Stranded), Outcome.Success(RecordStatus.Reused)),
                // Before the commit (or a commit that failed): nothing was granted, so the record stays stranded.
                ReuseCase("stranded, not committed", record(RecordStatus.Stranded), notCommitted, granted = false),
                ReuseCase("same reuse again", reusedHere, Outcome.Success(RecordStatus.Reused)),
                ReuseCase("reused by another session", reusedHere.copy(sessionId = "s-9"), notReusable("Reused")),
                ReuseCase("reused for another snooze", reusedHere.copy(snoozeNumber = 1), notReusable("Reused")),
                ReuseCase("granted", record(RecordStatus.Granted), notReusable("Granted")),
                ReuseCase("consumed", record(RecordStatus.Consumed), notReusable("Consumed")),
            ).forEach { case ->
                val world = LedgerWorld()
                world.now = LEDGER_T0 + 1.minutes
                case.existing?.let { world.recordRows[TOKEN_1.hash()] = it }
                if (case.granted) world.ledgerRows[TOKEN_1] = grant(snoozeNumber = 2)

                val result = world.ledger().markReused(TOKEN_1.hash(), SESSION, ALARM, 2)

                when (val expected = case.expected) {
                    is Outcome.Failure -> {
                        assertEquals(expected, result, case.name)
                        assertEquals(case.existing, world.recordRows[TOKEN_1.hash()], "${case.name}: unchanged")
                    }

                    is Outcome.Success -> {
                        val reused = assertIs<Outcome.Success<PurchaseRecord>>(result, case.name).value
                        assertEquals(reused, world.recordOf(), case.name)
                        assertEquals(expected.value, reused.status, case.name)
                        val reuse = Triple(reused.sessionId, reused.alarmId, reused.snoozeNumber)
                        assertEquals(Triple<String?, String?, Int?>(SESSION, ALARM, 2), reuse)
                        assertEquals(case.existing!!.price, reused.price, "${case.name}: the price paid is kept")
                    }
                }
            }
            world.recordFailure = storageDown
            assertEquals(Outcome.Failure(storageDown), ledger.markReused(TOKEN_1.hash(), SESSION, ALARM, 2))
        }

    @Test
    fun `lookup gives the reconciler both statuses, a settled marker as consumed and an unconsumed reuse as granted`() =
        runTest {
            assertEquals(Outcome.Success(PurchaseLookup(LedgerStatus.Absent, RecordStatus.Absent)), ledger.lookup(TOKEN_1))

            world.ledgerRows[TOKEN_1] = grant(status = LedgerStatus.Granted)
            world.recordRows[TOKEN_1.hash()] = record(RecordStatus.Reused)
            assertEquals(Outcome.Success(PurchaseLookup(LedgerStatus.Granted, RecordStatus.Granted)), ledger.lookup(TOKEN_1))

            world.ledgerRows[TOKEN_1] = grant(status = LedgerStatus.Granted).copy(settledAt = LEDGER_T0)
            world.recordRows[TOKEN_1.hash()] = record(RecordStatus.Reused, consumedAt = LEDGER_T0)
            assertEquals(Outcome.Success(PurchaseLookup(LedgerStatus.Consumed, RecordStatus.Reused)), ledger.lookup(TOKEN_1))

            world.recordFailure = storageDown
            assertEquals(Outcome.Failure(storageDown), ledger.lookup(TOKEN_1))
            world.ledgerFailure = storageDown
            assertEquals(Outcome.Failure(storageDown), ledger.lookup(TOKEN_1))
        }

    @Test
    fun `the refunding label shows what the newest stranded payment for the product actually cost, never an estimate`() =
        runTest {
            assertNull(ledger.refundingPrice(PRODUCT_1))
            listOf(
                record(RecordStatus.Stranded, price = Money(1_190_000, "EUR"), purchasedAt = LEDGER_T0).copy(tokenHash = "a"),
                record(RecordStatus.Stranded, price = Money(1_250_000, "EUR"), purchasedAt = LEDGER_T0 + 1.minutes).copy(tokenHash = "b"),
                record(RecordStatus.Consumed, price = Money(9_000_000, "EUR"), purchasedAt = LEDGER_T0 + 2.minutes).copy(tokenHash = "c"),
                record(RecordStatus.Stranded, productId = PRODUCT_3, price = Money.of(3, "USD")).copy(tokenHash = "d"),
            ).forEach { world.recordRows[it.tokenHash] = it }

            assertEquals(Money(1_250_000, "EUR"), ledger.refundingPrice(PRODUCT_1))

            val estimate = record(RecordStatus.Stranded, price = Money.of(1, "USD"), priceSource = PriceSource.Snapshot)
            world.recordRows["e"] = estimate.copy(tokenHash = "e", purchasedAt = LEDGER_T0 + 5.minutes)
            assertNull(ledger.refundingPrice(PRODUCT_1), "the newest is only an estimate: no amount")
            world.recordFailure = storageDown
            assertNull(ledger.refundingPrice(PRODUCT_1))
        }

    @Test
    fun `settled markers are kept 30 days, then purged, and pending rows are never purged`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant().copy(settledAt = LEDGER_T0)
            world.ledgerRows[TOKEN_2] = grant(TOKEN_2)
            world.now = LEDGER_T0 + 29.days
            assertEquals(Outcome.Success(0), ledger.purgeSettled())

            world.now = LEDGER_T0 + 31.days
            assertEquals(Outcome.Success(1), ledger.purgeSettled())
            assertEquals(listOf(TOKEN_2), world.ledgerRows.keys.toList())

            world.ledgerDeleteFailure = storageDown
            assertEquals(Outcome.Failure(storageDown), ledger.purgeSettled())
            assertTrue(world.logged.contains(LogEvent.OperationFailed.of(PurchaseLedger.WRITE_LEDGER, storageDown)))
        }

    @Test
    fun `a payment still unsettled after 48 h is logged once per process, without its token`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()
            world.play.results += List(3) { ConsumeResult.Failed("offline") }
            world.now = LEDGER_T0 + 47.hours
            ledger.settleAll()
            assertTrue(world.logged.none { it is LogEvent.OperationFailed && it.operation == PurchaseLedger.UNSETTLED })

            world.now = LEDGER_T0 + 49.hours
            ledger.settleAll()
            ledger.settleAll()
            val alerts = world.logged.filter { it is LogEvent.OperationFailed && it.operation == PurchaseLedger.UNSETTLED }
            assertEquals(1, alerts.size)
            assertFalse(TOKEN_1.value in alerts.single().toString())
        }

    @Test
    fun `the replay does nothing before the first unlock and settles everything after it`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()
            var unlocked = false
            val lock =
                object : UserLockState {
                    override fun isUserUnlocked(): Boolean = unlocked

                    override fun observe(): Flow<Boolean> = flowOf(unlocked)
                }
            val replay = ReplayGrantLedger(ledger, lock)

            assertNull(replay())
            assertEquals(emptyList(), world.play.calls, "Play is never asked before the first unlock (AD-15)")

            unlocked = true
            assertEquals(SettleResult.Settled, replay())
            assertTrue(world.isSettled())
        }

    @Test
    fun `no token ever reaches the log`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()
            world.play.results += ConsumeResult.Failed("offline")
            world.play.throwing = null
            ledger.settle(TOKEN_1)
            ledger.consumeOnly(paidSnapshot(TOKEN_2))
            world.recordWriteFailure = storageDown
            ledger.recordStranded(paidSnapshot(TOKEN_2))
            world.ledgerFailure = storageDown
            ledger.settleAll()

            assertTrue(world.logged.isNotEmpty())
            val text = world.logged.joinToString()
            listOf(TOKEN_1, TOKEN_2).forEach { assertFalse(it.value in text, "token in log: $text") }
            assertFalse(TOKEN_1.value in grant().toString())
        }
}
