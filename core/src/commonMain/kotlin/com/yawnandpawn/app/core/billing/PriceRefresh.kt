package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundTask
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.TaskResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The price refresh jobs (Story 4.3, AD-17): both need a network connection and run [BackgroundTaskKind.PriceRefresh]. */
object PriceRefreshJobs {
    /** One refresh as soon as the phone is online, enqueued at app start (and after the first unlock). */
    val NOW = BackgroundJob("price-refresh-now", BackgroundTaskKind.PriceRefresh, needsNetwork = true)

    /** The daily refresh. */
    val DAILY =
        BackgroundJob("price-refresh", BackgroundTaskKind.PriceRefresh, needsNetwork = true, repeatEvery = PriceCachePolicy.STALE_AFTER)

    val ALL: List<BackgroundJob> = listOf(NOW, DAILY)
}

/**
 * The background task of the price refresh jobs: one [PriceCatalog.refresh]. A transient failure (Play busy or
 * offline, a storage error) is [TaskResult.RetryLater]; any other failure waits for the next run.
 */
class PriceRefreshTask(
    private val catalog: PriceCatalog,
) : BackgroundTask {
    override suspend fun run(): TaskResult =
        when (val refreshed = catalog.refresh()) {
            is Outcome.Success -> TaskResult.Done
            is Outcome.Failure -> if (refreshed.error.isTransient()) TaskResult.RetryLater else TaskResult.Failed
        }

    private fun DomainError.isTransient(): Boolean =
        (this is DomainError.ProductDetailsFailed && transient) || this is DomainError.StorageFailure
}

/**
 * Schedules the price refresh jobs ([PriceRefreshJobs]) at app start and again at the first unlock (AD-15, AD-17):
 * WorkManager keeps its database in credential-protected storage, so nothing is enqueued while the user is locked
 * (the phone booted and not unlocked yet). Once both jobs are handed over, later calls do nothing for the rest of the
 * process. A failed enqueue is logged and tried again on the next call. The caller runs it off the main thread and
 * never awaits it on the wake path.
 */
class PriceRefreshScheduler(
    private val work: BackgroundWork,
    private val userLock: UserLockState,
    private val logger: Logger,
) {
    private val lock = Mutex()
    private var scheduled = false

    /** Enqueues both jobs when the user is unlocked; true once they are scheduled. */
    suspend fun start(): Boolean =
        lock.withLock {
            if (!scheduled && userLock.isUserUnlocked()) {
                val failures = PriceRefreshJobs.ALL.mapNotNull { (work.enqueue(it) as? Outcome.Failure)?.error }
                failures.forEach { logger.log(LogEvent.OperationFailed.of(SCHEDULE_PRICE_REFRESH, it)) }
                scheduled = failures.isEmpty()
            }
            scheduled
        }

    companion object {
        /** The operation logged when a price refresh job could not be enqueued. */
        const val SCHEDULE_PRICE_REFRESH = "schedule price refresh"
    }
}

/**
 * The price refresh at each session start (PRD §6.2): when a real alarm starts a session and the user is unlocked,
 * one [PriceCatalog.refresh] is launched on [scope] and never awaited, so the ring never waits for Play. Before the
 * first unlock it does nothing (Play Billing starts only after it, AD-15). The wake screen renders from the cached
 * snapshot meanwhile.
 */
class SessionStartPriceRefresh(
    private val catalog: PriceCatalog,
    private val userLock: UserLockState,
    private val scope: CoroutineScope,
) {
    /** The launched refresh, or null when the user is locked. */
    fun onSessionStarted(): Job? = if (userLock.isUserUnlocked()) scope.launch { catalog.refresh() } else null
}
