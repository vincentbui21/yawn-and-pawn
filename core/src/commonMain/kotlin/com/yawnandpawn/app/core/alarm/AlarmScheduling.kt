package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.config.PendingChangePromotion
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.datetime.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * Keeps the system alarms in step with the stored alarms (AD-4). An enabled alarm is armed at its [nextOccurrence]
 * (the same function the Home countdown uses), read from [Clock] and [TimeZoneProvider] at the time of the call; a
 * disabled alarm's request code is cancelled.
 *
 * A scheduler failure (for example `ExactAlarmNotPermitted`) is logged and never fails the caller. For a stored alarm
 * it heals: the alarm stays stored, the next [rescheduleAll] tries again, and Story 1.19 surfaces the missing
 * permission. A failed cancel after a delete does not heal that way, because the row is gone.
 *
 * [sync] and [cancel] must run while the caller holds [lock] (the alarm use cases call them inside it, right after
 * their repository write succeeded); [rescheduleAll] takes the lock itself.
 */
class AlarmScheduling(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val lock: AlarmWriteLock,
    private val logger: Logger,
    /** Promotes the due pending changes first in [rescheduleAll] (Story 4.4); none in tests and the backup fallback. */
    private val promotion: PendingChangePromotion? = null,
) {
    /** Arms [alarm] at its next occurrence after now when it is enabled, cancels its code when it is not. */
    fun sync(alarm: Alarm) {
        syncAfter(alarm, clock.now())
    }

    /** Cancels the system alarm under [requestCode] (an alarm that was just deleted). */
    fun cancel(requestCode: Int) {
        scheduler.cancel(requestCode).logFailure(OPERATION_CANCEL)
    }

    /**
     * Recomputes every stored alarm (app start, boot, time set, zone change, package replaced, exact-alarm permission
     * changed): each enabled alarm is armed at its next occurrence, each disabled alarm's code is cancelled. It is
     * idempotent: two runs with the same alarms, clock and zone make the same scheduler calls. A failed read of the
     * alarms is logged and returned; nothing is armed or cancelled then. The due pending changes are promoted first
     * ([promotion], Story 4.4; it takes the lock itself and logs its own failures, so the alarms are armed regardless).
     *
     * An enabled one-time alarm whose intended occurrence (the first one after it was last saved or switched on,
     * `updatedAt`) is already past (the phone was off, or the clock jumped over it) is not moved to the next day:
     * it is stored disabled, its code is cancelled and [LogEvent.OneTimeAlarmPassed] is logged. Powering off is an
     * accepted escape (FR-ONB-5).
     */
    suspend fun rescheduleAll(): Outcome<Unit, DomainError> {
        promotePending()
        return lock.withLock {
            val alarms = repository.listAll()
            if (alarms is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(OPERATION_RESCHEDULE, alarms.error))
            alarms.map { all ->
                val now = clock.now()
                val zone = timeZoneProvider.current()
                var scheduled = 0
                var disabled = 0
                var failed = 0
                all.forEach { stored ->
                    val alarm = if (stored.isPassedOneTime(now, zone)) disablePassed(stored, now, zone) else stored
                    when {
                        !syncAfter(alarm, now) -> failed++
                        alarm.enabled -> scheduled++
                        else -> disabled++
                    }
                }
                logger.log(LogEvent.AlarmsRescheduled(scheduled = scheduled, disabled = disabled, failed = failed))
            }
        }
    }

    /** Runs [promotion]; nothing it does, not even an unexpected exception, may keep the alarms from being armed. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun promotePending() {
        try {
            promotion?.promote()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(OPERATION_PROMOTE, e::class.simpleName ?: "exception"))
        }
    }

    /**
     * Like [sync], but the next occurrence strictly after [after] instead of now (a repeating alarm that just fired
     * re-arms after its own scheduled time, so a fire a little early never re-arms the same occurrence).
     * True when the scheduler call succeeded.
     */
    internal fun syncAfter(
        alarm: Alarm,
        after: Instant,
    ): Boolean =
        if (alarm.enabled) {
            val trigger = nextOccurrence(alarm.toRule(), after, timeZoneProvider.current())
            scheduler.schedule(alarm.id, alarm.requestCode, trigger.toEpochMilliseconds()).logFailure(OPERATION_SCHEDULE)
        } else {
            scheduler.cancel(alarm.requestCode).logFailure(OPERATION_CANCEL)
        }

    private fun Alarm.isPassedOneTime(
        now: Instant,
        zone: TimeZone,
    ): Boolean = enabled && toRule().isOneTime && intendedOccurrence(zone) <= now

    /** The occurrence this alarm was armed for when it was last saved or switched on. */
    private fun Alarm.intendedOccurrence(zone: TimeZone): Instant = nextOccurrence(toRule(), updatedAt, zone)

    /** Stores [alarm] disabled (a failed write is logged) and returns the disabled alarm, so its code is cancelled. */
    private suspend fun disablePassed(
        alarm: Alarm,
        now: Instant,
        zone: TimeZone,
    ): Alarm {
        val off = alarm.copy(enabled = false, updatedAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds()))
        when (val stored = repository.upsert(off)) {
            is Outcome.Success -> logger.log(LogEvent.OneTimeAlarmPassed(alarm.id, alarm.intendedOccurrence(zone)))
            is Outcome.Failure -> logger.log(LogEvent.OperationFailed.of(OPERATION_DISABLE_PASSED, stored.error))
        }
        return off
    }

    private fun Outcome<Unit, DomainError>.logFailure(operation: String): Boolean =
        when (this) {
            is Outcome.Success -> {
                true
            }

            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of(operation, error))
                false
            }
        }

    private companion object {
        const val OPERATION_SCHEDULE = "schedule alarm"
        const val OPERATION_CANCEL = "cancel alarm"
        const val OPERATION_RESCHEDULE = "reschedule alarms"
        const val OPERATION_DISABLE_PASSED = "disable passed one-time alarm"
        const val OPERATION_PROMOTE = "promote pending changes"
    }
}
