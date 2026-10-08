package com.yawnandpawn.app.android.wake

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.buildActivity
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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

    private fun store(app: WakeApp): ActiveSessionStore = app.koin.get()

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
        val screen = buildActivity(WakeActivity::class.java).setup()
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
    fun `no heads-up over the visible wake screen, and one again when it is left (Epic 3 device check)`() {
        val app = WakeApp()
        val service = ring(app)
        assertEquals(WakeNotifier.CHANNEL_ID, assertNotNull(posted(app)).channelId, "the ring starts on the Alarms channel")

        val screen = buildActivity(WakeActivity::class.java).setup()
        val onScreen = assertNotNull(posted(app))
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, onScreen.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager(app).getNotificationChannel(onScreen.channelId).importance)
        assertNull(onScreen.fullScreenIntent)
        // A slot fire while it is visible (the grace end, the heartbeat) posts the quiet one too.
        service.withIntent(WakeService.intent(app.app, WakeService.ACTION_SLOT)).startCommand(0, 2)
        app.awaitUntil("the slot start is handled") { true }
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, assertNotNull(posted(app)).channelId)

        screen.pause().stop()

        val away = assertNotNull(posted(app))
        assertEquals(WakeNotifier.CHANNEL_ID, away.channelId, "high importance again: it heads up as the way back")
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
        screen.restart().resume()
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, assertNotNull(posted(app)).channelId)
        service.destroy()
    }

    @Test
    fun `a recreate of the visible wake screen while ringing posts nothing, so no heads-up comes back (PR 41 review)`() {
        val app = WakeApp()
        ring(app)
        val screen = buildActivity(WakeActivity::class.java).setup()
        val quiet = assertNotNull(posted(app))
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, quiet.channelId)

        screen.recreate()
        app.awaitUntil("the main looper settles") { true }

        assertSame(quiet, posted(app), "not posted again: no Alarms-channel post in between, nothing for the rate limit")
        // Still counted as visible once: leaving it now posts the way back.
        screen.pause().stop()
        assertEquals(WakeNotifier.CHANNEL_ID, assertNotNull(posted(app)).channelId)
    }

    @Test
    fun `left during quiet time, the grace end posts the full-screen intent again (PR 41 review)`() {
        val clock = FakeClock(Instant.parse("2027-03-08T06:00:10Z"))
        val monotonic = FakeMonotonicClock(elapsedMillis = 5_000_000)
        val app = WakeApp(clock = clock, monotonic = monotonic)
        val service = ring(app)
        val screen = buildActivity(WakeActivity::class.java).setup()
        app.dispatch(SessionEvent.UserInteracted, SessionEvent.ImUpTapped)
        assertIs<SessionState.Grace>(app.engine.state.value)

        // The power key: the screen stops; the way back heads up without bringing the screen straight back.
        screen.pause().stop()
        val away = assertNotNull(posted(app))
        assertEquals(WakeNotifier.CHANNEL_ID, away.channelId)
        assertNull(away.fullScreenIntent)

        clock.advanceBy(21.seconds)
        monotonic.advanceBy(21.seconds)
        val tick = app.koin.get<ApplicationScope>().launch { app.engine.tick() }
        app.awaitUntil("the grace window ends") { tick.isCompleted && app.engine.state.value is SessionState.Loud }

        val loud = assertNotNull(posted(app))
        assertEquals(WakeNotifier.CHANNEL_ID, loud.channelId)
        assertNotNull(loud.fullScreenIntent, "the loud ring can turn the screen on again")
        service.destroy()
    }

    @Test
    fun `left during an emergency ring, the way back heads up and its backup slot posts the full-screen intent (PR 41 review)`() {
        val app = WakeApp()
        app.runtime.startEmergency(fired.scheduledAt, volumePercent = 80, cause = "test")
        val screen = buildActivity(WakeActivity::class.java).setup()
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, assertNotNull(posted(app)).channelId)

        screen.pause().stop()
        val away = assertNotNull(posted(app))
        assertEquals(WakeNotifier.CHANNEL_ID, away.channelId, "the way back heads up")
        assertNull(away.fullScreenIntent)

        val service = app.startService(WakeService.intent(app.app, WakeService.ACTION_SLOT))
        app.awaitUntil("the slot start is handled") { true }
        assertNotNull(assertNotNull(posted(app)).fullScreenIntent)
        service.destroy()
    }

    @Test
    fun `left during a ring, an emergency ring that starts posts the full-screen intent again (PR 41 review)`() {
        val app = WakeApp()
        val service = ring(app)
        val screen = buildActivity(WakeActivity::class.java).setup()
        screen.pause().stop()
        assertNull(assertNotNull(posted(app)).fullScreenIntent)

        app.runtime.startEmergency(fired.scheduledAt + 1.minutes, volumePercent = 80, cause = "test")

        assertNotNull(assertNotNull(posted(app)).fullScreenIntent)
        app.runtime.stopEmergency()
        service.destroy()
    }

    @Test
    fun `a wake screen left while Idle does not stop the next ring's full notification (PR 41 review)`() {
        val app = WakeApp()
        val screen = buildActivity(WakeActivity::class.java).setup()
        screen.pause().stop()

        val service = ring(app)

        val full = assertNotNull(posted(app))
        assertEquals(WakeNotifier.CHANNEL_ID, full.channelId)
        assertNotNull(full.fullScreenIntent)
        service.destroy()
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
        val screen = buildActivity(WakeActivity::class.java).setup()
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
    fun `a delete intent that starts a new process restores the ringing session and brings the notification back`() {
        val app = WakeApp()
        val stored = SessionState.Ringing(aSession())
        assertEquals(Outcome.Success(Unit), runBlocking { store(app).commit(stored) })

        // A new process: the engine is still Idle when the repost start arrives.
        val service = app.startService(WakeService.repostIntent(app.app))
        app.awaitRinging()

        assertEquals(stored.session.sessionId, assertIs<SessionState.Ringing>(app.engine.state.value).session.sessionId)
        val back = assertNotNull(posted(app), "the notification is back")
        assertEquals(WakeNotifier.CHANNEL_ID, back.channelId)
        assertNotNull(back.fullScreenIntent)
        assertFalse(shadowOf(service.get()).isStoppedBySelf, "the service keeps running")
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
    }

    @Test
    fun `a notification swiped during a snooze comes back without its full-screen intent`() {
        val app = WakeApp()
        val snoozed =
            SessionState.Snoozed(
                aSession().copy(interactionDeadline = null, snoozeEnd = Deadline.after(app.now(), 9.minutes), snoozesGranted = 1),
            )
        assertEquals(Outcome.Success(Unit), runBlocking { store(app).commit(snoozed) })
        val service = app.startService(WakeService.intent(app.app, WakeService.ACTION_RESTORE))
        app.awaitUntil("the snooze is restored") { app.engine.state.value is SessionState.Snoozed }
        // The ringing notification of the ring before the snooze is still up.
        app.koin.get<WakeNotifier>().show(snoozed.session.config.scheduledAt)
        val notification = assertNotNull(posted(app))
        startedActivities(app)

        manager(app).cancel(WakeNotifier.NOTIFICATION_ID)
        notification.deleteIntent.send()
        service.withIntent(assertNotNull(shadowOf(app.app).nextStartedService)).startCommand(0, 2)
        app.awaitUntil("the main looper settles") { true }

        val back = shadowOf(service.get()).lastForegroundNotification
        assertNull(back.fullScreenIntent, "nothing opens the wake screen during a snooze")
        assertEquals(ComponentName(app.app, WakeActivity::class.java), shadowOf(back.contentIntent).savedIntent.component)
        assertEquals(snoozed.session.sessionId, assertIs<SessionState.Snoozed>(app.engine.state.value).session.sessionId)
        assertFalse(shadowOf(service.get()).isStoppedBySelf, "the snooze goes on")
        assertEquals(emptyList(), startedActivities(app).filter(::isWakeScreen))
    }

    @Test
    fun `a delete intent that arrives after the session ended starts quietly and stops the service`() {
        val app = WakeApp()
        val service = ring(app)
        val notification = assertNotNull(posted(app))
        app.dispatch(SessionEvent.ImUpTapped)
        app.solveCheck()
        app.awaitUntil("the session ends") { app.engine.state.value == SessionState.Idle && shadowOf(service.get()).isStoppedBySelf }

        notification.deleteIntent.send()
        val repost = generateSequence { shadowOf(app.app).nextStartedService }.last()
        assertEquals(WakeService.ACTION_REPOST, repost.action)
        val late = app.startService(repost)
        app.awaitUntil("the late start stops") { shadowOf(late.get()).isStoppedBySelf }

        // Its foreground notification was the quiet one (nothing was shown any more), removed again by the stop.
        assertNull(app.runtime.shownAlarmAt(), "no ringing notification was posted")
        assertNull(posted(app), "no notification is left")
        assertEquals(SessionState.Idle, app.engine.state.value)
        assertNull(app.player.sound)
    }

    @Test
    fun `a swiped notification its repost start could not bring back is posted again at the next heartbeat`() {
        val app = WakeApp()
        ring(app)

        // The swipe's repost start forgets the notification first; here it never reaches the foreground.
        manager(app).cancel(WakeNotifier.NOTIFICATION_ID)
        app.runtime.notificationSwiped()
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

        val main = buildActivity(MainActivity::class.java).setup()
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
    fun `opening the app during an emergency ring hands over to the wake screen`() {
        val app = WakeApp()
        app.runtime.startEmergency(fired.scheduledAt, volumePercent = 80, cause = "test")
        assertEquals(SessionState.Idle, app.engine.state.value)
        startedActivities(app)

        buildActivity(MainActivity::class.java).setup()

        app.awaitUntil("the app opens the wake screen") { startedActivities(app).any(::isWakeScreen) }
    }

    @Test
    fun `a ring that starts while the app is open hands over to the wake screen`() {
        val app = WakeApp()
        buildActivity(MainActivity::class.java).setup()
        app.awaitUntil("the main looper settles") { true }
        assertEquals(emptyList(), startedActivities(app).filter(::isWakeScreen))

        ring(app)
        var forwarded: List<Intent> = emptyList()
        app.awaitUntil("the app opens the wake screen") {
            forwarded = forwarded + startedActivities(app).filter(::isWakeScreen)
            forwarded.isNotEmpty()
        }
        app.awaitUntil("the main looper settles") { true }

        assertEquals(1, (forwarded + startedActivities(app).filter(::isWakeScreen)).size, "exactly one wake screen start")
    }

    @Test
    fun `opening the app with no session shows the app and never opens the wake screen`() {
        val app = WakeApp()

        buildActivity(MainActivity::class.java).setup()
        app.awaitUntil("the main looper settles") { true }

        assertEquals(emptyList(), startedActivities(app).filter(::isWakeScreen))
    }
}
