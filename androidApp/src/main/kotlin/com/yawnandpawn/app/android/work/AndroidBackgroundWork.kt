package com.yawnandpawn.app.android.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.PreAlarmJobs
import com.yawnandpawn.app.core.work.SchedulePolicy
import com.yawnandpawn.app.core.work.SkipReminderJobs
import com.yawnandpawn.app.core.work.WeeklySummaryJobs
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * [BackgroundWork] over WorkManager 2.11 (AD-17, Story 4.3). Each job is unique work under its name: a one-time job
 * keeps a pending one of the same name (KEEP), a periodic job is updated in place (UPDATE). A job that needs the
 * network waits for a connection; a retry backs off exponentially from [BACKOFF_SECONDS].
 *
 * WorkManager keeps its database in credential-protected storage, so it must never start before the first unlock
 * (Direct Boot): the default initializer is removed from the manifest, the app is a `Configuration.Provider` (WorkManager
 * starts on the first [workManager] call), and [enqueue] refuses while the user is locked, without touching WorkManager.
 * Handing a job over never waits for it to run; callers still call this off the main thread, as the first call starts
 * WorkManager. WorkManager stores the job asynchronously: a store that fails later is logged ([watch]).
 *
 * The weekly summary (Story 6.8) is one-time unique work [WeeklySummaryJobs.NAME] with an initial delay and no network
 * constraint, handed over with the caller's [SchedulePolicy] (REPLACE, KEEP or APPEND_OR_REPLACE) and cancelled by
 * name, under the same lock guard and failure boundary as [enqueue].
 *
 * The Skip reminder (0.1.8) is one-time unique work [SkipReminderJobs.NAME] with an initial delay and no network
 * constraint, handed over like the weekly summary (REPLACE, or chained after its own run), cancelled by name, under the
 * same guards. Story 7.11's per-alarm pre-alarm jobs ([PreAlarmJobs.nameOf],
 * `pre-alarm-{alarmId}`) are only cancelled (the update cleanup); nothing enqueues them any more.
 */
