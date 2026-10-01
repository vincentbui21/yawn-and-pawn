package com.yawnandpawn.app.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.BootCounter
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeSnapshot

/**
 * [AlarmScheduler] on `AlarmManager.setAlarmClock()` only (AD-4): exact, allowed in Doze, shown as the next alarm in
 * the status bar and on the lock screen. There is never an inexact fallback (detekt `NoInexactAlarm`).
 *
 * - The operation is an immutable broadcast to [AlarmFiredReceiver] with an explicit component, one action per kind
 *   ([AlarmFiredReceiver.ACTION_ALARM], [AlarmFiredReceiver.ACTION_SESSION_SLOT], [AlarmFiredReceiver.ACTION_TEST_ALARM]),
 *   the extras [AlarmFiredReceiver.EXTRA_ALARM_ID] and [AlarmFiredReceiver.EXTRA_SCHEDULED_AT], and the request code.
 * - The show intent (tapping the alarm icon in the system UI) is an immutable activity intent opening [MainActivity].
 * - Scheduling again under a request code replaces the earlier alarm: the PendingIntents are equal.
 * - On API 31-32 without the exact-alarm permission (and whenever `setAlarmClock` throws `SecurityException`) it returns
 *   `ExactAlarmNotPermitted` and logs it. On API 33+ `USE_EXACT_ALARM` is granted at install.
 */
class AndroidAlarmScheduler(
    private val context: Context,
    private val clock: Clock,
    private val monotonicClock: MonotonicClock,
    private val bootCounter: BootCounter,
    private val logger: Logger,
) : AlarmScheduler {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(
        alarmId: String,
        requestCode: Int,
        triggerAtWallMillis: Long,
    ): Outcome<Unit, DomainError> = arm(AlarmFiredReceiver.ACTION_ALARM, requestCode, triggerAtWallMillis, alarmId)

    override fun cancel(requestCode: Int): Outcome<Unit, DomainError> = cancel(actionFor(requestCode), requestCode)

    override fun armSessionSlot(deadline: Deadline): Outcome<Unit, DomainError> =
        arm(AlarmFiredReceiver.ACTION_SESSION_SLOT, RequestCodes.SESSION_SLOT, wallMillisOf(deadline), alarmId = null)

    override fun cancelSessionSlot(): Outcome<Unit, DomainError> = cancel(AlarmFiredReceiver.ACTION_SESSION_SLOT, RequestCodes.SESSION_SLOT)

    override fun scheduleTest(triggerAtWallMillis: Long): Outcome<Unit, DomainError> =
        arm(AlarmFiredReceiver.ACTION_TEST_ALARM, RequestCodes.TEST_ALARM, triggerAtWallMillis, alarmId = null)

    /**
     * The only place a [Deadline] becomes wall time (AD-3): within the boot it was made in, now plus the monotonic
     * time left (a wall-clock change since then does not move it); after a reboot, its wall time.
     */
    private fun wallMillisOf(deadline: Deadline): Long {
        val now = TimeSnapshot.of(clock, monotonicClock, bootCounter)
        return if (now.bootCount == deadline.bootCount) {
            now.wallMillis + deadline.remaining(now).inWholeMilliseconds
        } else {
            deadline.wallMillis
        }
    }

    // setAlarmClock throws platform exceptions (permission, per-app alarm limit); map them all at this boundary (AD-12).
    @Suppress("TooGenericExceptionCaught")
    private fun arm(
        action: String,
        requestCode: Int,
        triggerAtWallMillis: Long,
        alarmId: String?,
    ): Outcome<Unit, DomainError> {
        if (exactAlarmsDenied()) return denied(requestCode, "canScheduleExactAlarms() is false")
        val intent =
            AlarmFiredReceiver
                .intent(context, action)
                .putExtra(AlarmFiredReceiver.EXTRA_ALARM_ID, alarmId)
                .putExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, triggerAtWallMillis)
        val operation = PendingIntent.getBroadcast(context, requestCode, intent, IMMUTABLE_UPDATE)
        val show = PendingIntent.getActivity(context, requestCode, Intent(context, MainActivity::class.java), IMMUTABLE_UPDATE)
        return try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtWallMillis, show), operation)
            Outcome.Success(Unit)
        } catch (e: SecurityException) {
            denied(requestCode, e.message ?: "SecurityException")
        } catch (e: RuntimeException) {
            // For example the per-app alarm limit (IllegalStateException): report it, so callers keep going.
            val cause = "${e::class.simpleName}: ${e.message}"
            logger.log(LogEvent.OperationFailed("set alarm clock", "refused (code $requestCode): $cause"))
            Outcome.Failure(DomainError.SchedulerFailure(cause))
        }
    }

    private fun cancel(
        action: String,
        requestCode: Int,
    ): Outcome<Unit, DomainError> {
        // Equal to the armed one (same component, action and code; extras do not count), so this finds it.
        val operation =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                AlarmFiredReceiver.intent(context, action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            ) ?: return Outcome.Success(Unit)
        alarmManager.cancel(operation)
        operation.cancel()
        return Outcome.Success(Unit)
    }

    private fun exactAlarmsDenied(): Boolean =
        Build.VERSION.SDK_INT in Build.VERSION_CODES.S..Build.VERSION_CODES.S_V2 && !alarmManager.canScheduleExactAlarms()

    private fun denied(
        requestCode: Int,
        cause: String,
    ): Outcome<Unit, DomainError> {
        logger.log(
            LogEvent.OperationFailed(
                "set alarm clock",
                "exact alarms not permitted on API ${Build.VERSION.SDK_INT} (code $requestCode): $cause",
            ),
        )
        return Outcome.Failure(DomainError.ExactAlarmNotPermitted)
    }

    private companion object {
        const val IMMUTABLE_UPDATE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        fun actionFor(requestCode: Int): String =
            when (requestCode) {
                RequestCodes.SESSION_SLOT -> AlarmFiredReceiver.ACTION_SESSION_SLOT
                RequestCodes.TEST_ALARM -> AlarmFiredReceiver.ACTION_TEST_ALARM
                else -> AlarmFiredReceiver.ACTION_ALARM
            }
    }
}
