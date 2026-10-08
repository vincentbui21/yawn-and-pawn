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
import kotlin.test.assertTrue

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

    private fun posted(): Notification = assertNotNull(shadowOf(manager).getNotification(WakeNotifier.NOTIFICATION_ID))

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

        val foreground = notifier.build(sevenAm)
        notifier.shownByService(sevenAm)
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, foreground.channelId)
        notifier.show(sevenAm)
        assertEquals(0, shadowOf(manager).size(), "the service's post is the quiet one already: nothing posted again")
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
