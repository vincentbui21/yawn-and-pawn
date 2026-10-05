package com.yawnandpawn.app.android

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.wake.WakeService
import com.yawnandpawn.app.stopApp
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AD-4 receivers and the permissions in the merged manifest (Stories 1.10 and 1.14). */
@RunWith(RobolectricTestRunner::class)
class ReceiversManifestTest {
    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()
    private val packageManager = app.packageManager

    @After
    fun tearDown() {
        stopApp()
    }

    private fun receiver(name: Class<*>) = packageManager.getReceiverInfo(ComponentName(app, name), 0)

    private fun receiversFor(action: String): List<String> =
        packageManager
            .queryBroadcastReceivers(Intent(action).setPackage(app.packageName), 0)
            .map { it.activityInfo.name }

    @Test
    fun `the alarm receiver is direct-boot aware, not exported and has no intent filter`() {
        val info = receiver(AlarmFiredReceiver::class.java)

        assertTrue(info.directBootAware, "directBootAware")
        assertFalse(info.exported, "exported")
        listOf(AlarmFiredReceiver.ACTION_ALARM, AlarmFiredReceiver.ACTION_TEST_ALARM)
            .forEach { action -> assertEquals(emptyList(), receiversFor(action), "no implicit $action") }
    }

    @Test
    fun `the session slot receiver is direct-boot aware, not exported and has no intent filter`() {
        val info = receiver(SessionSlotReceiver::class.java)

        assertTrue(info.directBootAware, "directBootAware")
        assertFalse(info.exported, "exported")
        assertEquals(emptyList(), receiversFor(SessionSlotReceiver.ACTION_SESSION_SLOT), "no implicit slot action")
    }

    @Test
    fun `the wake service does not stop with its task, so a swipe from Recents keeps the session`() {
        val info = packageManager.getServiceInfo(ComponentName(app, WakeService::class.java), 0)

        assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK, "stopWithTask is never set")
        assertTrue(info.directBootAware, "directBootAware")
        assertFalse(info.exported, "exported")
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("stopWithTask"), "no stopWithTask attribute")
    }

    @Test
    fun `the system events receiver is direct-boot aware, exported and listens to exactly the reschedule actions`() {
        val info = receiver(SystemEventsReceiver::class.java)

        assertTrue(info.directBootAware, "directBootAware")
        assertTrue(info.exported, "exported for system broadcasts")
        val expected =
            setOf(
                "android.intent.action.LOCKED_BOOT_COMPLETED",
                "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.TIME_SET",
                "android.intent.action.TIMEZONE_CHANGED",
                "android.intent.action.MY_PACKAGE_REPLACED",
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            )
        assertEquals(expected, SystemEventsReceiver.ACTIONS)
        expected.forEach { action ->
            assertEquals(listOf(SystemEventsReceiver::class.java.name), receiversFor(action), action)
        }
    }

    private fun requestedPermissions(): Set<String> =
        packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()

    @Test
    fun `on API 34 the manifest requests USE_EXACT_ALARM and the boot permission, and SCHEDULE_EXACT_ALARM stops at API 32`() {
        val requested = requestedPermissions()

        assertTrue(requested.contains("android.permission.USE_EXACT_ALARM"), "requested: $requested")
        assertTrue(requested.contains("android.permission.RECEIVE_BOOT_COMPLETED"), "requested: $requested")
        assertFalse(requested.contains("android.permission.SCHEDULE_EXACT_ALARM"), "maxSdkVersion 32 drops it on API 34")
        // Tests run in :androidApp; the permission allowlist check enforces the same in the merged manifests.
        val manifest = File("src/main/AndroidManifest.xml").readText().replace(Regex("\\s+"), " ")
        assertTrue(
            manifest.contains("android:name=\"android.permission.SCHEDULE_EXACT_ALARM\" android:maxSdkVersion=\"32\""),
            "SCHEDULE_EXACT_ALARM must stop at API 32",
        )
    }

    @Test
    fun `the manifest requests the wake runtime permissions`() {
        val requested = requestedPermissions()

        listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.USE_FULL_SCREEN_INTENT",
            "android.permission.WAKE_LOCK",
            "android.permission.VIBRATE",
            "android.permission.POST_NOTIFICATIONS",
        ).forEach { assertTrue(requested.contains(it), "$it requested: $requested") }
    }

    @Test
    @Config(sdk = [32])
    fun `on API 32 the manifest requests SCHEDULE_EXACT_ALARM`() {
        assertTrue(requestedPermissions().contains("android.permission.SCHEDULE_EXACT_ALARM"))
    }
}
