package com.yawnandpawn.app.core.work

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlin.time.Duration

/**
 * What a background job runs (AD-17). The adapter stores the name with the job and looks up the [BackgroundTask]
 * registered for it when the job runs: the price refresh (Story 4.3), the consume retry (Story 4.10) and the weekly
 * summary (Story 6.8).
 */
enum class BackgroundTaskKind {
    /** Refresh the cached Play prices (Story 4.3). */
    PriceRefresh,

    /** Consume the granted purchases left in the grant ledger (Story 4.10). */
    ConsumeRetry,

    /** Post the Sunday-evening weekly summary and chain next week's run (Story 6.8, [WeeklySummaryJobs]). */
    WeeklySummary,
}

/**
 * One piece of deferred, non-alarm work (AD-17).
 * - [uniqueName] identifies it: enqueueing a one-time job whose name is still pending keeps the pending one; a periodic
 *   job with the same name is updated in place.
 * - [task] is what runs.
 * - [needsNetwork] waits for a network connection before running.
 * - [repeatEvery] makes it periodic; null runs it once.
 */
data class BackgroundJob(
    val uniqueName: String,
    val task: BackgroundTaskKind,
    val needsNetwork: Boolean,
    val repeatEvery: Duration? = null,
)

/** The weekly summary's one-time unique work (Story 6.8). */
object WeeklySummaryJobs {
    const val NAME = "weekly-summary"
}

/** How [BackgroundWork.scheduleWeeklySummary] hands the job over when a `weekly-summary` work already exists. */
enum class SchedulePolicy {
    /** Replace a pending or running one (a time or zone change, the switch turned on). WorkManager REPLACE. */
    Replace,

    /** Keep a pending or running one; enqueue only when there is none (app start, a run due now). WorkManager KEEP. */
    KeepPending,

    /**
     * From inside the running summary: chained after it, so the run is never cancelled by its successor. APPEND_OR_REPLACE,
     * skipped when a successor is already pending (a run executed again after its process died), so the chain never forks.
     */
    AfterRun,
}

/**
 * Port for deferred work that is not an alarm (AD-17): price refresh, consume retries, the weekly summary. The Android
 * adapter uses WorkManager. Nothing on the wake path depends on it: each call only hands the job over and never waits
 * for it to run. It fails (never throws) when the job cannot be handed over, for example before the first unlock.
 */
interface BackgroundWork {
    fun enqueue(job: BackgroundJob): Outcome<Unit, DomainError>

    /**
     * The weekly summary (Story 6.8): one-time unique work [WeeklySummaryJobs.NAME] running [BackgroundTaskKind.WeeklySummary]
     * after [delay] (a negative one counts as none), with no network needed, handed over by [policy].
     */
    fun scheduleWeeklySummary(
        delay: Duration,
        policy: SchedulePolicy,
    ): Outcome<Unit, DomainError>

    /** Cancels the unique work [WeeklySummaryJobs.NAME] (Story 6.8); nothing pending is not an error. */
    fun cancelWeeklySummary(): Outcome<Unit, DomainError>
}

/** How a [BackgroundTask] run ended. */
enum class TaskResult {
    /** Finished; a periodic job runs again at its next period. */
    Done,

    /** A transient failure: run it again later, with backoff (the adapter caps the attempts). */
    RetryLater,

    /** Failed in a way retrying soon will not fix; a periodic job still runs at its next period. */
    Failed,
}

/** The work behind one [BackgroundTaskKind]. Runs off the main thread, outside any session. */
fun interface BackgroundTask {
    suspend fun run(): TaskResult
}
