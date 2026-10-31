package com.yawnandpawn.app.android.reminder

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
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reminder.SkipReminderNotifier
import com.yawnandpawn.app.core.reminder.SkipReminderTarget
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.countdownOf
import com.yawnandpawn.app.ui.format.formatClockTime
import kotlin.time.Duration

/**
 * The Skip reminder notification (0.1.8, FR-ALM-10; owner-approved mockup 2026-10-31): channel [CHANNEL_ID] "Upcoming
 * alarms" at default importance, the monochrome alarm glyph with no tint, the title "Up already?" and the text "Your
 * {time} alarm rings in {h} h {m} min. Skip today's alarms?" ("rings in {m} min" under an hour, the Home countdown's
 * rounding, [countdownOf]); {time} follows the phone's 12/24-hour setting. Two actions go to
 * [SkipReminderActionReceiver] without opening the app: "Skip today's alarms" (with the date and the first ring) and
 * "Keep". A tap on the notification itself opens the app on Alarms ([MainActivity.OPEN_ALARMS]).
 *
 * The reminder is one notification ([NOTIFICATION_ID], after the wake (1 014, 1 016, 1 017) and weekly summary (1 015)
 * ids): a post replaces it. [showSkipped] replaces it with "Today's alarms are skipped. No charge." under its own tag
 * ([TAG_SKIPPED]), so "Don't skip" removes the confirmation alone ([cancelSkipped]). Posted the evening before a ring
 * just after midnight, the text, the action and the confirmation say "tomorrow's". Each goes away on its own at the first
 * ring at the latest; the fire removes them earlier.
 *
 * It posts only with notifications enabled and, on Android 13+, `POST_NOTIFICATIONS` granted ([canPost]); it never asks
 * for the permission. A post the system refuses, or a notification service that throws, is returned as a failure (the
 * caller logs it), never thrown. Every PendingIntent is immutable.
 */
class AndroidSkipReminderNotifier(
    private val context: Context,
) : SkipReminderNotifier {
    override fun canPost(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    override fun post(
        target: SkipReminderTarget,
        ringsIn: Duration,
        tomorrow: Boolean,
    ): Outcome<Unit, DomainError> =
        guarded(POST) { manager ->
            ensureChannel(manager)
            manager.notify(NOTIFICATION_ID, reminder(target, ringsIn, tomorrow))
        }

    /** The confirmation has its own tag, so removing it ([cancelSkipped]) never touches a reminder. */
    override fun showSkipped(
        dismissIn: Duration,
        tomorrow: Boolean,
    ): Outcome<Unit, DomainError> =
        guarded(POST) { manager ->
            ensureChannel(manager)
            manager.cancel(NOTIFICATION_ID)
            val title = context.getString(if (tomorrow) R.string.skip_reminder_done_tomorrow else R.string.skip_reminder_done)
            manager.notify(TAG_SKIPPED, NOTIFICATION_ID, base(dismissIn).setContentTitle(title).build())
        }

    override fun cancelSkipped(): Outcome<Unit, DomainError> = guarded(CANCEL) { it.cancel(TAG_SKIPPED, NOTIFICATION_ID) }

    override fun cancelLegacy(requestCode: Int): Outcome<Unit, DomainError> =
        guarded(CANCEL) { it.cancel(LEGACY_NOTIFICATION_ID_BASE + requestCode) }

    override fun cancel(): Outcome<Unit, DomainError> =
        guarded(CANCEL) { manager ->
            manager.cancel(NOTIFICATION_ID)
            manager.cancel(TAG_SKIPPED, NOTIFICATION_ID)
        }

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

    /**
     * "Your 7:00 AM alarm rings in 1 h 59 min. Skip today's alarms?", or under an hour "... rings in 45 min. ..."; with
     * [tomorrow] "... Skip tomorrow's alarms?".
     */
    internal fun textOf(
        target: SkipReminderTarget,
        ringsIn: Duration,
        tomorrow: Boolean = false,
    ): String {
        val time = formatClockTime(target.time, DateFormat.is24HourFormat(context))
        val hours = if (tomorrow) R.string.skip_reminder_text_hours_tomorrow else R.string.skip_reminder_text_hours
        return when (val countdown = countdownOf(ringsIn)) {
            is Countdown.Minutes -> {
                val minutes = if (tomorrow) R.string.skip_reminder_text_minutes_tomorrow else R.string.skip_reminder_text_minutes
                context.getString(minutes, time, countdown.minutes)
            }

            is Countdown.HoursMinutes -> {
                context.getString(hours, time, countdown.hours, countdown.minutes)
            }

            // Never within the 2 h lead; the whole hours and the minutes left, from the duration itself.
            is Countdown.DaysHours -> {
                context.getString(hours, time, ringsIn.inWholeHours.toInt(), (ringsIn.inWholeMinutes % MINUTES_PER_HOUR).toInt())
            }
        }
    }

    private fun reminder(
        target: SkipReminderTarget,
        ringsIn: Duration,
        tomorrow: Boolean,
    ): Notification {
        val text = textOf(target, ringsIn, tomorrow)
        val skip =
            Notification.Action
                .Builder(
                    null,
                    context.getString(if (tomorrow) R.string.skip_reminder_skip_tomorrow else R.string.skip_reminder_skip),
                    broadcast(context, REQUEST_SKIP, SkipReminderActionReceiver.skipIntent(context, target)),
                ).build()
        val keep =
            Notification.Action
                .Builder(
                    null,
                    context.getString(R.string.skip_reminder_keep),
                    broadcast(context, REQUEST_KEEP, SkipReminderActionReceiver.keepIntent(context)),
                ).build()
        return base(ringsIn)
            .setContentTitle(context.getString(R.string.skip_reminder_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(skip)
            .addAction(keep)
            .build()
    }

    private fun base(timeout: Duration): Notification.Builder =
        Notification
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentIntent(openAlarms(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Gone at the first ring at the latest; the fire also removes it.
            .setTimeoutAfter(timeout.inWholeMilliseconds.coerceAtLeast(1))

    /** Creates the "Upcoming alarms" channel at default importance if missing (the user may change it afterwards). */
    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val name = context.getString(R.string.notification_channel_upcoming_alarms)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        /** The channel of Story 7.11's pre-alarm notification, kept (the user's channel settings stay). */
        const val CHANNEL_ID = "upcoming_alarms"

        const val NOTIFICATION_ID = 1_018

        /** The confirmation's tag ("Today's alarms are skipped."), so it and the reminder are two notifications. */
        const val TAG_SKIPPED = "skipped"

        /** Story 7.11's pre-alarm notification id was this plus the alarm's request code (removed by the update cleanup). */
        const val LEGACY_NOTIFICATION_ID_BASE = 100_000

        /**
         * The request codes of the PendingIntents. Negative, so they never equal an alarm's request code (1000 and up),
         * under which the system alarm's show intent opens the same activity.
         */
        const val REQUEST_OPEN_ALARMS = -1_018
        const val REQUEST_SKIP = -1_019
        const val REQUEST_KEEP = -1_020

        private const val MINUTES_PER_HOUR = 60
        private const val POST = "post skip reminder"
        private const val CANCEL = "remove skip reminder"
    }
}

/** A broadcast to [SkipReminderActionReceiver] (explicit, immutable) under [requestCode] (one per action). */
private fun broadcast(
    context: Context,
    requestCode: Int,
    intent: Intent,
): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

/** A tap on the notification: opens the app on Alarms (Home); the existing MainActivity gets it as a new intent. */
private fun openAlarms(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        AndroidSkipReminderNotifier.REQUEST_OPEN_ALARMS,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.OPEN_ALARMS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
