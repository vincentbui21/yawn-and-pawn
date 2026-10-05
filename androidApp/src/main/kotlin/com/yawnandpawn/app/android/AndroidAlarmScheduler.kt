package com.yawnandpawn.app.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.core.alarm.AlarmFired
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
import kotlin.time.Instant

/**
 * [AlarmScheduler] on `AlarmManager.setAlarmClock()` only (AD-4): exact, allowed in Doze, shown as the next alarm in
 * the status bar and on the lock screen. There is never an inexact fallback (detekt `NoInexactAlarm`).
 *
 * - The operation is an immutable broadcast with an explicit component and the request code: to [AlarmFiredReceiver]
 *   for a stored alarm ([AlarmFiredReceiver.ACTION_ALARM]) and the test alarm ([AlarmFiredReceiver.ACTION_TEST_ALARM])
 *   with the extras [AlarmFiredReceiver.EXTRA_ALARM_ID] and [AlarmFiredReceiver.EXTRA_SCHEDULED_AT], and to
 *   [SessionSlotReceiver] for the session slot (Story 2.1), carrying the alarm it stands for, if any, in the same extras.
 * - The show intent (tapping the alarm icon in the system UI) is an immutable activity intent opening [MainActivity].
 * - Scheduling again under a request code replaces the earlier alarm: the PendingIntents are equal.
 * - On API 31-32 without the exact-alarm permission (and whenever `setAlarmClock` throws `SecurityException`) it returns
 *   `ExactAlarmNotPermitted` and logs it. On API 33+ `USE_EXACT_ALARM` is granted at install.
 */
@Suppress("TooManyFunctions") // One per port call, plus the wall-time conversion and the arm and cancel helpers.
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
    ): Outcome<Unit, DomainError> {
        val intent = firedIntent(context, AlarmFiredReceiver.ACTION_ALARM, triggerAtWallMillis, alarmId)
        return arm(intent, requestCode, triggerAtWallMillis)
    }

    override fun cancel(requestCode: Int): Outcome<Unit, DomainError> = cancel(operationFor(context, requestCode), requestCode)

    /** The alarm the slot this process armed last carries, and the slot's wall trigger time (Story 2.1 review). */
    @Volatile
    private var carried: Pair<AlarmFired, Long>? = null

    override fun armSessionSlot(
        deadline: Deadline,
        alarm: AlarmFired?,
        retrySince: Instant?,
    ): Outcome<Unit, DomainError> {
        val triggerAt = wallMillisOf(deadline)
        val armed = arm(SessionSlotReceiver.intent(context, alarm, retrySince), RequestCodes.SESSION_SLOT, triggerAt)
        if (armed is Outcome.Success) carried = alarm?.let { it to triggerAt }
        return armed
    }

    /**
     * Known only for a slot this process armed (the extras of a PendingIntent cannot be read back), and only until its
     * trigger time: after that it has fired and its alarm went to the slot's fire.
     */
    override fun sessionSlotAlarm(): AlarmFired? = carried?.takeIf { (_, at) -> clock.now().toEpochMilliseconds() < at }?.first

    override fun cancelSessionSlot(): Outcome<Unit, DomainError> {
        carried = null
        return cancel(SessionSlotReceiver.intent(context), RequestCodes.SESSION_SLOT)
    }

    override fun scheduleTest(triggerAtWallMillis: Long): Outcome<Unit, DomainError> {
        val intent = firedIntent(context, AlarmFiredReceiver.ACTION_TEST_ALARM, triggerAtWallMillis, alarmId = null)
        return arm(intent, RequestCodes.TEST_ALARM, triggerAtWallMillis)
    }

    /**
     * The only place a [Deadline] becomes wall time (AD-3): within the boot it was made in, now plus the monotonic
     * time left (a wall-clock change since then does not move it); after a reboot, its wall time.
     */
    private fun wallMillisOf(deadline: Deadline): Long {
        val now = TimeSnapshot.of(clock, monotonicClock, bootCounter)
        return if (deadline.sameBoot(now)) {
            now.wallMillis + deadline.remaining(now).inWholeMilliseconds
        } else {
            deadline.wallMillis
        }
    }

    // setAlarmClock throws platform exceptions (permission, per-app alarm limit); map them all at this boundary (AD-12).
    @Suppress("TooGenericExceptionCaught")
    private fun arm(
        intent: Intent,
        requestCode: Int,
        triggerAtWallMillis: Long,
    ): Outcome<Unit, DomainError> {
        if (exactAlarmsDenied()) return denied(requestCode, "canScheduleExactAlarms() is false")
        // FLAG_UPDATE_CURRENT: arming again under the code replaces the alarm and the extras it carries.
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
        intent: Intent,
        requestCode: Int,
    ): Outcome<Unit, DomainError> {
        // Equal to the armed one (same component, action and code; extras do not count), so this finds it.
        val operation =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
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

    companion object {
        private const val IMMUTABLE_UPDATE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        /**
         * Cancels a session slot armed by a version before Story 2.1 (to [AlarmFiredReceiver] with the old slot action),
         * which [cancelSessionSlot] no longer matches. The app update broadcast calls it (Story 2.1 review).
         */
        fun cancelLegacySessionSlot(context: Context) {
            val legacy = AlarmFiredReceiver.intent(context, AlarmFiredReceiver.ACTION_LEGACY_SESSION_SLOT)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            val operation = PendingIntent.getBroadcast(context, RequestCodes.SESSION_SLOT, legacy, flags) ?: return
            context.getSystemService(AlarmManager::class.java).cancel(operation)
            operation.cancel()
        }

        /** The [AlarmFiredReceiver] operation for a stored alarm or the test alarm, with its extras. */
        private fun firedIntent(
            context: Context,
            action: String,
            triggerAtWallMillis: Long,
            alarmId: String?,
        ): Intent =
            AlarmFiredReceiver
                .intent(context, action)
                .putExtra(AlarmFiredReceiver.EXTRA_ALARM_ID, alarmId)
                .putExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, triggerAtWallMillis)

        /** The operation armed under [requestCode], without extras (they do not count when PendingIntents are compared). */
        private fun operationFor(
            context: Context,
            requestCode: Int,
        ): Intent =
            when (requestCode) {
                RequestCodes.SESSION_SLOT -> SessionSlotReceiver.intent(context)
                RequestCodes.TEST_ALARM -> AlarmFiredReceiver.intent(context, AlarmFiredReceiver.ACTION_TEST_ALARM)
                else -> AlarmFiredReceiver.intent(context, AlarmFiredReceiver.ACTION_ALARM)
            }
    }
}
