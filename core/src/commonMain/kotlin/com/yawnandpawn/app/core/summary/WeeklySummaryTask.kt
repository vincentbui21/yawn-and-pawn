package com.yawnandpawn.app.core.summary

import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.stats.IsoWeek
import com.yawnandpawn.app.core.stats.WeeklySummaryDecision
import com.yawnandpawn.app.core.stats.weeklySummary
import com.yawnandpawn.app.core.stats.weeklySummaryDecision
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.core.work.BackgroundTask
import com.yawnandpawn.app.core.work.TaskResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The weekly summary run (Story 6.8, FR-PRG-5), behind [com.yawnandpawn.app.core.work.BackgroundTaskKind.WeeklySummary]:
 * 1. Reads the switch and the last handled week. Off: nothing at all (turning it off already cancelled the work). A
 *    store that cannot be read posts nothing (the week may be handled already) and only chains next week.
 * 2. Decides in the current zone ([weeklySummaryDecision]): a due week is summarised from one read of the history and the
 *    purchase records, stored as handled, then posted when it has a counted session and the notifier may post (it
 *    never asks for the permission); a week more than 24 h late is only stored as handled; a handled week does nothing.
 * 3. Last, in every case but off: [WeeklySummaryScheduler.afterRun] chains next week's run after this one.
 *
 * It always ends [TaskResult.Done], so the chained run is never failed with it (a failed prerequisite fails its
 * dependents); a read that failed is logged and loses that week's summary only. The week is stored before the post, so
 * a run cut short between the two never posts twice, and the two run to the end even if the work is cancelled meanwhile
 * (a sync's REPLACE). It never throws: the ports return failures.
 */
class WeeklySummaryTask(
    private val history: SessionHistoryRepository,
    private val records: PurchaseRecordRepository,
    private val store: WeeklySummaryStore,
    private val notifier: WeeklySummaryNotifier,
    private val scheduler: WeeklySummaryScheduler,
    private val clock: Clock,
    private val zones: TimeZoneProvider,
    private val logger: Logger,
) : BackgroundTask {
    override suspend fun run(): TaskResult {
        val prefs =
            when (val read = store.read()) {
                is Outcome.Success -> read.value
                is Outcome.Failure -> null.also { log(read.error) }
            }
        if (prefs?.enabled == false) return TaskResult.Done
        if (prefs != null) {
            val zone = zones.current()
            when (val decision = weeklySummaryDecision(clock.now(), zone, prefs.lastHandledWeek)) {
                is WeeklySummaryDecision.Summarise -> summarise(decision.week, decision.sunday, zone)
                is WeeklySummaryDecision.SkipLate -> handled(decision.week)
                WeeklySummaryDecision.AlreadyHandled -> Unit
            }
        }
        scheduler.afterRun()
        return TaskResult.Done
    }

    private suspend fun summarise(
        week: IsoWeek,
        sunday: LocalDate,
        zone: TimeZone,
    ) {
        val rows =
            when (val read = history.all()) {
                is Outcome.Success -> read.value
                is Outcome.Failure -> return log(read.error)
            }
        val paid =
            when (val read = records.all()) {
                is Outcome.Success -> read.value
                is Outcome.Failure -> return log(read.error)
            }
        val summary = weeklySummary(rows, paid, sunday, zone)
        // Not cancellable once the week is being stored: a sync that replaces this run then (a time change at that very
        // moment) must not leave the week stored as handled but never posted.
        withContext(NonCancellable) {
            handled(week)
            // The switch may have been turned off while the week was summarised: then nothing is posted.
            if (summary.shouldSend && stillOn() && notifier.canPost()) {
                val posted = notifier.post(summary)
                if (posted is Outcome.Failure) log(posted.error)
            }
        }
    }

    /** The switch is still on; a store that cannot be read now does not stop a post the first read allowed. */
    private suspend fun stillOn(): Boolean = (store.read() as? Outcome.Success)?.value?.enabled != false

    private suspend fun handled(week: IsoWeek) {
        val stored = store.setLastHandledWeek(week)
        if (stored is Outcome.Failure) log(stored.error)
    }

    private fun log(error: DomainError) = logger.log(LogEvent.OperationFailed.of(RUN_WEEKLY_SUMMARY, error))

    companion object {
        /** The operation logged when a step of the run failed. */
        const val RUN_WEEKLY_SUMMARY = "run weekly summary"
    }
}
