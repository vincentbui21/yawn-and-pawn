package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.FireKind
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.log.diagnostic
import com.yawnandpawn.app.core.time.Clock
import kotlin.time.Instant

/** The system alarm of the stored alarm [alarmId] fired; it was armed for [scheduledAt]. */
data class AlarmFired(
    val alarmId: String,
    val scheduledAt: Instant,
)

/**
 * Port for what happens when a system alarm fires (AD-4). The alarm receiver hands every fire here and does nothing
 * else. [RearmOnFire] keeps the schedule right; since Story 1.14 the app binds a wrapper that runs it and then starts
 * the wake service for an enabled alarm and for the session slot. Story 1.18 binds the test alarm.
 */
interface AlarmFiredHandler {
    suspend fun onAlarmFired(fired: AlarmFired)

    /**
     * The session slot fired; [alarm] is the alarm it also stands for ([AlarmScheduler.armSessionSlot]), if any, and
     * [retrySince] when the wake-service starts it retries were first refused, if they were.
     */
    suspend fun onSessionSlotFired(
        alarm: AlarmFired?,
        retrySince: Instant? = null,
    )

    suspend fun onTestAlarmFired()
}

/**
 * The scheduling part of every fire: keeps the schedule right after a fire and rings nothing itself (the app's wake
 * handler wraps it and starts the ringing).
 * - A repeating alarm is armed at its next occurrence after max(now, scheduledAt).
 * - A one-time alarm is switched off (`updatedAt = now`) and its code cancelled, and that is logged.
 * - A missing or disabled alarm is logged and ignored, and so are session-slot and test fires.
 *
 * The fire usually starts a wake session first, so this runs while the app is session-locked: it writes the alarm
 * itself under [lock] instead of through the user's use cases, which `SessionLockGuard` refuses during a session
 * (Story 2.6).
 */
class RearmOnFire(
    private val repository: AlarmRepository,
    private val scheduling: AlarmScheduling,
    private val clock: Clock,
    private val lock: AlarmWriteLock,
    private val logger: Logger,
) : AlarmFiredHandler {
    override suspend fun onAlarmFired(fired: AlarmFired) {
        lock.withLock {
            when (val stored = repository.get(fired.alarmId)) {
                is Outcome.Failure -> {
                    ignore(fired.alarmId, stored.error.diagnostic())
                }

                is Outcome.Success -> {
                    val alarm = stored.value
                    when {
                        !alarm.enabled -> ignore(fired.alarmId, "alarm disabled")
                        alarm.toRule().isOneTime -> switchOff(alarm, fired)
                        else -> scheduling.syncAfter(alarm, maxOf(clock.now(), fired.scheduledAt))
                    }
                }
            }
        }
    }

    /** Stores the fired one-time [alarm] off and cancels its code ([AlarmScheduling.sync]); called under [lock]. */
    private suspend fun switchOff(
        alarm: Alarm,
        fired: AlarmFired,
    ) {
        val off = alarm.copy(enabled = false, updatedAt = clock.nowMillis())
        when (val stored = repository.upsert(off)) {
            is Outcome.Success -> {
                scheduling.sync(off)
                logger.log(LogEvent.OneTimeAlarmDisabled(fired.alarmId, fired.scheduledAt))
            }

            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of("disable fired one-time alarm", stored.error))
            }
        }
    }

    override suspend fun onSessionSlotFired(
        alarm: AlarmFired?,
        retrySince: Instant?,
    ) {
        logger.log(LogEvent.FireIgnored(FireKind.SessionSlot, alarmId = null, reason = NOT_BOUND))
    }

    override suspend fun onTestAlarmFired() {
        logger.log(LogEvent.FireIgnored(FireKind.TestAlarm, alarmId = null, reason = NOT_BOUND))
    }

    private fun ignore(
        alarmId: String,
        reason: String,
    ) = logger.log(LogEvent.FireIgnored(FireKind.Alarm, alarmId, reason))

    private companion object {
        const val NOT_BOUND = "no session runtime yet"
    }
}