class AndroidBackgroundWork(
    private val context: Context,
    private val userLock: UserLockState,
    private val logger: Logger,
    private val workManager: () -> WorkManager = { WorkManager.getInstance(context) },
) : BackgroundWork {
    override fun enqueue(job: BackgroundJob): Outcome<Unit, DomainError> = guarded(job.uniqueName) { hand(job) }

    override fun scheduleWeeklySummary(
        delay: Duration,
        policy: SchedulePolicy,
    ): Outcome<Unit, DomainError> =
        guarded(WeeklySummaryJobs.NAME) {
            // A run executed again after its process died already chained its successor: a second one would fork the chain.
            if (policy == SchedulePolicy.AfterRun && successorPending(WeeklySummaryJobs.NAME)) return@guarded
            val request =
                OneTimeWorkRequestBuilder<BackgroundTaskWorker>()
                    .setInitialDelay(delay.coerceAtLeast(Duration.ZERO).inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(BackgroundTaskWorker.KEY_TASK to BackgroundTaskKind.WeeklySummary.name))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .build()
            watch(workManager().enqueueUniqueWork(WeeklySummaryJobs.NAME, existingOf(policy), request).result, WeeklySummaryJobs.NAME)
        }

    override fun cancelWeeklySummary(): Outcome<Unit, DomainError> =
        guarded(WeeklySummaryJobs.NAME) { watch(workManager().cancelUniqueWork(WeeklySummaryJobs.NAME).result, WeeklySummaryJobs.NAME) }

    override fun scheduleSkipReminder(
        delay: Duration,
        policy: SchedulePolicy,
    ): Outcome<Unit, DomainError> =
        guarded(SkipReminderJobs.NAME) {
            // No successor check here (unlike the weekly summary): a run executed again after its process died appends a
            // second successor to the same chain, whose leaves merge on the next append, and each run only re-syncs.
            val request =
                OneTimeWorkRequestBuilder<BackgroundTaskWorker>()
                    .setInitialDelay(delay.coerceAtLeast(Duration.ZERO).inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(BackgroundTaskWorker.KEY_TASK to BackgroundTaskKind.SkipReminder.name))
                    .addTag(TAG)
                    .build()
            watch(workManager().enqueueUniqueWork(SkipReminderJobs.NAME, existingOf(policy), request).result, SkipReminderJobs.NAME)
        }

    override fun cancelSkipReminder(): Outcome<Unit, DomainError> =
        guarded(SkipReminderJobs.NAME) { watch(workManager().cancelUniqueWork(SkipReminderJobs.NAME).result, SkipReminderJobs.NAME) }

    override fun cancelPreAlarm(alarmId: String): Outcome<Unit, DomainError> {
        val name = PreAlarmJobs.nameOf(alarmId)
        return guarded(name) { watch(workManager().cancelUniqueWork(name).result, name) }
    }

    /** A [name] work waits to run (ENQUEUED, or BLOCKED behind the running one). */
    private fun successorPending(name: String): Boolean =
        workManager()
            .getWorkInfosForUniqueWork(name)
            .get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }

    /**
     * Runs [hand] when the user is unlocked, else fails without touching WorkManager. Whatever WorkManager throws (its
     * database, a bad request) is a failure value naming [name], never a crash (AD-12).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun guarded(
        name: String,
        hand: () -> Unit,
    ): Outcome<Unit, DomainError> {
        if (!userLock.isUserUnlocked()) return Outcome.Failure(DomainError.BackgroundWorkFailure("user locked: $name"))
        return try {
            hand()
            Outcome.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.BackgroundWorkFailure("$name: ${e::class.simpleName}"))
        }
    }

    private fun hand(job: BackgroundJob) {
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(if (job.needsNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                .build()
        val input = workDataOf(BackgroundTaskWorker.KEY_TASK to job.task.name)
        val every = job.repeatEvery
        if (every == null) {
            val request =
                OneTimeWorkRequestBuilder<BackgroundTaskWorker>()
                    .setConstraints(constraints)
                    .setInputData(input)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .build()
            watch(workManager().enqueueUniqueWork(job.uniqueName, ExistingWorkPolicy.KEEP, request).result, job.uniqueName)
        } else {
            val request =
                PeriodicWorkRequestBuilder<BackgroundTaskWorker>(every.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    .setConstraints(constraints)
                    .setInputData(input)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .build()
            val operation = workManager().enqueueUniquePeriodicWork(job.uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request)
            watch(operation.result, job.uniqueName)
        }
    }

    /**
     * Logs the enqueue of [name] if WorkManager's [result] fails; never blocks (the listener runs where it completes).
     * Whatever the future fails with is only logged.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun watch(
        result: ListenableFuture<Operation.State.SUCCESS>,
        name: String,
    ) {
        result.addListener({
            try {
                result.get()
            } catch (e: ExecutionException) {
                logger.log(LogEvent.OperationFailed(ENQUEUE, "$name: ${e.cause?.let { it::class.simpleName }}"))
            } catch (e: Exception) {
                logger.log(LogEvent.OperationFailed(ENQUEUE, "$name: ${e::class.simpleName}"))
            }
        }, Runnable::run)
    }

    companion object {
        /** The first retry delay; WorkManager doubles it for each later attempt. */
        const val BACKOFF_SECONDS = 30L

        /** The tag on every job of the app, for diagnostics. */
        const val TAG = "yawnandpawn"

        /** The operation logged when WorkManager failed to store a job. */
        const val ENQUEUE = "enqueue background work"
    }
}

/** WorkManager's policy for [policy]. */
private fun existingOf(policy: SchedulePolicy): ExistingWorkPolicy =
    when (policy) {
        SchedulePolicy.Replace -> ExistingWorkPolicy.REPLACE
        SchedulePolicy.KeepPending -> ExistingWorkPolicy.KEEP
        SchedulePolicy.AfterRun -> ExistingWorkPolicy.APPEND_OR_REPLACE
    }
