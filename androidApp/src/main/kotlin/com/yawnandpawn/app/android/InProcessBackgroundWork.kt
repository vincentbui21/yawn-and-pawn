package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundTask
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.TaskResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A stand-in [BackgroundWork] (Story 4.10) until Story 4.3's WorkManager adapter (`AndroidBackgroundWork`) is merged and
 * bound instead: it runs a one-time job in this process on [scope], unique by name (a job still running keeps it, like
 * WorkManager's KEEP), retrying a [TaskResult.RetryLater] with exponential backoff from [backoff] for at most
 * [MAX_ATTEMPTS] runs. A periodic job runs that way once every period, for as long as the process lives. It does not
 * survive the process and has no network constraint; the work it runs (the consume retry) is also run again on every
 * app start and unlock signal. Never on the wake path: the job runs on [scope], and [enqueue] only launches it.
 */
class InProcessBackgroundWork(
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val backoff: Duration = BACKOFF,
    private val tasks: () -> Map<BackgroundTaskKind, BackgroundTask>,
) : BackgroundWork {
    private val running = mutableSetOf<String>()

    override fun enqueue(job: BackgroundJob): Outcome<Unit, DomainError> {
        val task = tasks()[job.task] ?: return Outcome.Failure(DomainError.NotFound("task ${job.task} for ${job.uniqueName}"))
        val fresh = synchronized(running) { running.add(job.uniqueName) }
        if (fresh) {
            scope.launch {
                try {
                    val every = job.repeatEvery
                    if (every == null) {
                        runWithRetries(job, task)
                    } else {
                        while (true) {
                            delay(every)
                            runWithRetries(job, task)
                        }
                    }
                } finally {
                    synchronized(running) { running.remove(job.uniqueName) }
                }
            }
        }
        return Outcome.Success(Unit)
    }

    // A task that throws is logged and ends the job, like the WorkManager worker (AD-12).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runWithRetries(
        job: BackgroundJob,
        task: BackgroundTask,
    ) {
        var wait = backoff
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                delay(wait)
                wait *= 2
            }
            val result =
                try {
                    task.run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.log(LogEvent.OperationFailed(RUN, "${job.uniqueName}: ${e::class.simpleName}"))
                    return
                }
            if (result != TaskResult.RetryLater) return
        }
        logger.log(LogEvent.OperationFailed(RUN, "${job.uniqueName}: gave up after $MAX_ATTEMPTS runs"))
    }

    companion object {
        /** Runs per job, as in Story 4.3's worker; the next app start or resume tries again. */
        const val MAX_ATTEMPTS = 3

        /** The first retry delay, as in Story 4.3's adapter; doubled for each later run. */
        val BACKOFF: Duration = 30.seconds

        private const val RUN = "run background task"
    }
}
