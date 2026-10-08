package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.TaskResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Story 4.10: `PurchaseLedger` records, consumes and settles every paid snooze exactly once, and never consumes without a grant. */
class PurchaseLedgerTest {
    private val world = LedgerWorld()
    private val ledger = world.ledger()
    private val storageDown = DomainError.StorageFailure("disk I/O error")

    private fun owned(vararg tokens: PurchaseToken) = world.play.owned.addAll(tokens)

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
    fun `settling a ledger row records, consumes when still granted, and ends with the row gone (every starting status)`() =
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
                    // A consumed reuse stays reused: history still shows the reuse.
                    SettleCase(RecordStatus.Reused, LedgerStatus.Granted, RecordStatus.Reused, consumes = 1),
                    SettleCase(RecordStatus.Reused, LedgerStatus.Consumed, RecordStatus.Reused, consumes = 0),
                )
            cases.forEach { case ->
                val world = LedgerWorld()
                world.ledgerRows[TOKEN_1] = grant(snoozeNumber = 2, status = case.ledger)
                case.record?.let { world.recordRows[TOKEN_1.hash()] = record(it) }

                assertEquals(SettleResult.Settled, world.ledger().settle(TOKEN_1), "$case")

                val stored = world.recordOf()
                assertEquals(case.expected, stored.status, "$case")
                assertEquals(case.consumes, world.play.calls.size, "$case")
                assertTrue(world.ledgerRows.isEmpty(), "$case: the ledger row is gone")
                assertEquals(1, world.recordRows.size, "$case: one record")
                if (case.record == RecordStatus.Stranded) {
                    val reuse = Triple(stored.sessionId, stored.alarmId, stored.snoozeNumber)
                    assertEquals(Triple<String?, String?, Int?>(SESSION, ALARM, 2), reuse)
                }
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
                    purchasedAt = LEDGER_T0,
                    status = RecordStatus.Consumed,
                    updatedAt = LEDGER_T0 + 1.minutes,
                ),
                world.recordOf(),
            )
        }

    /** How a record with no intent is priced. */
    private data class PriceCase(
        val name: String,
        val productId: String,
        val cached: Money?,
        val intentFails: Boolean,
        val expected: Money,
    )

    @Test
    fun `with no intent a record is priced from the price snapshot, then the product's USD tier, then zero (logged)`() =
        runTest {
            listOf(
                PriceCase("snapshot", PRODUCT_3, Money(3_190_000, "EUR"), intentFails = false, expected = Money(3_190_000, "EUR")),
                PriceCase("no snapshot", PRODUCT_3, null, intentFails = false, expected = Money.of(3, "USD")),
                PriceCase("intents unreadable", PRODUCT_3, null, intentFails = true, expected = Money.of(3, "USD")),
                PriceCase("unknown product", "spike_s1_test", null, intentFails = false, expected = Money(0, "USD")),
            ).forEach { case ->
                val world = LedgerWorld()
                world.ledgerRows[TOKEN_1] = grant(productId = case.productId, snoozeNumber = 4)
                case.cached?.let { world.cachedPrices = mapOf(case.productId to it) }
                if (case.intentFails) world.intents.failure = storageDown

                world.ledger().settle(TOKEN_1)

                assertEquals(case.expected, world.recordOf().price, case.name)
                assertEquals(4, world.recordOf().snoozeNumber, "${case.name}: the ledger's snooze number")
                val unknown = world.logged.any { it == LogEvent.OperationFailed(PurchaseLedger.PRICE_RECORD, "no price for spike_s1_test") }
                assertEquals(case.productId == "spike_s1_test", unknown, case.name)
            }
        }

    @Test
    fun `a failed consume keeps the row and the record granted and enqueues the unique consume-retry job`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()
            owned(TOKEN_1)
            world.play.results += ConsumeResult.Failed("SERVICE_UNAVAILABLE")

            assertEquals(SettleResult.RetryLater, ledger.settle(TOKEN_1))

            assertEquals(LedgerStatus.Granted, world.ledgerRows.getValue(TOKEN_1).status)
            assertEquals(RecordStatus.Granted, world.recordOf().status, "the charge is in history while it waits")
            assertEquals(listOf(PurchaseLedger.CONSUME_RETRY_JOB), world.work.jobs)
            val job = PurchaseLedger.CONSUME_RETRY_JOB
            assertEquals("consume-retry", job.uniqueName)
            assertEquals(BackgroundTaskKind.ConsumeRetry, job.task)
            assertTrue(job.needsNetwork)
            assertNull(job.repeatEvery, "one-time, with backoff")
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
            assertTrue(world.ledgerRows.isEmpty())
            assertTrue(world.play.owned.isEmpty())
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
            assertEquals(2, world.work.jobs.size)
        }

    @Test
    fun `a failed delete leaves a consumed row, whose replay settles without consuming again`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()
            world.ledgerDeleteFailure = storageDown

            assertEquals(SettleResult.RetryLater, ledger.settle(TOKEN_1))
            assertEquals(LedgerStatus.Consumed, world.ledgerRows.getValue(TOKEN_1).status)

            world.ledgerDeleteFailure = null
            assertEquals(SettleResult.Settled, ledger.settleAll())
            assertEquals(1, world.play.calls.size)
            assertEquals(RecordStatus.Consumed, world.recordOf().status)
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
            assertEquals(listOf(TOKEN_2), world.ledgerRows.keys.toList())

            assertEquals(SettleResult.Settled, ledger.settleAll())
            assertTrue(world.ledgerRows.isEmpty())
        }

    /** A `ConsumeOnly` decision with no ledger row, by what the record says. */
    private data class ConsumeOnlyCase(
        val record: RecordStatus?,
        val expected: SettleResult,
        val consumes: Int,
        val after: RecordStatus?,
    )

    @Test
    fun `ConsumeOnly without a ledger row consumes only a granted record, and refuses everything else`() =
        runTest {
            listOf(
                ConsumeOnlyCase(RecordStatus.Granted, SettleResult.Settled, consumes = 1, after = RecordStatus.Consumed),
                ConsumeOnlyCase(null, SettleResult.Refused, consumes = 0, after = null),
                ConsumeOnlyCase(RecordStatus.Stranded, SettleResult.Refused, consumes = 0, after = RecordStatus.Stranded),
                ConsumeOnlyCase(RecordStatus.Consumed, SettleResult.Refused, consumes = 0, after = RecordStatus.Consumed),
                ConsumeOnlyCase(RecordStatus.Reused, SettleResult.Refused, consumes = 0, after = RecordStatus.Reused),
            ).forEach { case ->
                val world = LedgerWorld()
                case.record?.let { world.recordRows[TOKEN_1.hash()] = record(it).copy(orderId = null) }

                assertEquals(case.expected, world.ledger().consumeOnly(paidSnapshot(orderId = "GPA.42")), "$case")

                assertEquals(case.consumes, world.play.calls.size, "$case")
                assertEquals(case.after, world.recordRows[TOKEN_1.hash()]?.status, "$case")
                if (case.record == RecordStatus.Granted) assertEquals("GPA.42", world.recordOf().orderId, "the order id is filled in")
                assertTrue(world.work.jobs.isEmpty(), "$case: no ledger row, so no job")
            }
        }

    @Test
    fun `ConsumeOnly with a ledger row goes through the same consume path as the grant`() =
        runTest {
            world.ledgerRows[TOKEN_1] = grant()

            assertEquals(SettleResult.Settled, ledger.consumeOnly(paidSnapshot()))

            assertEquals(listOf(TOKEN_1), world.play.calls)
            assertEquals(RecordStatus.Consumed, world.recordOf().status)
            assertTrue(world.ledgerRows.isEmpty())
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
                    LEDGER_T0 - 30.minutes,
                    RecordStatus.Stranded,
                    LEDGER_T0,
                )
            assertEquals(Outcome.Success(expected), stranded)
            assertEquals(expected, world.recordOf())
            assertEquals(emptyList(), world.play.calls)
            assertTrue(world.ledgerRows.isEmpty())
        }

    @Test
    fun `a stranded payment with no profile id has no session, alarm or snooze number and is priced by its tier`() =
        runTest {
            val promo = paidSnapshot(productId = PRODUCT_3, profileId = null)
            val stranded = assertIs<Outcome.Success<PurchaseRecord>>(ledger.recordStranded(promo))

            assertNull(stranded.value.sessionId)
            assertNull(stranded.value.alarmId)
            assertNull(stranded.value.snoozeNumber)
            assertEquals(Money.of(3, "USD"), stranded.value.price)
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

    /** markReused from a starting record. */
    private data class ReuseCase(
        val name: String,
        val existing: PurchaseRecord?,
        val expected: Outcome<RecordStatus, DomainError>,
    )

    private fun notReusable(status: String): Outcome<RecordStatus, DomainError> = Outcome.Failure(DomainError.RecordNotReusable(status))

    @Test
    fun `markReused changes only a stranded record, is idempotent, and refuses every other starting status`() =
        runTest {
            val reusedHere = record(RecordStatus.Reused, sessionId = SESSION, alarmId = ALARM, snoozeNumber = 2)
            listOf(
                ReuseCase("absent", null, Outcome.Failure(DomainError.NotFound(TOKEN_1.hash()))),
                ReuseCase("stranded", record(RecordStatus.Stranded), Outcome.Success(RecordStatus.Reused)),
                ReuseCase("same reuse again", reusedHere, Outcome.Success(RecordStatus.Reused)),
                ReuseCase("reused by another session", reusedHere.copy(sessionId = "s-9"), notReusable("Reused")),
                ReuseCase("reused for another snooze", reusedHere.copy(snoozeNumber = 1), notReusable("Reused")),
                ReuseCase("granted", record(RecordStatus.Granted), notReusable("Granted")),
                ReuseCase("consumed", record(RecordStatus.Consumed), notReusable("Consumed")),
            ).forEach { case ->
                val world = LedgerWorld()
                world.now = LEDGER_T0 + 1.minutes
                case.existing?.let { world.recordRows[TOKEN_1.hash()] = it }

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
    fun `lookup gives the reconciler both statuses, Absent for no row`() =
        runTest {
            assertEquals(Outcome.Success(PurchaseLookup(LedgerStatus.Absent, RecordStatus.Absent)), ledger.lookup(TOKEN_1))

            world.ledgerRows[TOKEN_1] = grant(status = LedgerStatus.Consumed)
            world.recordRows[TOKEN_1.hash()] = record(RecordStatus.Reused)
            assertEquals(Outcome.Success(PurchaseLookup(LedgerStatus.Consumed, RecordStatus.Reused)), ledger.lookup(TOKEN_1))

            world.recordFailure = storageDown
            assertEquals(Outcome.Failure(storageDown), ledger.lookup(TOKEN_1))
            world.ledgerFailure = storageDown
            assertEquals(Outcome.Failure(storageDown), ledger.lookup(TOKEN_1))
        }

    @Test
    fun `the refunding label shows what the newest stranded payment for the product actually cost`() =
        runTest {
            assertNull(ledger.refundingPrice(PRODUCT_1))
            listOf(
                record(RecordStatus.Stranded, price = Money(1_190_000, "EUR"), purchasedAt = LEDGER_T0).copy(tokenHash = "a"),
                record(RecordStatus.Stranded, price = Money(1_250_000, "EUR"), purchasedAt = LEDGER_T0 + 1.minutes).copy(tokenHash = "b"),
                record(RecordStatus.Consumed, price = Money(9_000_000, "EUR"), purchasedAt = LEDGER_T0 + 2.minutes).copy(tokenHash = "c"),
                record(RecordStatus.Stranded, productId = PRODUCT_3, price = Money.of(3, "USD")).copy(tokenHash = "d"),
            ).forEach { world.recordRows[it.tokenHash] = it }

            assertEquals(Money(1_250_000, "EUR"), ledger.refundingPrice(PRODUCT_1))
            world.recordFailure = storageDown
            assertNull(ledger.refundingPrice(PRODUCT_1))
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
            assertTrue(world.ledgerRows.isEmpty())
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
