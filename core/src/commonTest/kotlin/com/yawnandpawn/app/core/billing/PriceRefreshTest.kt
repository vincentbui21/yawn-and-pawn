package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.alarm.RecordingLogger
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.TaskResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Story 4.3: the price refresh jobs, their task, the scheduler at app start and the refresh at session start. */
class PriceRefreshTest {
    private class Catalog(
        var result: Outcome<Unit, DomainError> = Outcome.Success(Unit),
    ) : PriceCatalog {
        var refreshes = 0
        var gate: CompletableDeferred<Unit>? = null
        var completed = 0

        override fun observe(): Flow<PriceCatalogSnapshot> = MutableStateFlow(PriceCatalogSnapshot.EMPTY)

        override suspend fun refresh(): Outcome<Unit, DomainError> {
            refreshes++
            gate?.await()
            completed++
            return result
        }
    }

    private class Lock(
        var unlocked: Boolean,
    ) : UserLockState {
        override fun isUserUnlocked(): Boolean = unlocked

        override fun observe(): Flow<Boolean> = flowOf(unlocked)
    }

    private class Work : BackgroundWork {
        val enqueued = mutableListOf<BackgroundJob>()
        var failure: DomainError? = null

        override fun enqueue(job: BackgroundJob): Outcome<Unit, DomainError> {
            failure?.let { return Outcome.Failure(it) }
            enqueued += job
            return Outcome.Success(Unit)
        }
    }

    private val logger = RecordingLogger()

    @Test
    fun `the two jobs need the network and run the price refresh, one of them every day`() {
        assertEquals(BackgroundJob("price-refresh-now", BackgroundTaskKind.PriceRefresh, needsNetwork = true), PriceRefreshJobs.NOW)
        assertEquals(
            BackgroundJob("price-refresh", BackgroundTaskKind.PriceRefresh, needsNetwork = true, repeatEvery = 24.hours),
            PriceRefreshJobs.DAILY,
        )
        assertEquals(listOf(PriceRefreshJobs.NOW, PriceRefreshJobs.DAILY), PriceRefreshJobs.ALL)
    }

    @Test
    fun `the task maps the refresh result`() =
        runTest {
            val table: List<Pair<Outcome<Unit, DomainError>, TaskResult>> =
                listOf(
                    Outcome.Success(Unit) to TaskResult.Done,
                    Outcome.Failure(DomainError.ProductDetailsFailed(transient = true, cause = "offline")) to TaskResult.RetryLater,
                    Outcome.Failure(DomainError.StorageFailure("disk")) to TaskResult.RetryLater,
                    Outcome.Failure(DomainError.ProductDetailsFailed(transient = false, cause = "no billing")) to TaskResult.Failed,
                    Outcome.Failure(DomainError.NotFound("x")) to TaskResult.Failed,
                )
            table.forEach { (refreshed, expected) ->
                assertEquals(expected, PriceRefreshTask(Catalog(refreshed)).run(), "$refreshed")
            }
        }

    @Test
    fun `the scheduler enqueues nothing while the user is locked, both jobs once unlocked, and only once`() =
        runTest {
            val work = Work()
            val lock = Lock(unlocked = false)
            val scheduler = PriceRefreshScheduler(work, lock, logger)

            assertFalse(scheduler.start())
            assertEquals(emptyList(), work.enqueued)

            lock.unlocked = true
            assertTrue(scheduler.start())
            assertTrue(scheduler.start())

            assertEquals(PriceRefreshJobs.ALL, work.enqueued)
            assertEquals(emptyList(), logger.events)
        }

    @Test
    fun `a refused enqueue is logged and tried again on the next start`() =
        runTest {
            val refused = DomainError.BackgroundWorkFailure("locked")
            val work = Work().apply { failure = refused }
            val scheduler = PriceRefreshScheduler(work, Lock(unlocked = true), logger)

            assertFalse(scheduler.start())
            assertEquals<List<LogEvent>>(
                PriceRefreshJobs.ALL.map { LogEvent.OperationFailed.of(PriceRefreshScheduler.SCHEDULE_PRICE_REFRESH, refused) },
                logger.events,
            )

            work.failure = null
            assertTrue(scheduler.start())
            assertEquals(PriceRefreshJobs.ALL, work.enqueued)
        }

    @Test
    fun `a session start launches one refresh and never waits for it`() =
        runTest {
            val catalog = Catalog().apply { gate = CompletableDeferred() }
            val refresh = SessionStartPriceRefresh(catalog, Lock(unlocked = true), this)

            val job = assertNotNull(refresh.onSessionStarted())
            runCurrent()

            assertEquals(1, catalog.refreshes)
            assertEquals(0, catalog.completed, "returned while Play is still answering")
            assertFalse(job.isCompleted)

            catalog.gate?.complete(Unit)
            job.join()
            assertEquals(1, catalog.completed)
        }

    @Test
    fun `a session start before the first unlock refreshes nothing`() =
        runTest {
            val catalog = Catalog()

            assertNull(SessionStartPriceRefresh(catalog, Lock(unlocked = false), this).onSessionStarted())
            runCurrent()

            assertEquals(0, catalog.refreshes)
        }
}
