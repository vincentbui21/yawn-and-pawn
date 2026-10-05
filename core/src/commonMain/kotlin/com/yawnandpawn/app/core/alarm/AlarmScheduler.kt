package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.time.Deadline

/**
 * Port for system alarms (AD-4): the only way the app schedules one. The Android adapter uses
 * `AlarmManager.setAlarmClock()` only and never falls back to an inexact alarm; `FakeAlarmScheduler` in tests.
 *
 * Scheduling again under the same request code replaces the earlier alarm. The arming calls ([schedule],
 * [armSessionSlot], [scheduleTest]) return `ExactAlarmNotPermitted` when the system does not allow exact alarms
 * (API 31-32 with the permission off), and `SchedulerFailure` when it refuses for another reason. [cancel] and
 * [cancelSessionSlot] do not need the exact-alarm permission.
 */
interface AlarmScheduler {
    /** Arms the alarm [alarmId] under its [requestCode] to fire at [triggerAtWallMillis] (epoch milliseconds). */
    fun schedule(
        alarmId: String,
        requestCode: Int,
        triggerAtWallMillis: Long,
    ): Outcome<Unit, DomainError>

    /** Cancels whatever is armed under [requestCode]; nothing armed is not an error. */
    fun cancel(requestCode: Int): Outcome<Unit, DomainError>

    /**
     * Arms the one session slot ([RequestCodes.SESSION_SLOT]) at [deadline], converted to wall time by the adapter (AD-3),
     * replacing any earlier arming and what it carried. [alarm] is the stored alarm the slot also stands for while no
     * committed session holds it (a refused wake-service start, or the emergency ring, Story 2.1): the fire hands it to
     * [AlarmFiredHandler.onSessionSlotFired], so a new process still rings it.
     */
    fun armSessionSlot(
        deadline: Deadline,
        alarm: AlarmFired? = null,
    ): Outcome<Unit, DomainError>

    /** Cancels the session slot. */
    fun cancelSessionSlot(): Outcome<Unit, DomainError>

    /** Arms the test alarm ([RequestCodes.TEST_ALARM]) at [triggerAtWallMillis] (Story 1.18). */
    fun scheduleTest(triggerAtWallMillis: Long): Outcome<Unit, DomainError>
}
