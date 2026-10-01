package com.yawnandpawn.app.android.reliability

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.reliability.ReliabilityItem
import com.yawnandpawn.app.testing.FakeLogger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 1.19: "Fix" deep links and the once-only notification permission request. */
@RunWith(RobolectricTestRunner::class)
class ReliabilitySettingsTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val logger = FakeLogger()
    private val packageUri = "package:${app.packageName}"

    @Test
    @Config(sdk = [34])
    fun `each item opens its own settings screen as a new task`() {
        val settings = AndroidReliabilitySettings(app, logger)

        val notifications = settings.intentFor(ReliabilityItem.Notifications)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifications.action)
        assertEquals(app.packageName, notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        val fullScreen = settings.intentFor(ReliabilityItem.FullScreenIntent)
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, fullScreen.action)
        assertEquals(packageUri, fullScreen.dataString)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, settings.intentFor(ReliabilityItem.ExactAlarms).action)

        settings.open(ReliabilityItem.FullScreenIntent)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    @Config(sdk = [31])
    fun `on API 31 exact alarms have their screen and the full-screen intent falls back to the app details`() {
        val settings = AndroidReliabilitySettings(app, logger)

        assertEquals(packageUri, settings.intentFor(ReliabilityItem.ExactAlarms).dataString)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, settings.intentFor(ReliabilityItem.ExactAlarms).action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.intentFor(ReliabilityItem.FullScreenIntent).action)
    }

    @Test
    @Config(sdk = [33])
    fun `API 33 asks for notifications once, only while a screen is in front`() {
        val permission = AndroidNotificationPermission(app, logger)
        var launches = 0

        assertTrue(permission.shouldRequest())
        permission.request()
        assertEquals(0, launches)
        assertTrue(permission.shouldRequest(), "nothing in front: not asked, so asked later")

        permission.attach { launches++ }
        permission.request()

        assertEquals(1, launches)
        assertFalse(permission.shouldRequest(), "asked once")
        assertFalse(AndroidNotificationPermission(app, logger).shouldRequest(), "remembered")
    }

    @Test
    @Config(sdk = [33])
    fun `a granted notification permission is never asked for`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(AndroidNotificationPermission(app, logger).shouldRequest())
    }

    @Test
    @Config(sdk = [32])
    fun `below API 33 there is no notification permission to ask for`() {
        assertFalse(AndroidNotificationPermission(app, logger).shouldRequest())
    }
}
