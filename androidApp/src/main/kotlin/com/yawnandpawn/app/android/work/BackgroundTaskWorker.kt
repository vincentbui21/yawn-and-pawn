package com.yawnandpawn.app.android.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.work.BackgroundTask
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.TaskResult
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.coroutines.cancellation.CancellationException

/** The task registered for each [BackgroundTaskKind] (a Koin single built in `workModule`). */
class BackgroundTasks(
    private val tasks: Map<BackgroundTaskKind, BackgroundTask>,
) {
    fun taskFor(kind: BackgroundTaskKind): BackgroundTask? = tasks[kind]
}

/**
 * The one WorkManager worker of the app (AD-17): runs the [BackgroundTask] registered for the job's
 * [BackgroundTaskKind] (input [KEY_TASK]). [TaskResult.RetryLater] retries with the job's backoff until
 * [MAX_ATTEMPTS] runs, then fails; an unknown kind (a job left by another app version) or a task that throws fails
 * and is logged. It runs after `Application.onCreate`, so the app's Koin graph is there.
 */
class BackgroundTaskWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {
    // A task that throws must not crash the process; it is logged and the run fails (AD-12).
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val logger = get<Logger>()
        val name = inputData.getString(KEY_TASK)
        val task = BackgroundTaskKind.entries.firstOrNull { it.name == name }?.let { get<BackgroundTasks>().taskFor(it) }
        if (task == null) {
            logger.log(LogEvent.OperationFailed(RUN, "no task for $name"))
            return Result.failure()
        }
        return try {
            when (task.run()) {
                TaskResult.Done -> Result.success()
                TaskResult.RetryLater -> if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
                TaskResult.Failed -> Result.failure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(RUN, "$name: ${e::class.simpleName}"))
            Result.failure()
        }
    }

    companion object {
        /** The input naming the job's [BackgroundTaskKind]. */
        const val KEY_TASK = "task"

        /** Runs per job before a transient failure gives up (a periodic job runs again at its next period). */
        const val MAX_ATTEMPTS = 3

        private const val RUN = "run background task"
    }
}
