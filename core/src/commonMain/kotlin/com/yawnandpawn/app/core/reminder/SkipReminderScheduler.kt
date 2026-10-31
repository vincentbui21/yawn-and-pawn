package com.yawnandpawn.app.core.reminder

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.core.work.BackgroundTask
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.SchedulePolicy
import com.yawnandpawn.app.core.work.TaskResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Keeps the Skip reminder (0.1.8, FR-ALM-10) in step with the alarms and the switch: one unique job
 * ([BackgroundWork.scheduleSkipReminder]; AD-17, never an `AlarmScheduler` alarm) at [SkipReminder.LEAD] before the first
 * ring of the next date to remind ([skipReminderPlan]).
 *
 * [sync] (every alarm change through `AlarmScheduling`: save, switch, delete, skip and its undo, the re-arm after a ring,
 * `rescheduleAll` at app start, boot and time or zone changes; the switch; and the job's own run, [SkipReminderTask]):
 * - Off: cancels the job and removes a posted reminder.
 * - Before the first unlock: nothing (no job can be handed over, and the action receiver is not direct-boot aware); the
 *   sync after the unlock does it.
 * - Dates with [SkipReminder.MIN_LEFT] or less left are stored as handled (monotonic, [SkipReminderPrefs.lastRemindedDate]).
 * - The next date's reminder time is still to come: schedules the job for it. It has come (the job's run, a new alarm
 *   under 2 h away, a late job): the date is stored as handled first, then the reminder is posted, unless a wake session
 *   is in progress ([sessionActive]) or notifications may not be posted; then the next date is looked at. Every date
 *   handled moves the stored date on, so a sync always ends by scheduling or cancelling the job.
 * - From the job's run ([sync] with `fromRun`) the next job is chained after the run ([SchedulePolicy.AfterRun]), so the
 *   run never cancels itself, and nothing is cancelled; every other sync replaces ([SchedulePolicy.Replace]).
 *
 * A posted reminder is left as it is by an alarm change (it times out at its ring, and the fire removes it). Calls are
 * serialized; a store or alarm read or write that fails is logged and returned, and then nothing is posted; a hand-over
 * or post that fails is logged.
 */
