package com.yawnandpawn.app.android.wake

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 2.5 (FR-SES-4, FR-SES-9): the user can leave the wake screen during a ring and come back with one tap. Leaving
 * changes nothing in the session (the sound plays on in the foreground service). The ongoing notification, which has
 * no action, always leads back to the one wake screen; on Android 14+ a swiped-away notification is posted again.
 * Opening the app during a ring hands over to the wake screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LeaveAndReturnTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val alarm = anAlarm(id = "alarm-a", requestCode = 1000)
    private val fired = AlarmFired(alarm.id, Instant.parse("2027-03-08T06:00:00Z"))

    private fun manager(app: WakeApp) = app.app.getSystemService(NotificationManager::class.java)

    private fun posted(app: WakeApp): Notification? =
        manager(app).activeNotifications.firstOrNull { it.id == WakeNotifier.NOTIFICATION_ID }?.notification

    /** Rings the stored alarm in the real wake service, as its system alarm would. */
    private fun ring(app: WakeApp): ServiceController<WakeService> {
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        val service = app.ring(fired)
        app.awaitRinging()
        return service
    }

    /** Every activity started so far, oldest first; reading them clears the list. */
    private fun startedActivities(app: WakeApp): List<Intent> = generateSequence { shadowOf(app.app).nextStartedActivity }.toList()

    private fun isWakeScreen(intent: Intent) = intent.component?.className == WakeActivity::class.java.name

    @Test
    fun `leaving the wake screen keeps the service in the foreground and the sound playing, and starts no activity`() {
        val app = WakeApp()
        val service = ring(app)
        val screen = Robolectric.buildActivity(WakeActivity::class.java).setup()
        startedActivities(app)

        // Home, Recents or another app: the wake screen is paused and stopped.
        screen.pause().stop()
        app.awaitUntil("the main looper settles") { true }

        assertTrue(app.lastMediaPlayer().isReallyPlaying, "the sound keeps playing")
        assertTrue(app.vibrator.isVibrating, "it keeps vibrating")
        assertFalse(shadowOf(service.get()).isStoppedBySelf, "the service keeps running")
        assertTrue(app.runtime.isServiceRunning)
        assertNotNull(posted(app), "the notification stays")
        assertEquals(emptyList(), startedActivities(app), "nothing starts an activity from the background")
        assertTrue(app.engine.state.value is SessionState.Ringing)
        // Back in front again (the notification), Back still does nothing.
        screen.restart().resume()
        screen.get().onBackPressedDispatcher.onBackPressed()
        assertFalse(screen.get().isFinishing)
    }

    @Test
    fun `the notification has no action, and its taps and full-screen intent open the one wake screen on the current state`() {
        val app = WakeApp()
        ring(app)
        val notification = assertNotNull(posted(app))

        assertTrue(notification.actions.isNullOrEmpty(), "nothing in the notification stops or lowers the sound")
        listOf(notification.contentIntent, notification.fullScreenIntent).forEach { pending ->
            val intent = shadowOf(pending).savedIntent
            assertTrue(shadowOf(pending).isActivity)
            assertEquals(ComponentName(app.app, WakeActivity::class.java), intent.component)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
        // The platform keeps one instance (singleTask in its own task, WakeActivityTest checks the manifest); tapped
        // three times, the open screen gets the intent again and still shows the current state.
        val screen = Robolectric.buildActivity(WakeActivity::class.java).setup()
        repeat(3) {
            notification.contentIntent.send()
            val tap = startedActivities(app).single()
            screen.newIntent(tap)
        }
        composeRule.onNodeWithText("I'm up").assertExists()
        assertFalse(screen.get().isFinishing)
    }

    @Test
    fun `a swiped-away notification is posted again by its delete intent while the sound plays`() {
        val app = WakeApp()
        val service = ring(app)
        val before = app.engine.state.value
        val notification = assertNotNull(posted(app))

        // The user swipes it away (Android 14+): the system removes it and sends its delete intent.
        manager(app).cancel(WakeNotifier.NOTIFICATION_ID)
        assertNull(posted(app))
        notification.deleteIntent.send()
        val repost = assertNotNull(shadowOf(app.app).nextStartedService, "the delete intent starts the wake service")
        assertEquals(WakeService.ACTION_REPOST, repost.action)
        service.withIntent(repost).startCommand(0, 2)
        app.awaitUntil("the notification is back") { posted(app) != null }

        assertEquals(WakeNotifier.NOTIFICATION_ID, shadowOf(service.get()).lastForegroundNotificationId)
        assertTrue(app.lastMediaPlayer().isReallyPlaying, "the sound never stopped")
        assertSame(before, app.engine.state.value, "no session event")
        assertTrue(posted(app)!!.actions.isNullOrEmpty())
    }

    @Test
    fun `a notification still missing is posted again at the next heartbeat`() {
        val app = WakeApp()
        ring(app)

        manager(app).cancel(WakeNotifier.NOTIFICATION_ID)
        app.dispatch(SessionEvent.SlotFired)

        val back = assertNotNull(posted(app), "the wake UI entry effect posts it again")
        assertEquals(ComponentName(app.app, WakeActivity::class.java), shadowOf(back.contentIntent).savedIntent.component)
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
    }

    @Test
    fun `opening the app during a ring hands over to the wake screen once`() {
        val app = WakeApp()
        ring(app)
        startedActivities(app)

        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        var forwarded: List<Intent> = emptyList()
        app.awaitUntil("the app opens the wake screen") {
            forwarded = forwarded + startedActivities(app)
            forwarded.isNotEmpty()
        }

        assertEquals(listOf(ComponentName(app.app, WakeActivity::class.java)), forwarded.map { it.component })
        app.awaitUntil("the main looper settles") { true }
        assertEquals(emptyList(), startedActivities(app), "once per resume")
        // Back in front later (Recents, the launcher): it hands over again.
        main.pause().resume()
        app.awaitUntil("the app opens the wake screen again") { startedActivities(app).isNotEmpty() }
    }

    @Test
    fun `opening the app with no session shows the app and never opens the wake screen`() {
        val app = WakeApp()

        Robolectric.buildActivity(MainActivity::class.java).setup()
        app.awaitUntil("the main looper settles") { true }

        assertEquals(emptyList(), startedActivities(app).filter(::isWakeScreen))
    }
}
