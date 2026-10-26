package com.yawnandpawn.app.android.wake

import android.app.NotificationManager
import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.AlarmFiredReceiver
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.buildActivity
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 5.13 device check (Oppo A96, Android 13): a test alarm rang locked with the screen off, but the screen never
 * woke. The full-screen intent came only as an update of the quiet "Alarm in progress" notification, and SystemUI
 * launches a full-screen intent only when a notification is first posted. On every path that rings, the notification
 * with the full-screen intent must be a NEW post ([NotificationPosts]), and at most one alarm notification shows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", shadows = [RecordingNotificationManager::class])
class FullScreenNewPostTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val ringsAt = Instant.parse("2027-03-08T06:00:00Z")
    private val testAlarms = FakeTestAlarmStore()

    private fun manager(app: WakeApp): NotificationManager = app.app.getSystemService(NotificationManager::class.java)

    /** The posts under the wake notification's ids. */
    private fun wakePosts(posts: NotificationPosts) = posts.posts().filter { it.posted.id in WakeNotifier.NOTIFICATION_IDS }

    /** The test alarm's system alarm fires with its ring time, as `AndroidAlarmScheduler.scheduleTest` armed it. */
    private fun fireTestAlarm(app: WakeApp) =
        app.app
            .sendBroadcast(
                AlarmFiredReceiver
                    .intent(app.app, AlarmFiredReceiver.ACTION_TEST_ALARM)
                    .putExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, ringsAt.toEpochMilliseconds()),
            ).also {
                shadowOf(Looper.getMainLooper()).idle()
                app.koin.get<ApplicationScope>().awaitChildren()
            }

    @Test
    fun `a test alarm's first post is the full ringing notification with its full-screen intent`() {
        val app = WakeApp(testAlarms = testAlarms)
        testAlarms.pending = aSessionConfig(label = "test", scheduledAt = ringsAt, testMode = true)
        val posts = NotificationPosts(app.app)

        fireTestAlarm(app)
        val start = assertNotNull(shadowOf(app.app).nextStartedService, "the receiver started the wake service")
        assertEquals(WakeService.ACTION_TEST, start.action)
        assertEquals(ringsAt.toEpochMilliseconds(), start.getLongExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, 0), "the ring time")
        val service = app.startService(start)

        val first = assertNotNull(wakePosts(posts).firstOrNull(), "posted at once").posted
        assertTrue(first.foreground && first.fullScreen && first.channelId == WakeNotifier.CHANNEL_ID, "$first")
        app.awaitRinging()
        assertTrue(
            app.engine.state.value
                .let { it is SessionState.Ringing && it.session.config.testMode },
        )
        posts.assertNewFullScreenPost(0, "the test alarm")
        assertEquals(1, wakePosts(posts).size, "the ring step posts nothing more: one alert")
        posts.assertAtMostOneShown(manager(app))
        service.destroy()
    }

    @Test
    fun `a slot start into a session stored by a killed process posts the full-screen ring as a new post`() {
        val app = WakeApp()
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<ActiveSessionStore>().commit(SessionState.Ringing(aSession())) })
        val posts = NotificationPosts(app.app)

        // A new process: nothing is shown yet, so the slot start enters the foreground with the quiet notification.
        val service = app.startService(WakeService.intent(app.app, WakeService.ACTION_SLOT))
        app.awaitRinging()

        val quiet = wakePosts(posts).first().posted
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, quiet.channelId)
        posts.assertNewFullScreenPost(0, "the restored ring")
        val foreground = shadowOf(service.get())
        assertEquals(app.koin.get<WakeNotifier>().currentId, foreground.lastForegroundNotificationId, "the foreground moved to it")
        assertNotNull(foreground.lastForegroundNotification.fullScreenIntent)
        posts.assertAtMostOneShown(manager(app))
        assertEquals(WakeNotifier.CHANNEL_ID, NotificationPosts.wakeNotification(manager(app))?.channelId)
        service.destroy()
    }

    @Test
    fun `a first ring posts the full-screen notification once, as a new post (regression guard)`() {
        val app = WakeApp()
        val alarm = anAlarm(id = "alarm-a", requestCode = 1000)
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        val posts = NotificationPosts(app.app)

        val service = app.ring(AlarmFired(alarm.id, ringsAt))
        app.awaitRinging()

        posts.assertNewFullScreenPost(0, "the first ring")
        val wake = wakePosts(posts)
        assertEquals(1, wake.size, "one post: the ring step posts nothing more")
        assertEquals(WakeNotifier.NOTIFICATION_ID, wake.single().posted.id)
        assertTrue(wake.single().posted.foreground)
        service.destroy()
    }

    @Test
    fun `a test fire with nothing pending leaves no wake screen waiting once the service stops`() {
        val app = WakeApp(testAlarms = testAlarms)
        val posts = NotificationPosts(app.app)

        fireTestAlarm(app)
        val service = app.startService(assertNotNull(shadowOf(app.app).nextStartedService))
        // The full-screen intent of the first post opens the wake screen before the service knows nothing is pending.
        assertTrue(wakePosts(posts).first().posted.fullScreen)
        val screen = buildActivity(WakeActivity::class.java).setup().get()
        app.awaitUntil("the service stops") { shadowOf(service.get()).isStoppedBySelf }
        assertTrue(app.logs().any { it.startsWith("FireIgnored kind=TestAlarm alarmId=null reason=no test pending") }, "${app.logs()}")
        assertFalse(screen.isFinishing, "it waits while the service runs")

        // The system destroys the stopped service.
        service.destroy()

        composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) { screen.isFinishing }
        assertEquals(SessionState.Idle, app.engine.state.value)
        assertEquals(0, posts.shownIds(manager(app)).size, "no alarm notification is left")
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
