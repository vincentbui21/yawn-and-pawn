package com.yawnandpawn.app.android.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import com.yawnandpawn.app.R
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
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
 * The notification is the foreground notification of [WakeService] ([currentId]).
 *
 * While the wake screen is visible ([wakeScreenShown], Epic 3 device check, bug 2) the same notification is posted on the
 * low-importance [QUIET_CHANNEL_ID] channel without its full-screen intent, so no heads-up drops over the screen's
 * countdown (a post on the Alarms channel heads up on an unlocked phone, and every slot fire posts it again). Leaving the
 * screen while the alarm rings posts it on the Alarms channel again, so it heads up as the way back.
 *
 * **New post for the full-screen intent (Story 5.13 device check):** Android's SystemUI acts on a full-screen intent
 * only when the notification is first posted; an update that adds one is ignored, so the alarm rang with the screen off
 * and no wake screen. Whenever the full version replaces a version without the full-screen intent (the quiet start, the
 * on-screen one, the way-back heads-up), it is posted under an id with nothing on it (one of three, [NOTIFICATION_IDS]),
 * the wake service's foreground notification moves to it ([ForegroundHost]) and the old one is removed. A change of
 * content only, or a version without the full-screen intent, stays a plain update. During a phone call nothing posts
 * the full version, so the wake screen does not jump over the call.
 */
