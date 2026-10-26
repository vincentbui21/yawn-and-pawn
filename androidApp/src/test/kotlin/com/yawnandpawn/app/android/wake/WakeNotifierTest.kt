package com.yawnandpawn.app.android.wake

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.R
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.ui.theme.PpsTokens
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Story 1.14: the ringing notification, its channel, its strings and its colour. */
@RunWith(RobolectricTestRunner::class)
class WakeNotifierTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val berlin = TimeZone.of("Europe/Berlin")
    private val notifier = WakeNotifier(context, FakeTimeZoneProvider(berlin))
    private val sevenAm = LocalDateTime.parse("2027-03-08T07:00").toInstant(berlin)

    private fun opensWakeActivity(notification: Notification) {
        listOf(notification.fullScreenIntent, notification.contentIntent).forEach { pending ->
            val shadow = shadowOf(assertNotNull(pending))
            assertTrue(shadow.isActivity, "an activity intent")
            assertTrue(shadow.isImmutable, "immutable")
            assertEquals(ComponentName(context, WakeActivity::class.java), shadow.savedIntent.component)
        }
    }

    @Test
    fun `the notification is an ongoing public alarm on the high-importance Alarms channel that returns to the wake screen`() {
        val notification = notifier.build(sevenAm)

        val channel = assertNotNull(manager.getNotificationChannel(WakeNotifier.CHANNEL_ID))
        assertEquals("alarms", channel.id)
        assertEquals("Alarms", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound, "the player rings, the channel stays quiet")
        assertEquals("alarms", notification.channelId)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0, "ongoing")
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL == 0, "not auto-cancel")
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals(R.drawable.ic_stat_alarm, notification.smallIcon.resId)
        assertEquals(context.getColor(R.color.notification_accent), notification.color)
        assertTrue(notification.actions.isNullOrEmpty(), "no action stops the sound")
        opensWakeActivity(notification)
    }

    @Test
    fun `the title is the alarm time in the 12-hour format and the text the EXPERIENCE copy`() {
        val notification = notifier.build(sevenAm)

        assertEquals("7:00 AM", shadowOf(notification).contentTitle)
        assertEquals("7:00 AM alarm · Tap to return to your alarm", shadowOf(notification).contentText)
    }

    @Test
    fun `with the 24-hour setting the title and text use the 24-hour time`() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")

        val notification = notifier.build(sevenAm)

        assertEquals("07:00", shadowOf(notification).contentTitle)
        assertEquals("07:00 alarm · Tap to return to your alarm", shadowOf(notification).contentText)
    }

    @Test
    fun `show posts once per alarm time and cancel removes it`() {
        notifier.show(sevenAm)
        notifier.show(sevenAm)

        assertEquals(1, shadowOf(manager).size())
        assertEquals(sevenAm, notifier.shownFor)
        notifier.cancel()
        assertEquals(0, shadowOf(manager).size())
        assertNull(notifier.shownFor)
    }

    private fun posted(): Notification = assertNotNull(shadowOf(manager).getNotification(notifier.currentId))

    private fun importanceOf(notification: Notification): Int =
        assertNotNull(manager.getNotificationChannel(notification.channelId)).importance

    /** The same notification, whatever its channel: the time, the way back, no action. */
    private fun assertWayBack(notification: Notification) {
        assertEquals("7:00 AM alarm · Tap to return to your alarm", shadowOf(notification).contentText)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0, "ongoing")
        assertNotNull(notification.contentIntent, "a tap returns to the wake screen")
        assertNotNull(notification.deleteIntent, "a swipe posts it again")
        assertTrue(notification.actions.isNullOrEmpty())
    }

    @Test
    fun `while the wake screen is visible the notification is quiet, so no heads-up covers the countdown (Epic 3 device check)`() {
        notifier.show(sevenAm)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, importanceOf(posted()))

        notifier.wakeScreenShown(visible = true, ringing = true)

        val quiet = posted()
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, quiet.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, importanceOf(quiet), "low importance never heads up")
        assertNull(quiet.fullScreenIntent, "no full-screen intent over a visible wake screen")
        assertWayBack(quiet)
        assertEquals(1, shadowOf(manager).size())
        // Every later post while it is visible stays quiet: a step's WakeUiShown and a slot fire's startForeground.
        notifier.show(sevenAm)
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, posted().channelId)
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, notifier.build(sevenAm).channelId)
        assertNull(notifier.build(sevenAm).fullScreenIntent)
    }

    @Test
    fun `leaving the wake screen while the alarm rings posts the heads-up notification again (Epic 3 device check)`() {
        notifier.show(sevenAm)
        notifier.wakeScreenShown(visible = true, ringing = true)

        notifier.wakeScreenShown(visible = false, ringing = true)

        val back = posted()
        assertEquals(WakeNotifier.CHANNEL_ID, back.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, importanceOf(back), "it heads up as the way back")
        assertNull(back.fullScreenIntent, "leaving with the power key does not bring the screen straight back")
        assertWayBack(back)
        assertEquals(sevenAm, notifier.shownFor)
        // A slot fire's startForeground posts the full one again, as before.
        assertNotNull(notifier.build(sevenAm).fullScreenIntent)
        assertEquals(WakeNotifier.CHANNEL_ID, notifier.build(sevenAm).channelId)
    }

    @Test
    fun `a screen that closes itself just as the snooze ends posts the full-screen ring at once (Story 4_15 review)`() {
        // The Snoozed note is up, its close has started, and the snooze end's ring step finds the screen still shown.
        notifier.wakeScreenShown(visible = true, ringing = false)
        notifier.show(sevenAm)
        assertNull(posted().fullScreenIntent, "quiet while the note shows")

        notifier.wakeScreenShown(visible = false, ringing = true, finishing = true)

        val ring = posted()
        assertEquals(WakeNotifier.CHANNEL_ID, ring.channelId)
        opensWakeActivity(ring)
        notifier.show(sevenAm)
        assertSame(ring, posted(), "the next step posts nothing new")
    }

    @Test
    fun `a second wake screen started before the first stopped keeps it quiet (PR 41 review)`() {
        notifier.show(sevenAm)
        notifier.wakeScreenShown(visible = true, ringing = true) // A
        notifier.wakeScreenShown(visible = true, ringing = true) // B starts before A stops

        notifier.wakeScreenShown(visible = false, ringing = true) // A stops

        assertTrue(notifier.screenShown)
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, posted().channelId)
        notifier.wakeScreenShown(visible = false, ringing = true) // B stops: now it is left
        assertEquals(WakeNotifier.CHANNEL_ID, posted().channelId)
    }

    @Test
    fun `a stop for a configuration change is not leaving, and the recreated screen keeps it quiet (PR 41 review)`() {
        notifier.show(sevenAm)
        notifier.wakeScreenShown(visible = true, ringing = true)
        val quiet = posted()

        notifier.wakeScreenShown(visible = false, ringing = true, changingConfigurations = true)
        assertTrue(notifier.screenShown, "still visible until the new instance starts")
        notifier.show(sevenAm) // A step between the stop and the start.
        notifier.wakeScreenShown(visible = true, ringing = true)

        assertSame(quiet, posted(), "nothing posted")
        notifier.wakeScreenShown(visible = false, ringing = true)
        assertEquals(WakeNotifier.CHANNEL_ID, posted().channelId)
    }

    @Test
    fun `after leaving, the next ring step posts the full-screen intent again (PR 41 review)`() {
        notifier.show(sevenAm)
        notifier.wakeScreenShown(visible = true, ringing = true)
        notifier.wakeScreenShown(visible = false, ringing = true)
        assertNull(posted().fullScreenIntent)

        notifier.show(sevenAm)

        assertNotNull(posted().fullScreenIntent)
        assertEquals(WakeNotifier.CHANNEL_ID, posted().channelId)
        val full = posted()
        notifier.show(sevenAm)
        assertSame(full, posted(), "then once per alarm time again")
    }

    @Test
    fun `leaving in a snooze keeps it quiet, and the next ring posts the full ringing one (Epic 3 device check)`() {
        notifier.show(sevenAm)
        notifier.wakeScreenShown(visible = true, ringing = false)

        notifier.wakeScreenShown(visible = false, ringing = false)
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, posted().channelId)

        notifier.show(sevenAm)
        assertEquals(WakeNotifier.CHANNEL_ID, posted().channelId)
        assertNotNull(posted().fullScreenIntent)
    }

    @Test
    fun `a ring that starts while the wake screen is visible posts the quiet one (Epic 3 device check)`() {
        notifier.wakeScreenShown(visible = true, ringing = false)
        assertEquals(0, shadowOf(manager).size(), "nothing to change before a notification is posted")

        val service = FakeForegroundHost()
        notifier.postForeground(service, sevenAm, ringing = true)
        assertEquals(
            WakeNotifier.QUIET_CHANNEL_ID,
            service.posts
                .single()
                .second.channelId,
        )
        notifier.show(sevenAm)
        assertEquals(1, service.posts.size, "the service's post is the quiet one already: nothing posted again")
        assertEquals(0, shadowOf(manager).size())
    }

    /** The wake service's `startForeground`, recorded: each (id, notification) in order; refused while [refuse]. */
    private class FakeForegroundHost : WakeNotifier.ForegroundHost {
        val posts = mutableListOf<Pair<Int, Notification>>()
        var refuse = false

        override fun startForeground(
            id: Int,
            notification: Notification,
        ) {
            if (refuse) throw IllegalStateException("startForeground not allowed")
            posts += id to notification
        }
    }

    @Test
    fun `the full ringing one over the service's quiet start is a new post, and the foreground moves to it (Story 5_13)`() {
        val service = FakeForegroundHost()
        notifier.postForeground(service, alarmAt = null, ringing = false)
        val (quietId, quiet) = service.posts.single()
        assertEquals(WakeNotifier.NOTIFICATION_ID, quietId)
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, quiet.channelId)

        notifier.show(sevenAm)

        val (id, moved) = service.posts.last()
        assertEquals(WakeNotifier.OTHER_NOTIFICATION_ID, id, "under the other id: a new post, not an update of the quiet one")
        assertNotNull(moved.fullScreenIntent)
        assertEquals(WakeNotifier.OTHER_NOTIFICATION_ID, notifier.currentId)
        assertNotNull(posted().fullScreenIntent)
        assertNull(shadowOf(manager).getNotification(WakeNotifier.NOTIFICATION_ID), "the quiet one is removed")
    }

    @Test
    fun `a test start whose first post rings shows the full-screen intent at once, and its WakeUiShown posts nothing more`() {
        val service = FakeForegroundHost()

        notifier.postForeground(service, sevenAm, ringing = true)
        notifier.show(sevenAm)

        val (id, first) = service.posts.single()
        assertEquals(WakeNotifier.NOTIFICATION_ID, id)
        assertEquals(WakeNotifier.CHANNEL_ID, first.channelId)
        assertNotNull(first.fullScreenIntent)
        assertEquals(0, shadowOf(manager).size(), "nothing posted again")
    }

    @Test
    fun `a content change of the full one stays an update under the same id`() {
        notifier.show(sevenAm)
        val id = notifier.currentId

        notifier.show(sevenAm + 1.minutes)

        assertEquals(id, notifier.currentId, "same id: only the time changed, the full-screen intent was already there")
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun `a slot start in a snooze keeps the quiet on-screen one, so nothing opens the wake screen before the re-ring`() {
        val service = FakeForegroundHost()
        notifier.postForeground(service, sevenAm, ringing = true)
        notifier.wakeScreenShown(visible = true, ringing = false)
        notifier.wakeScreenShown(visible = false, ringing = false)

        notifier.postForeground(service, sevenAm, ringing = false)

        assertEquals(
            WakeNotifier.QUIET_CHANNEL_ID,
            service.posts
                .last()
                .second.channelId,
        )
        assertNull(
            service.posts
                .last()
                .second.fullScreenIntent,
        )
        // The snooze end's ring step: a new post with the full-screen intent.
        val before = notifier.currentId
        notifier.show(sevenAm)
        assertTrue(notifier.currentId != before, "a new post")
        assertNotNull(posted().fullScreenIntent)
    }

    @Test
    fun `a move the platform refuses keeps the new full post, and every next new post goes under a free third id`() {
        val service = FakeForegroundHost()
        notifier.postForeground(service, sevenAm, ringing = true)
        notifier.wakeScreenShown(visible = true, ringing = true)
        notifier.wakeScreenShown(visible = false, ringing = true)
        service.refuse = true

        notifier.show(sevenAm)

        assertEquals(WakeNotifier.OTHER_NOTIFICATION_ID, notifier.currentId, "a new post all the same")
        assertNotNull(posted().fullScreenIntent)
        assertNotNull(shadowOf(manager).getNotification(WakeNotifier.NOTIFICATION_ID), "the service's own stays: the platform keeps it")

        // Left and back again: neither the service's id nor the current one; never a cancel and post again (PR #95 review).
        notifier.wakeScreenShown(visible = true, ringing = true)
        notifier.wakeScreenShown(visible = false, ringing = true)
        assertNull(posted().fullScreenIntent)
        notifier.show(sevenAm)
        assertEquals(WakeNotifier.THIRD_NOTIFICATION_ID, notifier.currentId)
        assertNotNull(posted().fullScreenIntent)
        assertNull(shadowOf(manager).getNotification(WakeNotifier.OTHER_NOTIFICATION_ID), "the old new post is removed")

        // Once the platform lets the foreground move, everything is back to one notification.
        service.refuse = false
        notifier.wakeScreenShown(visible = true, ringing = true)
        notifier.wakeScreenShown(visible = false, ringing = true)
        notifier.show(sevenAm)
        assertEquals(WakeNotifier.OTHER_NOTIFICATION_ID, notifier.currentId)
        assertEquals(WakeNotifier.OTHER_NOTIFICATION_ID, service.posts.last().first, "the foreground moved")
        assertEquals(1, shadowOf(manager).size())
        assertNotNull(posted().fullScreenIntent)
    }

    @Test
    fun `without the wake service a new post goes under another id, and the old one is removed`() {
        notifier.show(sevenAm)
        notifier.wakeScreenShown(visible = true, ringing = true)
        notifier.wakeScreenShown(visible = false, ringing = true)

        notifier.show(sevenAm)

        assertEquals(WakeNotifier.OTHER_NOTIFICATION_ID, notifier.currentId)
        assertNotNull(posted().fullScreenIntent)
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun `cancel removes the notification under every id`() {
        val service = FakeForegroundHost()
        notifier.postForeground(service, alarmAt = null, ringing = false)
        notifier.show(sevenAm)
        assertEquals(WakeNotifier.OTHER_NOTIFICATION_ID, notifier.currentId)

        notifier.cancel()

        assertEquals(0, shadowOf(manager).size())
        assertNull(notifier.shownFor)
    }

    @Test
    fun `the notification colour resource equals the generated DESIGN accent token`() {
        assertEquals(PpsTokens.Light.accent.toArgb(), context.getColor(R.color.notification_accent))
    }

    @Test
    fun `the notification strings are copied from EXPERIENCE verbatim`() {
        val experience =
            File("../_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md").readText()
        val channel = context.getString(R.string.notification_channel_alarms)
        val text = context.getString(R.string.notification_ringing_text, "{time}")

        assertTrue(experience.contains("| Notification channel | \"$channel\" |"), channel)
        assertTrue(experience.contains("| Ringing notification | \"$text\" |"), text)
    }
}
