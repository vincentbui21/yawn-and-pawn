package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.billing.ConsumeRetryTask
import com.yawnandpawn.app.core.billing.PriceSnapshotLookup
import com.yawnandpawn.app.core.billing.PurchaseLedger
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundTask
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.TaskResult
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeGrantLedgerStore
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakePurchaseIntentStore
import com.yawnandpawn.app.testing.FakePurchaseRecordRepository
import com.yawnandpawn.app.testing.aGrant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Story 4.10: the in-process stand-in for Story 4.3's WorkManager adapter runs the consume retry with bounded backoff. */
class InProcessBackgroundWorkTest {
    private val scope = TestScope()
    private val logger = FakeLogger()
    private val results = ArrayDeque<TaskResult>()
    private var runs = 0
    private val task =
        BackgroundTask {
            runs++
            results.removeFirstOrNull() ?: TaskResult.Done
        }
    private val work = InProcessBackgroundWork(scope, logger) { mapOf(BackgroundTaskKind.ConsumeRetry to task) }
    private val job = PurchaseLedger.CONSUME_RETRY_JOB

    @Test
    fun `a failing job runs again after 30 s, then 60 s, and stops once it is done`() {
        results += listOf(TaskResult.RetryLater, TaskResult.RetryLater)

        assertEquals(Outcome.Success(Unit), work.enqueue(job))
        scope.runCurrent()
        assertEquals(1, runs)
        scope.advanceTimeBy(29.seconds)
        scope.runCurrent()
        assertEquals(1, runs, "not before the 30 s backoff")
        scope.advanceTimeBy(1.seconds)
        scope.runCurrent()
        assertEquals(2, runs)
        scope.advanceTimeBy(60.seconds)
        scope.runCurrent()
        assertEquals(3, runs)
        scope.advanceTimeBy(1.hours)
        assertEquals(3, runs, "done")
    }

    @Test
    fun `the attempts are bounded, and a job still running keeps its name (KEEP)`() {
        results += List(10) { TaskResult.RetryLater }

        work.enqueue(job)
        work.enqueue(job)
        scope.advanceTimeBy(1.hours)
        scope.runCurrent()

        assertEquals(InProcessBackgroundWork.MAX_ATTEMPTS, runs)
        assertEquals(listOf(LogEvent.OperationFailed("run background task", "consume-retry: gave up after 3 runs")), logger.events)

        work.enqueue(job)
        scope.runCurrent()
        assertEquals(InProcessBackgroundWork.MAX_ATTEMPTS + 1, runs, "a finished job can be enqueued again")
    }

    @Test
    fun `a task that throws is logged and ends the job, and a job with no task is refused`() {
        val throwing = InProcessBackgroundWork(scope, logger) { mapOf(BackgroundTaskKind.ConsumeRetry to BackgroundTask { error("boom") }) }
        throwing.enqueue(job)
        scope.runCurrent()
        assertEquals(listOf(LogEvent.OperationFailed("run background task", "consume-retry: IllegalStateException")), logger.events)

        val unknown = BackgroundJob("price-refresh", BackgroundTaskKind.PriceRefresh, needsNetwork = true)
        assertIs<Outcome.Failure<DomainError>>(work.enqueue(unknown))
    }

    @Test
    fun `a periodic job runs once every period, with its retries, for as long as the process lives`() {
        assertEquals(Outcome.Success(Unit), work.enqueue(PurchaseLedger.CONSUME_RETRY_PERIODIC))
        scope.advanceTimeBy(6.hours - 1.seconds)
        scope.runCurrent()
        assertEquals(0, runs, "the one-time job runs at once; the periodic one waits a period")
        scope.advanceTimeBy(1.seconds)
        scope.runCurrent()
        assertEquals(1, runs)
        scope.advanceTimeBy(18.hours)
        scope.runCurrent()
        assertEquals(4, runs)
    }

    /** The real ledger over this worker, with Play failing every consume (a phone offline overnight). */
    private class OfflineNight(
        val scope: TestScope,
    ) {
        val logger = FakeLogger()
        val ledgerRows = FakeGrantLedgerStore()
        val billing = FakeBilling(consumeResult = ConsumeResult.Failed("offline"))
        lateinit var ledger: PurchaseLedger
        val work = InProcessBackgroundWork(scope, logger) { mapOf(BackgroundTaskKind.ConsumeRetry to ConsumeRetryTask(ledger)) }

        init {
            ledger =
                PurchaseLedger(
                    ledgerRows,
                    FakePurchaseRecordRepository(),
                    FakePurchaseIntentStore(),
                    billing,
                    work,
                    PriceSnapshotLookup.None,
                    FakeClock(),
                    logger,
                )
            ledgerRows.put(aGrant())
        }
    }

    @Test
    fun `the ledger's retries give up after 3 runs, then the periodic job keeps going while the payment is unsettled`() {
        val night = OfflineNight(scope)

        scope.launch { night.ledger.settle(PurchaseToken("token-1")) }
        scope.advanceTimeBy(1.hours)
        scope.runCurrent()

        assertEquals(4, night.billing.consumed.size, "the grant's settle, then 3 runs of the one-time job")
        assertTrue(night.logger.events.contains(LogEvent.OperationFailed("run background task", "consume-retry: gave up after 3 runs")))

        scope.advanceTimeBy(6.hours)
        scope.runCurrent()
        assertTrue(night.billing.consumed.size > 4, "the periodic job tried again: ${night.billing.consumed.size}")

        night.billing.consumeResult = ConsumeResult.Consumed
        scope.advanceTimeBy(6.hours)
        scope.runCurrent()
        assertEquals(emptyList(), night.ledgerRows.entries.filter { it.settledAt == null }, "settled once Play is back")
    }
}
