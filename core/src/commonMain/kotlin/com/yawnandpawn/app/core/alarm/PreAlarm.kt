package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.KeyedBackgroundTask
import com.yawnandpawn.app.core.work.TaskResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The pre-alarm notification's rule (Story 7.11, FR-ALM-10). */
object PreAlarm {
    /** How long before a ring "Skip this one" is offered. */
    val LEAD: Duration = 2.hours
}

/**
 * Port for the pre-alarm notification (Story 7.11): channel "Upcoming alarms", "{time} alarm" / "Rings in {h} h {min}
 * min" with the action "Skip this one", one per alarm (its id derived from the alarm's request code). Never requests the
 * notification permission; failures are values, never thrown.
 */
interface PreAlarmNotifier {
    /** `POST_NOTIFICATIONS` is granted and the app's notifications are enabled. */
    fun canPost(): Boolean

    /** Posts (or updates) [alarm]'s notification: it rings in [ringsIn]. */
    fun post(
        alarm: Alarm,
        ringsIn: Duration,
    ): Outcome<Unit, DomainError>

    /** Removes the notification of the alarm with [requestCode], if one is posted; none is not an error. */
    fun cancel(requestCode: Int): Outcome<Unit, DomainError>
}

/**
 * Keeps each alarm's pre-alarm job (Story 7.11, AD-17: [BackgroundWork], never `AlarmScheduler`) at 2 h before its next
 * ring ([nextRing]): enqueued only while the alarm is enabled with "Offer to skip" on (its effective value: a pending
 * change waits in `pending_change`, the row holds the live one), with delay = next ring − 2 h − now, at least 0, REPLACE.
 * Otherwise the job and any posted notification are cancelled. A sync for a ring whose 2 h window has not started also
 * removes a notification still showing (it was about an earlier ring, for example the one just skipped).
 *
 * [AlarmScheduling] calls it after each system alarm change (save, switch, delete, `rescheduleAll`, the re-arm after a
 * ring), so it follows the alarms; the skip use cases go through it the same way. WorkManager keeps its database in
 * credential-protected storage, so before the first unlock nothing is handed over (the boot after the unlock re-syncs
 * through `rescheduleAll`); a notification is still removed then. Failures are logged and never reach the caller.
 */
class PreAlarmScheduler(
    private val work: BackgroundWork,
    private val notifier: PreAlarmNotifier,
    private val clock: Clock,
    private val zones: TimeZoneProvider,
    private val userLock: UserLockState,
    private val logger: Logger,
) {
    /** Aligns [alarm]'s job with its next ring strictly after [after] (now by default; a re-arm passes the fired time). */
    fun sync(
        alarm: Alarm,
        after: Instant = clock.now(),
    ) {
        if (!alarm.enabled || !alarm.offerSkip) {
            cancel(alarm.id, alarm.requestCode)
            return
        }
        val now = clock.now()
        val delay = (nextRing(alarm, maxOf(after, now), zones.current()) - PreAlarm.LEAD - now).coerceAtLeast(Duration.ZERO)
        if (delay > Duration.ZERO) notifier.cancel(alarm.requestCode).logged(CANCEL_NOTIFICATION)
        if (userLock.isUserUnlocked()) work.schedulePreAlarm(alarm.id, delay).logged(SCHEDULE)
    }

    /** Cancels the job of [alarmId] and removes its notification (the alarm is off, deleted, or its switch is off). */
    fun cancel(
        alarmId: String,
        requestCode: Int,
    ) {
        notifier.cancel(requestCode).logged(CANCEL_NOTIFICATION)
        if (userLock.isUserUnlocked()) work.cancelPreAlarm(alarmId).logged(CANCEL)
    }

    private fun Outcome<Unit, DomainError>.logged(operation: String) {
        if (this is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(operation, error))
    }

    private companion object {
        const val SCHEDULE = "schedule pre-alarm"
        const val CANCEL = "cancel pre-alarm"
        const val CANCEL_NOTIFICATION = "remove pre-alarm notification"
    }
}

/**
 * The pre-alarm job's run (Story 7.11), keyed by the alarm id: reads the alarm and posts its notification only when it is
 * enabled with "Offer to skip" on, notifications may be posted, and its next ring ([nextRing]) is at most [PreAlarm.LEAD]
 * away. A run at or after the occurrence it was meant for (late) finds the next ring a day away and posts nothing; so does
 * a deleted alarm. It never reschedules itself (REPLACE from inside the run would cancel it): the re-arm after the ring
 * does. Always [TaskResult.Done]; a read failure is logged.
 */
class PreAlarmTask(
    private val repository: AlarmRepository,
    private val notifier: PreAlarmNotifier,
    private val clock: Clock,
    private val zones: TimeZoneProvider,
    private val logger: Logger,
) : KeyedBackgroundTask {
    override suspend fun run(key: String): TaskResult {
        when (val read = repository.get(key)) {
            is Outcome.Failure -> {
                if (read.error !is DomainError.NotFound) logger.log(LogEvent.OperationFailed.of(POST, read.error))
            }

            is Outcome.Success -> {
                post(read.value)
            }
        }
        return TaskResult.Done
    }

    private fun post(alarm: Alarm) {
        if (!alarm.enabled || !alarm.offerSkip || !notifier.canPost()) return
        val now = clock.now()
        val ringsIn = nextRing(alarm, now, zones.current()) - now
        if (ringsIn > PreAlarm.LEAD) return
        val posted = notifier.post(alarm, ringsIn)
        if (posted is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(POST, posted.error))
    }

    private companion object {
        const val POST = "post pre-alarm"
    }
}
