package com.yawnandpawn.app.android.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import com.yawnandpawn.app.R
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.format.formatClockTime
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The ongoing ringing notification (EXPERIENCE.md `notification-ringing`, AD-5): channel [CHANNEL_ID] "Alarms" at high
 * importance, category alarm, ongoing, public on the lock screen, the sunrise status icon in the accent colour. Its
 * title is the alarm time (in the phone's 12/24-hour format), its text "{time} alarm · Tap to return to your alarm".
 * Both its full-screen intent and its content intent open [WakeActivity] (immutable); it has no action, so nothing in
 * it stops the sound. The notification is the foreground notification of [WakeService] ([NOTIFICATION_ID]).
 */
class WakeNotifier(
    private val context: Context,
    private val timeZones: TimeZoneProvider,
) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** The alarm time the posted notification shows; null when none is posted. */
    @Volatile
    var shownFor: Instant? = null
        private set

    /** The notification for a ring of the alarm scheduled at [alarmAt]. Creates the channel first. */
    fun build(alarmAt: Instant): Notification {
        ensureChannel()
        val time = formatClockTime(alarmAt.toLocalDateTime(timeZones.current()).time, DateFormat.is24HourFormat(context))
        val openWakeScreen =
            PendingIntent.getActivity(
                context,
                REQUEST_WAKE_SCREEN,
                WakeActivity.intent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(time)
            .setContentText(context.getString(R.string.notification_ringing_text, time))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setContentIntent(openWakeScreen)
            .setFullScreenIntent(openWakeScreen, true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }.build()
    }

    /**
     * The quiet foreground notification of a service start with no alarm to show yet (a slot fire or a restore): low
     * importance on its own channel, no sound, no full-screen intent, "Alarm in progress". A ringing state replaces it
     * with [build]'s notification (its `WakeUiShown`), so nothing flashes the wake screen at night for nothing.
     */
    fun buildQuiet(): Notification {
        if (manager.getNotificationChannel(QUIET_CHANNEL_ID) == null) {
            val name = context.getString(R.string.notification_channel_session)
            manager.createNotificationChannel(
                NotificationChannel(QUIET_CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) },
            )
        }
        return Notification
            .Builder(context, QUIET_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(context.getString(R.string.notification_channel_session))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    /** Posts (or updates) the notification for [alarmAt]; nothing when it already shows that time. */
    fun show(alarmAt: Instant) {
        if (shownFor == alarmAt) return
        manager.notify(NOTIFICATION_ID, build(alarmAt))
        shownFor = alarmAt
    }

    /** [WakeService] posted [alarmAt]'s notification itself with `startForeground` (called once that succeeded). */
    fun shownByService(alarmAt: Instant) {
        shownFor = alarmAt
    }

    /** Removes the notification. */
    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
        shownFor = null
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val name = context.getString(R.string.notification_channel_alarms)
        val channel =
            NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH).apply {
                // The player rings and vibrates; the notification itself stays quiet.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "alarms"

        /** The quiet channel of [buildQuiet]. */
        const val QUIET_CHANNEL_ID = "alarm_in_progress"
        const val NOTIFICATION_ID = 1_014
        private const val REQUEST_WAKE_SCREEN = 0
    }
}
