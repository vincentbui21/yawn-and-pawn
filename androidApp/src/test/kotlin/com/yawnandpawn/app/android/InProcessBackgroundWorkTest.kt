package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.billing.PurchaseLedger
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundTask
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.TaskResult
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `a task that throws is logged and ends the job, and periodic or unknown jobs are refused`() {
        val throwing = InProcessBackgroundWork(scope, logger) { mapOf(BackgroundTaskKind.ConsumeRetry to BackgroundTask { error("boom") }) }
        throwing.enqueue(job)
        scope.runCurrent()
        assertEquals(listOf(LogEvent.OperationFailed("run background task", "consume-retry: IllegalStateException")), logger.events)

        assertIs<Outcome.Failure<DomainError>>(work.enqueue(job.copy(repeatEvery = 24.hours)))
        val unknown = BackgroundJob("price-refresh", BackgroundTaskKind.PriceRefresh, needsNetwork = true)
        assertIs<Outcome.Failure<DomainError>>(work.enqueue(unknown))
    }
}