class SkipReminderScheduler(
    private val alarms: AlarmRepository,
    private val store: SkipReminderStore,
    private val notifier: SkipReminderNotifier,
    private val work: BackgroundWork,
    private val clock: Clock,
    private val zones: TimeZoneProvider,
    private val userLock: UserLockState,
    private val logger: Logger,
    /** A wake session is in progress (ringing, snoozed, or the emergency ring): no reminder is posted then. */
    private val sessionActive: suspend () -> Boolean,
) {
    private val lock = Mutex()

    /** Aligns the job (and, inside a reminder window, the post) with the alarms and the switch now. */
    suspend fun sync(fromRun: Boolean = false): Outcome<Unit, DomainError> =
        lock.withLock {
            val prefs =
                when (val read = store.read()) {
                    is Outcome.Success -> read.value
                    is Outcome.Failure -> return@withLock logged(SYNC, read)
                }
            if (!prefs.enabled) {
                notifier.cancel().also { logged(REMOVE, it) }
                return@withLock cancelJob(fromRun)
            }
            if (!userLock.isUserUnlocked()) return@withLock Outcome.Success(Unit)
            val all =
                when (val read = alarms.listAll()) {
                    is Outcome.Success -> read.value
                    is Outcome.Failure -> return@withLock logged(SYNC, read)
                }
            var through = prefs.lastRemindedDate
            // Each round handles a later date (at most one per day ahead), so the rounds always end in a schedule or cancel.
            repeat(SkipReminder.DAYS_AHEAD + 2) {
                val now = clock.now()
                val zone = zones.current()
                val plan = skipReminderPlan(all, now, zone, through)
                val target = plan.target ?: return@withLock handled(plan.handledThrough, through) { cancelJob(fromRun) }
                val delay = target.firstRing - SkipReminder.LEAD - now
                if (delay > Duration.ZERO) return@withLock handled(plan.handledThrough, through) { schedule(delay, fromRun) }
                when (val reminded = remind(target, now, zone)) {
                    is Outcome.Success -> through = target.date
                    is Outcome.Failure -> return@withLock reminded
                }
            }
            cancelJob(fromRun)
        }

    /** A skip was undone ("Don't skip"): its "Today's alarms are skipped." confirmation goes; a reminder stays. */
    fun skipUndone() {
        logged(REMOVE, notifier.cancelSkipped())
    }

    /**
     * Update cleanup (0.1.8): removes Story 7.11's pre-alarm notifications of [stored] (their "Skip this one" does nothing
     * any more) and, once the user is unlocked, cancels their per-alarm jobs (`pre-alarm-{id}`). Failures are logged.
     */
    fun cancelLegacyPreAlarms(stored: List<Alarm>) {
        stored.forEach { logged(REMOVE, notifier.cancelLegacy(it.requestCode)) }
        if (!userLock.isUserUnlocked()) return
        stored.forEach { logged(CANCEL_LEGACY, work.cancelPreAlarm(it.id)) }
    }

    /** Stores [handledThrough] when it moves past [through], then runs [next] (a failed write is logged and returned). */
    private suspend fun handled(
        handledThrough: LocalDate?,
        through: LocalDate?,
        next: () -> Outcome<Unit, DomainError>,
    ): Outcome<Unit, DomainError> {
        if (handledThrough != null && (through == null || handledThrough > through)) {
            val stored = store.setLastRemindedDate(handledThrough)
            if (stored is Outcome.Failure) return logged(SYNC, stored)
        }
        return next()
    }

    /**
     * Stores [target]'s date as handled, then posts it when allowed ("tomorrow's" when it is not [now]'s local date in
     * [zone]); not cancellable, so a date is never posted twice.
     */
    private suspend fun remind(
        target: SkipReminderTarget,
        now: Instant,
        zone: TimeZone,
    ): Outcome<Unit, DomainError> =
        withContext(NonCancellable) {
            val stored = store.setLastRemindedDate(target.date)
            if (stored is Outcome.Failure) return@withContext logged(POST, stored)
            if (!sessionActive() && notifier.canPost()) {
                val tomorrow = target.date != now.toLocalDateTime(zone).date
                logged(POST, notifier.post(target, target.firstRing - clock.now(), tomorrow))
            }
            Outcome.Success(Unit)
        }

    private fun schedule(
        delay: Duration,
        fromRun: Boolean,
    ): Outcome<Unit, DomainError> =
        logged(SCHEDULE, work.scheduleSkipReminder(delay, if (fromRun) SchedulePolicy.AfterRun else SchedulePolicy.Replace))

    /** Cancels the job; from the job's own run there is nothing to cancel (the run is the only one, and it is ending). */
    private fun cancelJob(fromRun: Boolean): Outcome<Unit, DomainError> =
        if (fromRun || !userLock.isUserUnlocked()) Outcome.Success(Unit) else logged(CANCEL, work.cancelSkipReminder())

    private fun <T> logged(
        operation: String,
        result: Outcome<T, DomainError>,
    ): Outcome<Unit, DomainError> =
        when (result) {
            is Outcome.Success -> {
                Outcome.Success(Unit)
            }

            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of(operation, result.error))
                result
            }
        }

    companion object {
        /** The operation logged when the reminder could not be aligned. */
        const val SYNC = "sync skip reminder"
        private const val SCHEDULE = "schedule skip reminder"
        private const val CANCEL = "cancel skip reminder"
        private const val CANCEL_LEGACY = "cancel pre-alarm"
        private const val POST = "post skip reminder"
        private const val REMOVE = "remove skip reminder"
    }
}

/**
 * The Skip reminder job's run (0.1.8), behind [com.yawnandpawn.app.core.work.BackgroundTaskKind.SkipReminder]: it
 * recomputes everything at run time through [SkipReminderScheduler.sync] (the alarms, the switch, the date already
 * reminded, a session in progress, the time left), which posts when the reminder time has come and schedules the next
 * date (chained after this run). A run that comes early only schedules again. Always [TaskResult.Done], so the chained
 * job is never failed with it; failures are logged by the scheduler.
 */
class SkipReminderTask(
    private val scheduler: SkipReminderScheduler,
) : BackgroundTask {
    override suspend fun run(): TaskResult {
        scheduler.sync(fromRun = true)
        return TaskResult.Done
    }
}
