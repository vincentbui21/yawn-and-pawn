package com.yawnandpawn.app.android.prealarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationManagerCompat
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.R
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.PreAlarmNotifier
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.countdownOf
import com.yawnandpawn.app.ui.format.formatClockTime
import kotlin.time.Duration

/**
 * The pre-alarm notification (Story 7.11, FR-ALM-10): channel [CHANNEL_ID] "Upcoming alarms" at default importance, the
 * title "{time} alarm" (the phone's 12/24-hour setting), the text "Rings in {h} h {min} min" in the Home countdown's
 * wording ([countdownOf]), the monochrome alarm glyph with no tint, and the action "Skip this one", which opens
 * [MainActivity] with [MainActivity.EXTRA_SKIP_ALARM] (the app then asks "Skip your {time} alarm? This is logged." over
 * Home). A tap on the notification itself opens the app. It goes away on its own when the alarm is due, and the
 * scheduler and the fire remove it earlier ([cancel]).
 *
 * One notification per alarm: its id is [NOTIFICATION_ID_BASE] + the alarm's request code (unique and stable for the
 * alarm's life, AD-4), above the wake (1 014, 1 016, 1 017) and weekly summary (1 015) ids; the action's PendingIntent
 * uses the same number, so each alarm's action carries its own id.
 *
 * It posts only with notifications enabled and, on Android 13+, `POST_NOTIFICATIONS` granted ([canPost]); it never asks
 * for the permission. A post the system refuses, or a notification service that throws, is returned as a failure (the
 * caller logs it), never thrown.
 */
class AndroidPreAlarmNotifier(
    private val context: Context,
) : PreAlarmNotifier {
    override fun canPost(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    override fun post(
        alarm: Alarm,
        ringsIn: Duration,
    ): Outcome<Unit, DomainError> =
        guarded(POST) { manager ->
            ensureChannel(manager)
            manager.notify(notificationIdOf(alarm.requestCode), build(alarm, ringsIn))
        }

    override fun cancel(requestCode: Int): Outcome<Unit, DomainError> = guarded(CANCEL) { it.cancel(notificationIdOf(requestCode)) }

    // A refused post (SecurityException) or a broken notification service is a failure value, never a crash (AD-12).
    @Suppress("TooGenericExceptionCaught")
    private fun guarded(
        operation: String,
        block: (NotificationManager) -> Unit,
    ): Outcome<Unit, DomainError> =
        try {
            block(context.getSystemService(NotificationManager::class.java))
            Outcome.Success(Unit)
        } catch (e: RuntimeException) {
            Outcome.Failure(DomainError.BackgroundWorkFailure("$operation: ${e::class.simpleName}"))
        }

    /** "Rings in 1 h 59 min", or under an hour "Rings in 45 min" (EXPERIENCE.md Key strings, the Home countdown). */
    internal fun textOf(ringsIn: Duration): String =
        when (val countdown = countdownOf(ringsIn)) {
            is Countdown.Minutes -> {
                context.getString(R.string.pre_alarm_rings_in_minutes, countdown.minutes)
            }

            is Countdown.HoursMinutes -> {
                context.getString(R.string.pre_alarm_rings_in_hours, countdown.hours, countdown.minutes)
            }

            // Never within the 2 h lead; the whole hours and the minutes left, from the duration itself.
            is Countdown.DaysHours -> {
                context.getString(
                    R.string.pre_alarm_rings_in_hours,
                    ringsIn.inWholeHours.toInt(),
                    (ringsIn.inWholeMinutes % MINUTES_PER_HOUR).toInt(),
                )
            }
        }

    private fun build(
        alarm: Alarm,
        ringsIn: Duration,
    ): Notification {
        val time = formatClockTime(alarm.time, DateFormat.is24HourFormat(context))
        val skip =
            Notification.Action
                .Builder(null, context.getString(R.string.pre_alarm_skip_action), skipIntent(alarm))
                .build()
        return Notification
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.pre_alarm_title, time))
            .setContentText(textOf(ringsIn))
            .setContentIntent(openApp())
            .addAction(skip)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Gone at the ring at the latest; the fire also removes it.
            .setTimeoutAfter(ringsIn.inWholeMilliseconds.coerceAtLeast(1))
            .build()
    }

    /** "Skip this one": the existing MainActivity gets it as a new intent, else a new one starts; the app asks first. */
    private fun skipIntent(alarm: Alarm): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationIdOf(alarm.requestCode),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SKIP_ALARM, alarm.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** A tap on the notification: opens the app (no extra, so it never skips anything). */
    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Creates the "Upcoming alarms" channel at default importance if missing (the user may change it afterwards). */
    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val name = context.getString(R.string.notification_channel_upcoming_alarms)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CHANNEL_ID = "upcoming_alarms"

        /** Added to the alarm's request code (1000 and up) for its notification id and its action's request code. */
        const val NOTIFICATION_ID_BASE = 100_000

        /**
         * The request code of the tap's PendingIntent (one for every alarm: it carries nothing). Negative, so it never
         * equals an alarm's request code (1000 and up), under which the system alarm's show intent opens the same activity.
         */
        const val REQUEST_OPEN_APP = -1_018

        /** The notification id of the alarm with [requestCode]. */
        fun notificationIdOf(requestCode: Int): Int = NOTIFICATION_ID_BASE + requestCode

        private const val MINUTES_PER_HOUR = 60
        private const val POST = "post pre-alarm"
        private const val CANCEL = "remove pre-alarm notification"
    }
}