@Suppress("TooManyFunctions") // One function per post decision; splitting would split the one owner of what is posted.
class WakeNotifier(
    private val context: Context,
    private val timeZones: TimeZoneProvider,
    private val logger: Logger? = null,
) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Guards what is posted: [show], [wakeScreenShown] and the service's own post decide on the same state. */
    private val lock = Any()

    /**
     * The alarm time the posted notification shows; null when none is posted (or only the quiet one). Kept here, never
     * read back from the active notifications (the system lists a post only after a while).
     */
    @Volatile
    var shownFor: Instant? = null
        private set

    /** The id the notification is posted under now: one of [NOTIFICATION_IDS]. */
    @Volatile
    var currentId: Int = NOTIFICATION_ID
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

    /** Which version is posted under [currentId]; null when nothing is (or it was swiped away). */
    private var posted: Posted? = null

    /** The wake service while it is in the foreground ([postForeground]); its foreground notification moves with a new post. */
    private var host: ForegroundHost? = null

    /**
     * The id of the service's foreground notification while [host] is set. Usually [currentId]; after a refused move
     * (`startForeground` throwing) the new post under [currentId] shows beside it, and the next new post never reuses it.
     */
    private var foregroundId: Int? = null

    /**
     * The notification for a ring of the alarm scheduled at [alarmAt]. Creates the channel first. Without [fullScreen]
     * it has no full-screen intent. While the wake screen is visible it is the quiet on-screen one ([buildOnScreen]).
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
     * with the full one as a new post ([show]), so nothing flashes the wake screen at night for nothing.
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
     * session step, the heartbeat included) restores it (Story 2.5). The full one over a version without the full-screen
     * intent (the quiet start, the on-screen one, the way back) is a new post ([post]).
     *
     * Not [ringing] (a ring paused by a phone call, Story 2.7): never the full one, so the wake screen does not jump over
     * the call; the on-screen one stays, else the ringing one without its full-screen intent. The step that ends the
     * pause posts the full one again (a new post).
     */
    fun show(
        alarmAt: Instant,
        ringing: Boolean = true,
    ) {
        synchronized(lock) {
            val wanted = wantedFor(alarmAt, ringing)
            if (shownFor == alarmAt && posted == wanted) return
            post(wanted, alarmAt)
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
     *
     * A screen that stops because it is [finishing] (the Snoozed note closing by itself, Story 4.15) while the alarm
     * rings did not leave by the user's choice: the snooze ended at that very moment and its `WakeUiShown` found the
     * screen still shown. The full notification is posted then, with its full-screen intent (a new post), so the ring
     * brings the wake screen back at once instead of waiting for the next ring step.
     */
    fun wakeScreenShown(
        visible: Boolean,
        ringing: Boolean,
        changingConfigurations: Boolean = false,
        finishing: Boolean = false,
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
                screenShown && posted != Posted.OnScreen -> post(Posted.OnScreen, alarmAt)

                // Left while ringing: the heads-up without its full-screen intent, or with it when the screen closed itself.
                !screenShown && posted == Posted.OnScreen && ringing -> post(Posted.left(finishing), alarmAt)
            }
        }
    }

    /**
     * The wake service's `startForeground` in `onStartCommand`, through [host], for the alarm at [alarmAt] (null: none to
     * show yet, the quiet [buildQuiet] one). While the alarm [ringing] it is the full ringing one (the on-screen one while
     * the wake screen is visible). Otherwise (a snooze, a ring paused by a call) it keeps the on-screen one when that is
     * posted, else it is the ringing one without its full-screen intent: nothing opens the wake screen then. A full one
     * over a version without the full-screen intent is a new post ([postNew]). Throws what `startForeground` throws when
     * the platform refuses; a new post already made then stays, and the foreground stays where it was.
     */
    fun postForeground(
        host: ForegroundHost,
        alarmAt: Instant?,
        ringing: Boolean,
    ) {
        synchronized(lock) {
            val wanted = wantedFor(alarmAt, ringing)
            val notification = notificationFor(wanted, alarmAt)
            val id = if (needsNewPost(wanted)) postNew(notification).also { record(it, wanted, alarmAt) } else currentId
            val before = foregroundId.takeIf { this.host != null }
            host.startForeground(id, notification)
            this.host = host
            foregroundId = id
            record(id, wanted, alarmAt)
            // The platform drops the old foreground notification itself; without a move this removes nothing.
            if (before != null && before != id) manager.cancel(before)
        }
    }

    /** What to post for [alarmAt]: see [show] and [postForeground]. */
    private fun wantedFor(
        alarmAt: Instant?,
        ringing: Boolean,
    ): Posted =
        when {
            alarmAt == null -> Posted.Quiet
            screenShown -> Posted.OnScreen
            ringing -> Posted.Full
            posted == Posted.OnScreen && shownFor != null -> Posted.OnScreen
            else -> Posted.Away
        }

    /**
     * Posts [wanted] for [alarmAt]: a plain update under [currentId] when only the content changes or the full-screen
     * intent goes away, but a NEW post ([postNew]) when the full one ([Posted.Full]) replaces a version without the
     * full-screen intent (Story 5.13 device check: SystemUI launches it only on a first post). The wake service's
     * foreground notification then moves to the new post, and the old one is removed, so two alarm notifications never
     * stay. A refused move is logged; the new post, with its full-screen intent, stays beside the service's own.
     */
    private fun post(
        wanted: Posted,
        alarmAt: Instant,
    ) {
        val notification = notificationFor(wanted, alarmAt)
        if (!needsNewPost(wanted)) {
            manager.notify(currentId, notification)
            record(currentId, wanted, alarmAt)
            return
        }
        val id = postNew(notification)
        record(id, wanted, alarmAt)
        host?.takeIf { foregroundId != id }?.let { moveForeground(it, id, notification) }
    }

    /** Moves the wake [service]'s foreground notification to the new post [id]; a refusal is logged (both stay). */
    private fun moveForeground(
        service: ForegroundHost,
        id: Int,
        notification: Notification,
    ) {
        val before = foregroundId
        try {
            service.startForeground(id, notification)
            foregroundId = id
            before?.let(manager::cancel)
        } catch (e: IllegalStateException) {
            moveRefused(e)
        } catch (e: SecurityException) {
            moveRefused(e)
        }
    }

    /**
     * Posts [notification] so that SystemUI sees a first post, and returns its id: always under an id with nothing on it,
     * neither [currentId] nor the service's foreground id (one of three, [NOTIFICATION_IDS]). Never a cancel and a post
     * again under the same id: SystemUI can keep a heads-up entry a moment after a cancel, and the post would count as an
     * update (PR #95 review). A post under [currentId] that is not the service's own is removed after the new one shows;
     * the service's own goes when its foreground moves ([moveForeground]).
     */
    private fun postNew(notification: Notification): Int {
        val service = foregroundId.takeIf { host != null }
        val id = NOTIFICATION_IDS.first { it != currentId && it != service }
        manager.notify(id, notification)
        if (currentId != service) manager.cancel(currentId)
        return id
    }

    /** The full one over a posted version without the full-screen intent; over nothing (or a swiped one) it is a first post. */
    private fun needsNewPost(wanted: Posted): Boolean = wanted == Posted.Full && posted != null && posted != Posted.Full

    private fun notificationFor(
        wanted: Posted,
        alarmAt: Instant?,
    ): Notification =
        when {
            wanted == Posted.Quiet || alarmAt == null -> buildQuiet()
            wanted == Posted.OnScreen -> buildOnScreen(alarmAt)
            else -> buildRinging(alarmAt, fullScreen = wanted == Posted.Full)
        }

    private fun record(
        id: Int,
        wanted: Posted,
        alarmAt: Instant?,
    ) {
        currentId = id
        posted = wanted
        shownFor = alarmAt.takeIf { wanted != Posted.Quiet }
    }

    private fun moveRefused(e: RuntimeException) {
        logger?.log(LogEvent.OperationFailed(MOVE_FOREGROUND, "${e::class.simpleName}; the new post stays beside it"))
    }

    /** The user swiped the notification away (its delete intent, Story 2.5): it is no longer posted. */
    fun forget() {
        synchronized(lock) {
            shownFor = null
            posted = null
        }
    }

    /**
     * Removes the notification (both ids). The platform keeps the wake service's foreground notification until the
     * service leaves the foreground ([foregroundStopped]): what it showed is unknown from then on, so the next full one
     * is a new post (Story 5.13 device check).
     */
    fun cancel() {
        synchronized(lock) {
            NOTIFICATION_IDS.forEach(manager::cancel)
            shownFor = null
            val foreground = foregroundId.takeIf { host != null }
            if (foreground == null) {
                posted = null
            } else {
                currentId = foreground
                posted = Posted.Quiet
            }
        }
    }

    /**
     * The wake service [host] left the foreground with its notification removed (or stopped): its foreground
     * notification shows no more. Nothing when it is not the instance that entered the foreground last.
     */
    fun foregroundStopped(host: ForegroundHost) {
        synchronized(lock) {
            if (this.host !== host) return
            if (foregroundId == currentId) {
                shownFor = null
                posted = null
            }
            this.host = null
            foregroundId = null
        }
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

    /**
     * The wake service in the foreground: `startForeground(id, notification)` with its service type. A new post moves its
     * foreground notification to the new id, so the old one can be removed. Throws what `startForeground` throws.
     */
    fun interface ForegroundHost {
        fun startForeground(
            id: Int,
            notification: Notification,
        )
    }

    /**
     * The posted version: [Full] (Alarms channel with the full-screen intent), [Away] (without it), [OnScreen] (quiet,
     * while the wake screen is visible), [Quiet] (the service's "Alarm in progress" one, no alarm shown).
     */
    private enum class Posted {
        Full,
        Away,
        OnScreen,
        Quiet,
        ;

        companion object {
            /** What leaving a wake screen posts: [Full] when it closed itself ([finishing]), else [Away]. */
            fun left(finishing: Boolean): Posted = if (finishing) Full else Away
        }
    }

    companion object {
        const val CHANNEL_ID = "alarms"

        /** The quiet channel of [buildQuiet] and [buildOnScreen]. */
        const val QUIET_CHANNEL_ID = "alarm_in_progress"

        /** The first id of the notification; a new post for the full-screen intent goes under another of [NOTIFICATION_IDS]. */
        const val NOTIFICATION_ID = 1_014

        /** The second id (Story 5.13 device check; 1015 is the weekly summary's). */
        const val OTHER_NOTIFICATION_ID = 1_016

        /**
         * The third id (PR #95 review): with the service's foreground id and the current one both taken (after a refused
         * move), a new post still goes under an id with nothing on it.
         */
        const val THIRD_NOTIFICATION_ID = 1_017

        /** The ids the ringing notification is posted under, in the order a new post picks a free one. */
        val NOTIFICATION_IDS: List<Int> = listOf(NOTIFICATION_ID, OTHER_NOTIFICATION_ID, THIRD_NOTIFICATION_ID)
        private const val MOVE_FOREGROUND = "move the wake service notification"
        private const val REQUEST_WAKE_SCREEN = 0
        private const val REQUEST_REPOST = 1
    }
}
