package com.yawnandpawn.app.core.work

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlin.time.Duration

/**
 * What a background job runs (AD-17). The adapter stores the name with the job and looks up the [BackgroundTask]
 * registered for it when the job runs. Later stories add their kinds: the consume retry (Story 4.10) and the weekly
 * summary (Epic 6).
 */
enum class BackgroundTaskKind {
    /** Refresh the cached Play prices (Story 4.3). */
    PriceRefresh,

    /** Consume the granted purchases left in the grant ledger (Story 4.10). */
    ConsumeRetry,
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

/**
 * Port for deferred work that is not an alarm (AD-17): price refresh, consume retries, the weekly summary. The Android
 * adapter uses WorkManager. Nothing on the wake path depends on it: [enqueue] only hands the job over and never waits
 * for it to run. It fails (never throws) when the job cannot be handed over, for example before the first unlock.
 */
fun interface BackgroundWork {
    fun enqueue(job: BackgroundJob): Outcome<Unit, DomainError>
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
