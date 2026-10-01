package com.yawnandpawn.app.android.reliability

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals

/** Story 1.19: the reliability probe on API 26, 31, 32, 33, 34 and 36. */
@RunWith(RobolectricTestRunner::class)
class ReliabilityProbeTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))

    // Robolectric has no shadow for the API 34+ grant: the seam stands in for NotificationManager.canUseFullScreenIntent().
    private var fullScreenGranted = true
    private var fullScreenAsked = 0
    private val probe =
        AndroidReliabilityProbe(context, canUseFullScreenIntent = {
            fullScreenAsked++
            fullScreenGranted
        })

    /** Every grant the probe could read is off. */
    private fun allOff() {
        notifications.setNotificationsEnabled(false)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
    }

    private fun status(
        notifications: Boolean,
        fullScreen: Boolean,
        exact: Boolean,
    ) = ReliabilityStatus(notificationsAllowed = notifications, fullScreenIntentAllowed = fullScreen, exactAlarmsAllowed = exact)

    @Test
    @Config(sdk = [26])
    fun `API 26 reads only notifications`() {
        assertEquals(ReliabilityStatus.ALL_OK, probe.check())
        allOff()
        assertEquals(status(notifications = false, fullScreen = true, exact = true), probe.check())
    }

    @Test
    @Config(sdk = [31])
    fun `API 31 reads notifications and exact alarms`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertEquals(ReliabilityStatus.ALL_OK, probe.check())
        allOff()
        assertEquals(status(notifications = false, fullScreen = true, exact = false), probe.check())
    }

    @Test
    @Config(sdk = [32])
    fun `API 32 reads notifications and exact alarms`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(status(notifications = true, fullScreen = true, exact = false), probe.check())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertEquals(ReliabilityStatus.ALL_OK, probe.check())
    }

    @Test
    @Config(sdk = [33])
    fun `API 33 has USE_EXACT_ALARM, so exact alarms are always allowed`() {
        allOff()
        assertEquals(status(notifications = false, fullScreen = true, exact = true), probe.check())
        assertEquals(false, context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(), "ignored on 33+")
        assertEquals(0, fullScreenAsked, "no full-screen grant to read below 34")
    }

    @Test
    @Config(sdk = [34])
    fun `API 34 reads the full-screen intent grant`() {
        assertEquals(ReliabilityStatus.ALL_OK, probe.check())
        fullScreenGranted = false
        assertEquals(status(notifications = true, fullScreen = false, exact = true), probe.check())
        assertEquals(2, fullScreenAsked)
        // The real platform call works too (it is what production binds).
        AndroidReliabilityProbe(context).check()
    }

    /**
     * Robolectric 4.17 needs Java 21 for SDK 35+ and the toolchain is pinned to 17 (`robolectric.properties`), so the
     * API 36 rules run through the probe's SDK level on the SDK 34 sandbox (34 and 36 use the same calls).
     */
    @Test
    @Config(sdk = [34])
    fun `API 36 reads notifications and the full-screen intent grant`() {
        val api36 =
            AndroidReliabilityProbe(context, sdkInt = 36, canUseFullScreenIntent = {
                fullScreenAsked++
                fullScreenGranted
            })
        assertEquals(ReliabilityStatus.ALL_OK, api36.check())
        allOff()
        fullScreenGranted = false
        assertEquals(status(notifications = false, fullScreen = false, exact = true), api36.check())
    }
}
