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
 * it stops the sound. Its delete intent (a swipe on Android 14+) restarts [WakeService] to post it again (Story 2.5).
 * The notification is the foreground notification of [WakeService] ([NOTIFICATION_ID]).
 *
 * While the wake screen is visible ([wakeScreenShown], Epic 3 device check, bug 2) the same notification is posted on the
 * low-importance [QUIET_CHANNEL_ID] channel without its full-screen intent, so no heads-up drops over the screen's
 * countdown (a post on the Alarms channel heads up on an unlocked phone, and every slot fire posts it again). Leaving the
 * screen while the alarm rings posts it on the Alarms channel again, so it heads up as the way back.
 */
class WakeNotifier(
    private val context: Context,
    private val timeZones: TimeZoneProvider,
) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Guards what is posted: [show], [wakeScreenShown] and the service's own post decide on the same state. */
    private val lock = Any()

    /**
     * The alarm time the posted notification shows; null when none is posted. Kept here, never read back from the
     * active notifications (the system lists a post only after a while): only [cancel] and [forget] clear it.
     */
    @Volatile
    var shownFor: Instant? = null
        private set

    /** A wake screen is visible (one or more started instances): every post is the quiet on-screen one. */
    @Volatile
    var screenShown: Boolean = false
        private set

    /**
     * Started wake screens (PR #41 review): two `singleTask` instances can overlap, with B started before A stopped, so
     * the screen counts as visible while any is started.
     */
    private var startedScreens = 0

    /** Wake screens stopped for a configuration change (rotation, dark mode, font scale): their new instance starts next. */
    private var recreatingScreens = 0

    /** Which version is posted (meaningful while [shownFor] is set). */
    private var posted = Posted.Full

    /**
     * The notification for a ring of the alarm scheduled at [alarmAt]. Creates the channel first. Without [fullScreen]
     * it has no full-screen intent (a swiped notification posted again during a snooze, Story 2.5). While the wake screen
     * is visible it is the quiet on-screen one ([buildOnScreen]), so a slot fire's `startForeground` heads up over nothing.
     */
    fun build(
        alarmAt: Instant,
        fullScreen: Boolean = true,
    ): Notification = if (screenShown) buildOnScreen(alarmAt) else buildRinging(alarmAt, fullScreen)

    /** The notification on the high-importance Alarms channel: it heads up on an unlocked phone (the way back, Story 2.5). */
    fun buildRinging(
        alarmAt: Instant,
        fullScreen: Boolean = true,
    ): Notification {
        ensureChannel()
        return ringingBuilder(alarmAt, CHANNEL_ID)
            .apply { if (fullScreen) setFullScreenIntent(openWakeScreen, true) }
            .build()
    }

    /**
     * The same notification while the wake screen is visible: on the low-importance [QUIET_CHANNEL_ID] channel and
     * without a full-screen intent, so nothing heads up over the screen (Epic 3 device check, bug 2). Same title, text,
     * tap and delete intent: pulled down from the shade it still reads "Tap to return to your alarm".
     */
    fun buildOnScreen(alarmAt: Instant): Notification {
        ensureChannel(quiet = true)
        return ringingBuilder(alarmAt, QUIET_CHANNEL_ID).build()
    }

    private fun ringingBuilder(
        alarmAt: Instant,
        channelId: String,
    ): Notification.Builder {
        val time = formatClockTime(alarmAt.toLocalDateTime(timeZones.current()).time, DateFormat.is24HourFormat(context))
        // Android 14+ lets the user swipe it away: the wake service posts it again at once (Story 2.5).
        val postAgain =
            PendingIntent.getService(
                context,
                REQUEST_REPOST,
                WakeService.repostIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification
            .Builder(context, channelId)
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
            .setDeleteIntent(postAgain)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
    }

    /** Opens [WakeActivity]: the full-screen intent and the tap of every version. */
    private val openWakeScreen: PendingIntent by lazy {
        PendingIntent.getActivity(
            context,
            REQUEST_WAKE_SCREEN,
            WakeActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * The quiet foreground notification of a service start with no alarm to show yet (a slot fire or a restore): low
     * importance on its own channel, no sound, no full-screen intent, "Alarm in progress". A ringing state replaces it
     * with [build]'s notification (its `WakeUiShown`), so nothing flashes the wake screen at night for nothing.
     */
    fun buildQuiet(): Notification {
        ensureChannel(quiet = true)
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

    /**
     * Posts (or updates) the notification for [alarmAt]: the quiet on-screen one while the wake screen is visible, else
     * the full ringing one with its full-screen intent. Nothing when that one already shows that time, so a step never
     * posts (and alerts) twice. The heads-up posted on leaving the screen has no full-screen intent, so the next ring
     * step (the grace end, the heartbeat, an emergency ring) posts the full one, and a ring that goes loud turns the
     * screen on again (PR #41 review). After a swipe ([forget]) it is posted again, so the next `WakeUiShown` (each
     * session step, the heartbeat included) restores it (Story 2.5).
     */
    fun show(alarmAt: Instant) {
        synchronized(lock) {
            val wanted = if (screenShown) Posted.OnScreen else Posted.Full
            if (shownFor == alarmAt && posted == wanted) return
            manager.notify(NOTIFICATION_ID, if (wanted == Posted.OnScreen) buildOnScreen(alarmAt) else buildRinging(alarmAt))
            shownFor = alarmAt
            posted = wanted
        }
    }

    /**
     * A wake screen started ([visible], `onStart`) or stopped (`onStop`: Home, another app, the screen off). The screen
     * counts as visible while any instance is started; a stop for a [changingConfigurations] recreate keeps it visible
     * until the new instance starts, so a rotation posts nothing (PR #41 review).
     *
     * Visible: a posted ringing notification becomes the on-screen one, which also takes down a heads-up already over
     * the screen. Left while the alarm [ringing] (Ring or an emergency ring): the ringing one again, so it heads up as the
     * way back (Story 2.5), without its full-screen intent, so leaving with the power key does not bring the screen
     * straight back; the next ring step posts the full one ([show]). Left in a snooze, the quiet one stays and the snooze
     * end's `WakeUiShown` posts the full one.
     */
    fun wakeScreenShown(
        visible: Boolean,
        ringing: Boolean,
        changingConfigurations: Boolean = false,
    ) {
        synchronized(lock) {
            when {
                visible && recreatingScreens > 0 -> recreatingScreens--
                visible -> startedScreens++
                changingConfigurations -> recreatingScreens++
                else -> startedScreens = (startedScreens - 1).coerceAtLeast(0)
            }
            screenShown = startedScreens > 0
            val alarmAt = shownFor ?: return
            when {
                screenShown && posted != Posted.OnScreen -> {
                    manager.notify(NOTIFICATION_ID, buildOnScreen(alarmAt))
                    posted = Posted.OnScreen
                }

                !screenShown && posted == Posted.OnScreen && ringing -> {
                    manager.notify(NOTIFICATION_ID, buildRinging(alarmAt, fullScreen = false))
                    posted = Posted.Away
                }
            }
        }
    }

    /** [WakeService] posted [alarmAt]'s notification ([build]) itself with `startForeground` (called once that succeeded). */
    fun shownByService(alarmAt: Instant) {
        synchronized(lock) {
            shownFor = alarmAt
            posted = if (screenShown) Posted.OnScreen else Posted.Full
        }
    }

    /** The user swiped the notification away (its delete intent, Story 2.5): it is no longer posted. */
    fun forget() {
        shownFor = null
    }

    /** Removes the notification. */
    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
        shownFor = null
    }

    /** Creates the Alarms channel ([CHANNEL_ID], high importance), or with [quiet] the [QUIET_CHANNEL_ID] one, if missing. */
    private fun ensureChannel(quiet: Boolean = false) {
        val id = if (quiet) QUIET_CHANNEL_ID else CHANNEL_ID
        if (manager.getNotificationChannel(id) != null) return
        val channel =
            if (quiet) {
                val name = context.getString(R.string.notification_channel_session)
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) }
            } else {
                val name = context.getString(R.string.notification_channel_alarms)
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                    // The player rings and vibrates; the notification itself stays quiet.
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            }
        manager.createNotificationChannel(channel)
    }

    /** The posted version: [Full] (Alarms channel with the full-screen intent), [Away] (without it), [OnScreen] (quiet). */
    private enum class Posted { Full, Away, OnScreen }

    companion object {
        const val CHANNEL_ID = "alarms"

        /** The quiet channel of [buildQuiet] and [buildOnScreen]. */
        const val QUIET_CHANNEL_ID = "alarm_in_progress"
        const val NOTIFICATION_ID = 1_014
        private const val REQUEST_WAKE_SCREEN = 0
        private const val REQUEST_REPOST = 1
    }
}
