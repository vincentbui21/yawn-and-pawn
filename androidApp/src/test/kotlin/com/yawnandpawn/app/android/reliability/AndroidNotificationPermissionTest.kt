package com.yawnandpawn.app.android.reliability

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.testing.FakeLogger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 1.19: the once-only notification permission request (the "Fix" deep links are in AndroidReliabilitySettingsTest). */
@RunWith(RobolectricTestRunner::class)
class AndroidNotificationPermissionTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val logger = FakeLogger()

    @Test
    @Config(sdk = [33])
    fun `an old screen stopping keeps the newer screen's launcher, and a launch that throws is asked again`() {
        val permission = AndroidNotificationPermission(app, logger)
        val failing: () -> Unit = { throw IllegalStateException("not attached to an activity") }
        permission.attach(failing)
        assertFailsWith<IllegalStateException> { permission.request() }
        assertTrue(permission.shouldRequest(), "not remembered as asked")

        var newer = 0
        val newLaunch: () -> Unit = { newer++ }
        permission.attach(newLaunch)
        permission.detach(failing)
        permission.request()

        assertEquals(1, newer, "the newer screen still launches")
        assertFalse(permission.shouldRequest())
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
