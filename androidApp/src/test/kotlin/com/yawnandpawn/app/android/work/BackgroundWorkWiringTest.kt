package com.yawnandpawn.app.android.work

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.core.billing.ConsumeRetryTask
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceRefreshJobs
import com.yawnandpawn.app.core.billing.PriceRefreshScheduler
import com.yawnandpawn.app.core.billing.ProductDetailsSource
import com.yawnandpawn.app.core.billing.PurchaseLedger
import com.yawnandpawn.app.core.billing.SessionStartPriceRefresh
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.work.BackgroundTask
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.TaskResult
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeProductDetailsSource
import com.yawnandpawn.app.testing.FakeUserLockState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours

/**
 * Story 4.3 (AD-17): the WorkManager adapter, on WorkManager's own test setup (`WorkManagerTestInitHelper` and its
 * `TestDriver`). The price refresh jobs are unique, need the network and run the price refresh through the app's one
 * worker; nothing reaches WorkManager before the first unlock; WorkManager starts on demand, not at app start.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundWorkWiringTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()
    private val source = FakeProductDetailsSource()
    private val log = FakeLogger()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
    }

    // WorkManager's test instance is process-wide: closed here, so no later test of this JVM finds its database open.
    @After
    fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    private fun start(vararg overrides: Module) =
        restartKoin(
            app,
            module {
                single<ProductDetailsSource> { source }
                single<Logger> { log }
                single<UserLockState> { FakeUserLockState(unlocked = true) }
                // The real adapter instead of the tests' fake.
                single<BackgroundWork> { AndroidBackgroundWork(app, get(), get()) }
            },
            *overrides,
        )

    private val workManager: WorkManager
        get() = WorkManager.getInstance(app)

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).getBounded()

    /** Every WorkManager answer within [APP_WORK_TIMEOUT_MILLIS]: a stuck one fails the test instead of hanging it. */
    private fun <T> ListenableFuture<T>.getBounded(): T = get(APP_WORK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

    private fun awaitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + APP_WORK_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        fail("timed out waiting until $what")
    }

    @Test
    fun `the scheduler enqueues the two unique price refresh jobs, with a network constraint and a daily period`() {
        start()
        val koin = GlobalContext.get()

        assertTrue(runBlocking { koin.get<PriceRefreshScheduler>().start() })

        val now = assertNotNull(infos(PriceRefreshJobs.NOW.uniqueName).singleOrNull())
        assertEquals(WorkInfo.State.ENQUEUED, now.state)
        assertEquals(NetworkType.CONNECTED, now.constraints.requiredNetworkType)
        assertTrue(AndroidBackgroundWork.TAG in now.tags)
        assertEquals(null, now.periodicityInfo)

        val daily = assertNotNull(infos(PriceRefreshJobs.DAILY.uniqueName).singleOrNull())
        assertEquals(WorkInfo.State.ENQUEUED, daily.state)
        assertEquals(NetworkType.CONNECTED, daily.constraints.requiredNetworkType)
        assertEquals(TimeUnit.HOURS.toMillis(24), daily.periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun `the consume retry jobs (Story 4-10) are unique WorkManager work run by the ledger's task`() {
        start()
        val work = GlobalContext.get().get<BackgroundWork>()

        assertEquals(Outcome.Success(Unit), work.enqueue(PurchaseLedger.CONSUME_RETRY_JOB))
        assertEquals(Outcome.Success(Unit), work.enqueue(PurchaseLedger.CONSUME_RETRY_PERIODIC))

        val once = assertNotNull(infos(PurchaseLedger.CONSUME_RETRY_JOB.uniqueName).singleOrNull())
        assertEquals(NetworkType.CONNECTED, once.constraints.requiredNetworkType)
        assertEquals(null, once.periodicityInfo)
        val periodic = assertNotNull(infos(PurchaseLedger.CONSUME_RETRY_PERIODIC.uniqueName).singleOrNull())
        assertEquals(TimeUnit.HOURS.toMillis(6), periodic.periodicityInfo?.repeatIntervalMillis)
        assertIs<ConsumeRetryTask>(GlobalContext.get().get<BackgroundTasks>().taskFor(BackgroundTaskKind.ConsumeRetry))
    }

    @Test
    fun `a pending one-time job is kept, and the periodic job is updated in place`() {
        start()
        val work = GlobalContext.get().get<BackgroundWork>()
        PriceRefreshJobs.ALL.forEach { assertEquals(Outcome.Success(Unit), work.enqueue(it)) }
        val first = PriceRefreshJobs.ALL.map { infos(it.uniqueName).single().id }

        PriceRefreshJobs.ALL.forEach { assertEquals(Outcome.Success(Unit), work.enqueue(it)) }

        assertEquals(first, PriceRefreshJobs.ALL.map { infos(it.uniqueName).single().id })
    }

    @Test
    fun `enqueueing the daily job with a new period updates it in place`() {
        start()
        val work = GlobalContext.get().get<BackgroundWork>()
        assertEquals(Outcome.Success(Unit), work.enqueue(PriceRefreshJobs.DAILY))
        val first = infos(PriceRefreshJobs.DAILY.uniqueName).single()

        assertEquals(Outcome.Success(Unit), work.enqueue(PriceRefreshJobs.DAILY.copy(repeatEvery = 48.hours)))

        val updated = infos(PriceRefreshJobs.DAILY.uniqueName).single()
        assertEquals(first.id, updated.id, "UPDATE keeps the job")
        assertEquals(TimeUnit.HOURS.toMillis(48), updated.periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun `the worker and the session-start refresh share one catalog, so Play is asked once at a time`() {
        start()
        val koin = GlobalContext.get()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate

        val sessionRefresh = assertNotNull(koin.get<SessionStartPriceRefresh>().onSessionStarted())
        awaitUntil("the session-start refresh asked Play") { source.requests.size == 1 }
        var workerResult: ListenableWorker.Result? = null
        // A daemon thread: one left waiting can never keep the test JVM alive.
        val worker = thread(isDaemon = true) { workerResult = runWorker(BackgroundTaskKind.PriceRefresh.name) }
        Thread.sleep(SETTLE_MILLIS)

        assertEquals(1, source.requests.size, "the worker's refresh waits for the one in flight")
        gate.complete(Unit)
        worker.join(APP_WORK_TIMEOUT_MILLIS)
        runBlocking { withTimeout(APP_WORK_TIMEOUT_MILLIS) { sessionRefresh.join() } }

        assertEquals(2, source.requests.size)
        assertEquals(ListenableWorker.Result.success(), workerResult)
    }

    @Test
    fun `a job WorkManager fails to store is logged`() {
        val work = AndroidBackgroundWork(app, FakeUserLockState(unlocked = true), log) { fail("not used") }

        work.watch(FailedFuture(IllegalStateException("disk full")), "price-refresh")
        work.watch(FailedFuture(null), "price-refresh-now")

        assertEquals(
            listOf<LogEvent>(
                LogEvent.OperationFailed(AndroidBackgroundWork.ENQUEUE, "price-refresh: IllegalStateException"),
                LogEvent.OperationFailed(AndroidBackgroundWork.ENQUEUE, "price-refresh-now: CancellationException"),
            ),
            log.events,
        )
    }

    /** A finished future that failed with [cause], or was cancelled when [cause] is null. */
    private class FailedFuture(
        private val cause: Throwable?,
    ) : ListenableFuture<Operation.State.SUCCESS> {
        override fun addListener(
            listener: Runnable,
            executor: Executor,
        ) = executor.execute(listener)

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        override fun isCancelled(): Boolean = cause == null

        override fun isDone(): Boolean = true

        override fun get(): Operation.State.SUCCESS =
            throw cause?.let { ExecutionException(it) } ?: java.util.concurrent.CancellationException("cancelled")

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Operation.State.SUCCESS = get()
    }

    @Test
    fun `when the network comes, the job runs the price refresh and fills the cache`() {
        start()
        val koin = GlobalContext.get()
        assertEquals(Outcome.Success(Unit), koin.get<BackgroundWork>().enqueue(PriceRefreshJobs.NOW))
        val id = infos(PriceRefreshJobs.NOW.uniqueName).single().id
        assertEquals(emptyList(), source.requests, "nothing runs before the constraint is met")

        assertNotNull(WorkManagerTestInitHelper.getTestDriver(app)).setAllConstraintsMet(id)

        awaitUntil("the job succeeded") { workManager.getWorkInfoById(id).getBounded()?.state == WorkInfo.State.SUCCEEDED }
        assertEquals(listOf(SnoozeProducts.all), source.requests)
        assertEquals(SnoozeProducts.all.size, runBlocking { koin.get<PriceCatalog>().observe().first() }.entries.size)
    }

    @Test
    fun `nothing reaches WorkManager while the user is locked`() {
        val work =
            AndroidBackgroundWork(app, FakeUserLockState(unlocked = false), log) { fail("WorkManager must not start before the unlock") }

        val result = work.enqueue(PriceRefreshJobs.NOW)

        assertIs<DomainError.BackgroundWorkFailure>(assertIs<Outcome.Failure<DomainError>>(result).error)
    }

    @Test
    fun `a WorkManager that throws is a failure value, not a crash`() {
        val work = AndroidBackgroundWork(app, FakeUserLockState(unlocked = true), log) { throw IllegalStateException("no database") }

        val error = assertIs<Outcome.Failure<DomainError>>(work.enqueue(PriceRefreshJobs.DAILY)).error

        assertEquals(DomainError.BackgroundWorkFailure("price-refresh: IllegalStateException"), error)
    }

    private fun runWorker(
        task: String?,
        attempt: Int = 0,
    ): ListenableWorker.Result =
        runBlocking {
            TestListenableWorkerBuilder<BackgroundTaskWorker>(app)
                .setInputData(if (task == null) workDataOf() else workDataOf(BackgroundTaskWorker.KEY_TASK to task))
                .setRunAttemptCount(attempt)
                .build()
                .doWork()
        }

    private fun tasks(answer: () -> TaskResult) =
        module { single { BackgroundTasks(mapOf(BackgroundTaskKind.PriceRefresh to BackgroundTask { answer() })) } }

    @Test
    fun `the worker maps the task's result, retrying a transient failure up to three runs`() {
        var answer = TaskResult.Done
        start(tasks { answer })
        val name = BackgroundTaskKind.PriceRefresh.name

        assertEquals(ListenableWorker.Result.success(), runWorker(name))
        answer = TaskResult.Failed
        assertEquals(ListenableWorker.Result.failure(), runWorker(name))
        answer = TaskResult.RetryLater
        assertEquals(ListenableWorker.Result.retry(), runWorker(name, attempt = 0))
        assertEquals(ListenableWorker.Result.retry(), runWorker(name, attempt = 1))
        assertEquals(ListenableWorker.Result.failure(), runWorker(name, attempt = BackgroundTaskWorker.MAX_ATTEMPTS - 1))
    }

    @Test
    fun `a job with no known task, or a task that throws, fails and is logged`() {
        start(tasks { throw IllegalStateException("boom") })

        assertEquals(ListenableWorker.Result.failure(), runWorker("WeeklySummaryFromTheFuture"))
        assertEquals(ListenableWorker.Result.failure(), runWorker(null))
        assertEquals(ListenableWorker.Result.failure(), runWorker(BackgroundTaskKind.PriceRefresh.name))

        assertEquals(
            listOf<LogEvent>(
                LogEvent.OperationFailed("run background task", "no task for WeeklySummaryFromTheFuture"),
                LogEvent.OperationFailed("run background task", "no task for null"),
                LogEvent.OperationFailed("run background task", "PriceRefresh: IllegalStateException"),
            ),
            log.events.filter { it is LogEvent.OperationFailed && it.operation == "run background task" },
        )
    }

    @Test
    fun `the price refresh task is registered and runs the catalog refresh`() {
        start()
        source.failWith(DomainError.ProductDetailsFailed(transient = true, cause = "offline"))

        assertEquals(ListenableWorker.Result.retry(), runWorker(BackgroundTaskKind.PriceRefresh.name))
        assertEquals(1, source.requests.size)
    }

    @Test
    fun `the production source fails without retrying until the Play adapter arrives`() =
        runBlocking {
            val result = UnavailableProductDetailsSource().fetch(SnoozeProducts.all)

            val error = assertIs<DomainError.ProductDetailsFailed>(assertIs<Outcome.Failure<DomainError>>(result).error)
            assertFalse(error.transient)
        }

    @Test
    fun `WorkManager is not started by the manifest, and the app gives its configuration`() {
        @Suppress("DEPRECATION") // The flags are the only way to read these parts of the merged manifest.
        val provider =
            app.packageManager.getProviderInfo(
                ComponentName(app, "androidx.startup.InitializationProvider"),
                PackageManager.GET_META_DATA,
            )
        val initializers = provider.metaData?.keySet().orEmpty()

        assertFalse("androidx.work.WorkManagerInitializer" in initializers, "initializers: $initializers")
        assertTrue(initializers.isNotEmpty(), "the other startup initializers stay: $initializers")
        assertIs<Configuration.Provider>(app)
        assertEquals(Log.INFO, app.workManagerConfiguration.minimumLoggingLevel)
    }

    private companion object {
        const val POLL_MILLIS = 20L
        const val SETTLE_MILLIS = 300L
    }
}
