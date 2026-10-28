package com.yawnandpawn.app.android.reliability

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.AutomaticZenRule
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.service.notification.ZenPolicy
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.wake.WakeNotifier
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.ChecklistRow
import com.yawnandpawn.app.core.reliability.DeviceInfo
import com.yawnandpawn.app.core.reliability.DeviceInfoSource
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.core.reliability.EvaluateReliability
import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.reliability.ReliabilitySettings
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Story 1.19: the reliability probe on API 26, 31, 32, 33, 34 and 36. Story 5.3: Do Not Disturb, battery and camera on
 * API 26, 28, 30, 31, 33, 34 and 36.
 */
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

    /**
     * Every value starts in its good state (exact alarms too), so the Story 1.19 tests see only their three items. The
     * app never holds `ACCESS_NOTIFICATION_POLICY`, and neither do these tests (review item 8): the shadow sets the
     * filter and the policy without it.
     */
    @Before
    fun goodPhone() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        notifications.setNotificationPolicyAccessGranted(false)
        dnd(NotificationManager.INTERRUPTION_FILTER_ALL, alarmsAllowed = true)
        battery(ignoring = true, restricted = false, bucket = UsageStatsManager.STANDBY_BUCKET_ACTIVE)
        shadowOf(context as Application).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    private fun dnd(
        filter: Int,
        alarmsAllowed: Boolean,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.setInterruptionFilter(filter)
        val categories = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or (if (alarmsAllowed) ALARMS else 0)
        manager.notificationPolicy = NotificationManager.Policy(categories, 0, 0)
    }

    private fun battery(
        ignoring: Boolean,
        restricted: Boolean,
        bucket: Int,
    ) {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, ignoring)
        shadowOf(context.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(restricted)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            shadowOf(context.getSystemService(UsageStatsManager::class.java)).setCurrentAppStandbyBucket(bucket)
        }
    }

    /** Every Story 5.3 setting is off: total silence, battery optimised and restricted, camera and microphone denied. */
    private fun allNewOff() {
        dnd(NotificationManager.INTERRUPTION_FILTER_NONE, alarmsAllowed = false)
        battery(ignoring = false, restricted = true, bucket = UsageStatsManager.STANDBY_BUCKET_RESTRICTED)
        shadowOf(context as Application).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    /**
     * The Story 5.3 values of [status]: DND, battery exempt, background restricted, bucket restricted, camera, and (Story
     * 7.2) the microphone.
     */
    private fun newValues(status: ReliabilityStatus): List<Boolean> =
        listOf(
            status.dndAllowsAlarms,
            status.batteryOptimizationIgnored,
            status.backgroundRestricted,
            status.standbyBucketRestricted,
            status.cameraGranted,
            status.microphoneGranted,
        )

    private val good = newValues(ReliabilityStatus.ALL_OK)

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
    fun `the app's own module binds the real probe (only the test app swaps it)`() {
        val koin =
            koinApplication {
                androidContext(context)
                modules(reliabilityModule(), module { single<Logger> { FakeLogger() } })
            }.koin

        assertIs<AndroidReliabilityProbe>(koin.get<ReliabilityProbe>())
        assertIs<AndroidReliabilitySettings>(koin.get<ReliabilitySettings>())
        assertSame(koin.get<AndroidNotificationPermission>(), koin.get<NotificationPermission>())
        koin.close()
    }

    @Test
    @Config(sdk = [33])
    fun `a blocked Alarms channel counts as notifications off`() {
        alarmsChannel(NotificationManager.IMPORTANCE_NONE)

        assertEquals(status(notifications = false, fullScreen = true, exact = true), probe.check())
    }

    @Test
    @Config(sdk = [33])
    fun `a lowered Alarms channel still shows, so it counts as on`() {
        alarmsChannel(NotificationManager.IMPORTANCE_LOW)

        assertEquals(ReliabilityStatus.ALL_OK, probe.check())
    }

    private fun alarmsChannel(importance: Int) =
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(WakeNotifier.CHANNEL_ID, "Alarms", importance))

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

    @Test
    @Config(sdk = [26])
    fun `API 26 reads DND total silence, battery optimisation and the camera, nothing newer`() {
        assertEquals(good, newValues(probe.check()))

        // Below 28 a priority mode always lets alarms through; background restriction and the bucket do not exist.
        dnd(NotificationManager.INTERRUPTION_FILTER_PRIORITY, alarmsAllowed = false)
        battery(ignoring = false, restricted = true, bucket = UsageStatsManager.STANDBY_BUCKET_RESTRICTED)
        shadowOf(context as Application).denyPermissions(Manifest.permission.CAMERA)
        assertEquals(listOf(true, false, false, false, false, true), newValues(probe.check()))

        dnd(NotificationManager.INTERRUPTION_FILTER_NONE, alarmsAllowed = true)
        assertEquals(false, probe.check().dndAllowsAlarms)
    }

    @Test
    @Config(sdk = [28])
    fun `API 28 reads the DND policy and background restriction, not the restricted bucket`() {
        assertEquals(good, newValues(probe.check()))

        // A policy without alarms blocks them even with DND off now: a scheduled rule turns it on later.
        dnd(NotificationManager.INTERRUPTION_FILTER_ALL, alarmsAllowed = false)
        assertEquals(false, probe.check().dndAllowsAlarms)
        dnd(NotificationManager.INTERRUPTION_FILTER_PRIORITY, alarmsAllowed = false)
        assertEquals(false, probe.check().dndAllowsAlarms)
        dnd(NotificationManager.INTERRUPTION_FILTER_ALARMS, alarmsAllowed = false)
        assertEquals(true, probe.check().dndAllowsAlarms, "alarms only lets alarms through")
        dnd(NotificationManager.INTERRUPTION_FILTER_PRIORITY, alarmsAllowed = true)
        assertEquals(true, probe.check().dndAllowsAlarms)

        allNewOff()
        assertEquals(listOf(false, false, true, false, false, false), newValues(probe.check()))
    }

    @Test
    @Config(sdk = [28])
    fun `no DND policy counts as allowed`() {
        context.getSystemService(NotificationManager::class.java).notificationPolicy = null
        assertEquals(true, probe.check().dndAllowsAlarms)
    }

    @Test
    @Config(sdk = [30])
    fun `API 30 reads every Story 5_3 value, the restricted bucket included`() {
        assertReadsEverything(probe)
        battery(ignoring = true, restricted = false, bucket = UsageStatsManager.STANDBY_BUCKET_RARE)
        assertEquals(false, probe.check().standbyBucketRestricted, "only the restricted bucket counts")
    }

    @Test
    @Config(sdk = [31])
    fun `API 31 reads every Story 5_3 value`() = assertReadsEverything(probe)

    @Test
    @Config(sdk = [33])
    fun `API 33 reads every Story 5_3 value`() = assertReadsEverything(probe)

    @Test
    @Config(sdk = [34])
    fun `API 34 reads every Story 5_3 value`() = assertReadsEverything(probe)

    /** API 36 through the probe's SDK level on the SDK 34 sandbox, as for the Story 1.19 values. */
    @Test
    @Config(sdk = [34])
    fun `API 36 reads every Story 5_3 value`() =
        assertReadsEverything(AndroidReliabilityProbe(context, sdkInt = 36, canUseFullScreenIntent = { true }))

    /**
     * All good reads good; all off reads every value off; and the Story 1.19 items do not change. The
     * DND policy rule holds on this level too (review item 3): with DND off a policy without alarms blocks them, "alarms
     * only" lets them through whatever the policy says, and no policy counts as allowed.
     */
    private fun assertReadsEverything(probe: AndroidReliabilityProbe) {
        assertEquals(ReliabilityStatus.ALL_OK, probe.check())

        allNewOff()

        val status = probe.check()
        assertEquals(listOf(false, false, true, true, false, false), newValues(status))
        assertEquals(false, status.batteryOk)
        val storyOneNineteen = listOf(status.notificationsAllowed, status.fullScreenIntentAllowed, status.exactAlarmsAllowed)
        assertEquals(listOf(true, true, true), storyOneNineteen, "the Story 1.19 items do not change")

        goodPhone()
        dnd(NotificationManager.INTERRUPTION_FILTER_ALL, alarmsAllowed = false)
        assertEquals(false, probe.check().dndAllowsAlarms, "a policy without alarms blocks them")
        dnd(NotificationManager.INTERRUPTION_FILTER_ALARMS, alarmsAllowed = false)
        assertEquals(true, probe.check().dndAllowsAlarms, "alarms only lets alarms through")
        dnd(NotificationManager.INTERRUPTION_FILTER_ALL, alarmsAllowed = true)
        context.getSystemService(NotificationManager::class.java).notificationPolicy = null
        assertEquals(true, probe.check().dndAllowsAlarms, "no policy counts as allowed")
        goodPhone()
    }

    /** Review item 8: the policy read needs no access, and without it a policy without alarms still reads as blocking. */
    @Test
    @Config(sdk = [28])
    fun `API 28 reads the DND policy without notification policy access`() = assertPolicyReadWithoutAccess()

    @Test
    @Config(sdk = [34])
    fun `API 34 reads the DND policy without notification policy access`() = assertPolicyReadWithoutAccess()

    private fun assertPolicyReadWithoutAccess() {
        dnd(NotificationManager.INTERRUPTION_FILTER_ALL, alarmsAllowed = false)
        notifications.setNotificationPolicyAccessGranted(false)

        assertEquals(false, probe.check().dndAllowsAlarms)
    }

    /**
     * Review fix 13, the documented limit: only the default policy is read. A schedule rule with its own `ZenPolicy` that
     * blocks alarms is invisible without `ACCESS_NOTIFICATION_POLICY` (the test grants it only to add the rule, as the
     * Settings app would), so the probe reads "allowed" before and while it runs. Story 5.13 checks this on a phone.
     */
    @Test
    @Config(sdk = [30])
    fun `a schedule rule with its own policy is not seen, only the default policy is (the documented limit)`() {
        notifications.setNotificationPolicyAccessGranted(true)
        val manager = context.getSystemService(NotificationManager::class.java)
        val blocksAlarms = ZenPolicy.Builder().allowAlarms(false).build()
        val rule =
            AutomaticZenRule(
                "Sleep",
                null,
                ComponentName(context.packageName, "Settings"),
                Uri.parse("condition://sleep"),
                blocksAlarms,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                true,
            )
        manager.addAutomaticZenRule(rule)

        assertEquals(true, probe.check().dndAllowsAlarms, "before the rule runs")
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertEquals(true, probe.check().dndAllowsAlarms, "while it runs: the default policy still has alarms")
    }

    /**
     * Review fix 12: the probe runs as Home starts, so a Story 5.3 read that throws is logged and counts as good; Home
     * never crashes. The Story 1.19 reads still work.
     */
    @Test
    @Config(sdk = [34])
    fun `a phone whose Story 5_3 services throw still gets a status, every new value good and each failure logged`() {
        allNewOff()
        val logger = FakeLogger()
        val broken = AndroidReliabilityProbe(BrokenServices(context), canUseFullScreenIntent = { true }, logger = logger)

        assertEquals(ReliabilityStatus.ALL_OK, broken.check())
        assertEquals(
            listOf(
                "Do Not Disturb",
                "battery optimisation",
                "background restriction",
                "standby bucket",
                "camera permission",
                "microphone permission",
            ).map { LogEvent.OperationFailed("read $it", "SecurityException") },
            logger.events,
        )
    }

    /**
     * A context whose Story 5.3 reads throw, as a broken OEM service would: the power, activity and usage stats
     * services, the camera permission check, and the notification service only when the DND read asks for it (the
     * Story 1.19 reads use it too).
     */
    private class BrokenServices(
        base: Context,
    ) : ContextWrapper(base) {
        override fun getSystemService(name: String): Any? =
            when {
                name in setOf(POWER_SERVICE, ACTIVITY_SERVICE, USAGE_STATS_SERVICE) -> throw SecurityException("broken $name")
                name == NOTIFICATION_SERVICE && fromDndRead() -> throw SecurityException("broken $name")
                else -> super.getSystemService(name)
            }

        override fun checkSelfPermission(permission: String): Int =
            if (permission in setOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
                throw SecurityException("broken")
            } else {
                super.checkSelfPermission(permission)
            }

        private fun fromDndRead(): Boolean = Throwable().stackTrace.any { it.methodName == "dndAllowsAlarms" }
    }

    /** Review item 2: the phone's details, read from `Build`; Story 5.5 maps the maker, then the brand. */
    @Test
    fun `the device info source reads the API level, maker and build, and maps the maker or else the brand`() {
        assertEquals(DeviceInfo(31, "X", "f", DeviceMaker.Other), AndroidDeviceInfoSource(31, "X", "f", "X").current())
        assertEquals(DeviceMaker.Xiaomi, AndroidDeviceInfoSource(34, "Xiaomi", "f", "Redmi").current().deviceMaker)
        assertEquals(DeviceMaker.OppoRealme, AndroidDeviceInfoSource(34, "Unknown", "f", "realme").current().deviceMaker)
        assertEquals(DeviceMaker.Huawei, AndroidDeviceInfoSource(34, "HONOR", "f", "HONOR").current().deviceMaker)
        val oppo = AndroidDeviceInfoSource(34, " OPPO ", "f", "OPPO").current()
        assertEquals(" OPPO ", oppo.maker, "the confirmation keeps the raw manufacturer")
        assertEquals(true, oppo.makerCovered)

        val device = GlobalContext.get().get<DeviceInfoSource>().current()
        assertIs<AndroidDeviceInfoSource>(GlobalContext.get().get<DeviceInfoSource>())
        assertEquals(Build.VERSION.SDK_INT, device.sdkInt)
        assertEquals(Build.MANUFACTURER.orEmpty(), device.maker)
        assertEquals(Build.FINGERPRINT.orEmpty(), device.fingerprint)
        assertEquals(DeviceMaker.of(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty()), device.deviceMaker)
    }

    /** Review item 2: the app's graph builds the checklist evaluation over its real records and alarms. */
    @Test
    fun `the app's graph evaluates the checklist, every row in order`() {
        val rows = runBlocking { GlobalContext.get().get<EvaluateReliability>()() }

        assertEquals(ChecklistRow.entries.toList(), rows.map { it.row })
    }

    private companion object {
        /** `NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS` (API 28; a constant, so usable on any sandbox). */
        const val ALARMS = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
    }
}
