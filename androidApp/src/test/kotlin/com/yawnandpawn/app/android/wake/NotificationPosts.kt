package com.yawnandpawn.app.android.wake

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationManager
import java.util.Collections
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Story 5.13 device check: records every post and removal of the app's notifications, in order. `ShadowService`
 * posts its foreground notification through `NotificationManager.notify` (and removes it through `cancel`), so the wake
 * service's `startForeground` posts are recorded too. Enable it on a test class with
 * `@Config(shadows = [RecordingNotificationManager::class])`, then read it through [NotificationPosts].
 */
@Implements(NotificationManager::class)
class RecordingNotificationManager : ShadowNotificationManager() {
    /** Every post and removal so far, oldest first. */
    val events: MutableList<NotificationEvent> = Collections.synchronizedList(mutableListOf())

    @Implementation
    override fun notify(
        tag: String?,
        id: Int,
        notification: Notification,
    ) {
        events += NotificationEvent.Posted(id, notification.fullScreenIntent != null, notification.channelId, byService())
        super.notify(tag, id, notification)
    }

    @Implementation
    override fun cancel(
        tag: String?,
        id: Int,
    ) {
        events += NotificationEvent.Removed(id, byService())
        super.cancel(tag, id)
    }

    /** Called from `ShadowService` (`startForeground`, `stopForeground`, the service's destroy), not from the app. */
    private fun byService(): Boolean = Thread.currentThread().stackTrace.any { it.className.endsWith(".ShadowService") }

    @Implementation
    override fun cancelAll() {
        events += NotificationEvent.RemovedAll
        super.cancelAll()
    }
}

/** A post ([Posted]) or a removal ([Removed], [RemovedAll]) seen by [RecordingNotificationManager]. */
sealed interface NotificationEvent {
    data class Posted(
        val id: Int,
        val fullScreen: Boolean,
        val channelId: String?,
        /** Posted by the wake service's `startForeground`: its foreground notification from now on. */
        val foreground: Boolean,
    ) : NotificationEvent

    data class Removed(
        val id: Int,
        /** Removed by the service (`stopForeground`, its destroy); the app's own cancel of it does nothing on a device. */
        val byService: Boolean,
    ) : NotificationEvent

    data object RemovedAll : NotificationEvent
}

/**
 * The posts [RecordingNotificationManager] recorded for the app in [context], replayed per id as a device would see
 * them: a post is NEW when nothing was shown under its id just before, else it UPDATES that post. Android's SystemUI
 * launches a full-screen intent only on a new post (Story 5.13 device check: the alarm rang with the screen off), so a
 * post with a full-screen intent must never update a post without one under the same id.
 *
 * The replay follows the platform's rules for the wake service's foreground notification, which Robolectric does not:
 * the app's own cancel of it does nothing (only the service's stop removes it), and a `startForeground` under another
 * id drops the old foreground notification.
 */
internal class NotificationPosts(
    context: Context,
) {
    private val recorder: RecordingNotificationManager =
        Shadow.extract(context.getSystemService(NotificationManager::class.java))

    /** One replayed post: [new] when nothing was shown under its id; [replaced] is the post it updated, if any. */
    data class Post(
        val index: Int,
        val posted: NotificationEvent.Posted,
        val replaced: NotificationEvent.Posted?,
    ) {
        val new: Boolean
            get() = replaced == null
    }

    /** The replay: every post, and what a device shows at the end, by id. */
    private class Replay(
        val posts: List<Post>,
        val shown: Map<Int, NotificationEvent.Posted>,
    )

    private fun replay(): Replay {
        val shown = mutableMapOf<Int, NotificationEvent.Posted>()
        var foregroundId: Int? = null
        val replayed = mutableListOf<Post>()
        recorder.events.toList().forEachIndexed { index, event ->
            when (event) {
                is NotificationEvent.Posted -> {
                    if (event.foreground && foregroundId != null && foregroundId != event.id) shown.remove(foregroundId)
                    replayed += Post(index, event, shown[event.id])
                    shown[event.id] = event
                    if (event.foreground) foregroundId = event.id
                }

                is NotificationEvent.Removed -> {
                    if (event.id != foregroundId || event.byService) {
                        shown.remove(event.id)
                        if (event.id == foregroundId) foregroundId = null
                    }
                }

                NotificationEvent.RemovedAll -> {
                    shown.keys.retainAll(listOfNotNull(foregroundId))
                }
            }
        }
        return Replay(replayed, shown)
    }

    /** How many events are recorded now: pass it as `since` to look only at what happens after. */
    fun mark(): Int = recorder.events.size

    /** Every post, replayed per id, oldest first; only those from event [since] on. */
    fun posts(since: Int = 0): List<Post> = replay().posts.filter { it.index >= since }

    /** No post with a full-screen intent ever updated a post without one under the same id. */
    fun assertFullScreenOnlyOnNewPosts() {
        val bad = posts().filter { it.posted.fullScreen && it.replaced?.fullScreen == false }
        if (bad.isNotEmpty()) fail("a full-screen intent was added by an update, which SystemUI ignores: $bad; all: ${posts()}")
    }

    /**
     * Since [since], a wake notification with a full-screen intent on the Alarms channel was posted as a NEW post (so
     * SystemUI launches it), and none was added by an update.
     */
    fun assertNewFullScreenPost(
        since: Int,
        what: String,
    ) {
        assertFullScreenOnlyOnNewPosts()
        val recent = posts(since).filter { it.posted.id in WakeNotifier.NOTIFICATION_IDS }
        assertTrue(
            recent.any { it.new && it.posted.fullScreen && it.posted.channelId == WakeNotifier.CHANNEL_ID },
            "$what: a new post with the full-screen intent; posts since: $recent",
        )
    }

    /** At most one wake notification is shown now, in Robolectric and as a device would show it. */
    fun assertAtMostOneShown(manager: NotificationManager) {
        val shown = shownIds(manager)
        assertTrue(shown.size <= 1, "one alarm notification at most, shown: $shown")
        val onDevice = replay().shown.keys.filter { it in WakeNotifier.NOTIFICATION_IDS }
        assertTrue(onDevice.size <= 1, "one alarm notification at most on a device, shown: $onDevice")
    }

    /** The wake notifications shown now (in Robolectric), by id. */
    fun shownIds(manager: NotificationManager): List<Int> =
        manager.activeNotifications.map { it.id }.filter { it in WakeNotifier.NOTIFICATION_IDS }

    companion object {
        /** The posted wake notification (either id), or null; fails when two are shown. */
        fun wakeNotification(manager: NotificationManager): Notification? {
            val shown = manager.activeNotifications.filter { it.id in WakeNotifier.NOTIFICATION_IDS }
            assertTrue(shown.size <= 1, "one alarm notification at most: ${shown.map { it.id }}")
            return shown.singleOrNull()?.notification
        }
    }
}
