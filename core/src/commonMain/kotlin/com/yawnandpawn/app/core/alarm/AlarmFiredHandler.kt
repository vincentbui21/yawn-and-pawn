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
 * else. Epic 1's default is [RearmOnFire]; Story 1.14 binds the wake runtime, and Stories 1.12 and 1.18 bind the
 * session slot and the test alarm.
 */
interface AlarmFiredHandler {
    suspend fun onAlarmFired(fired: AlarmFired)

    suspend fun onSessionSlotFired()

    suspend fun onTestAlarmFired()
}

/**
 * The Epic 1 default [AlarmFiredHandler]: keeps the schedule right after a fire, and rings nothing yet.
 * - A repeating alarm is armed at its next occurrence after max(now, scheduledAt).
 * - A one-time alarm is switched off through [SetAlarmEnabled], which cancels its code.
 * - A missing or disabled alarm is logged and ignored, and so are session-slot and test fires.
 */
class RearmOnFire(
    private val repository: AlarmRepository,
    private val scheduling: AlarmScheduling,
    private val setAlarmEnabled: SetAlarmEnabled,
    private val clock: Clock,
    private val lock: AlarmWriteLock,
    private val logger: Logger,
) : AlarmFiredHandler {
    override suspend fun onAlarmFired(fired: AlarmFired) {
        val disableOneTime =
            lock.withLock {
                when (val stored = repository.get(fired.alarmId)) {
                    is Outcome.Failure -> {
                        ignore(fired.alarmId, stored.error.diagnostic())
                        false
                    }

                    is Outcome.Success -> {
                        val alarm = stored.value
                        when {
                            !alarm.enabled -> {
                                ignore(fired.alarmId, "alarm disabled")
                                false
                            }

                            alarm.toRule().isOneTime -> {
                                true
                            }

                            else -> {
                                scheduling.syncAfter(alarm, maxOf(clock.now(), fired.scheduledAt))
                                false
                            }
                        }
                    }
                }
            }
        // Outside the lock: SetAlarmEnabled takes it itself.
        if (disableOneTime) {
            val disabled = setAlarmEnabled(fired.alarmId, enabled = false)
            if (disabled is Outcome.Failure) logger.log(LogEvent.OperationFailed.of("disable fired one-time alarm", disabled.error))
        }
    }

    override suspend fun onSessionSlotFired() {
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
