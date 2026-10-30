package com.yawnandpawn.app.android.reliability

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.wake.WakeNotifier
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.reliability.ChecklistRow
import com.yawnandpawn.app.testing.FakeLogger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 5.4: each checklist row's "Fix" starts its own system screen when this phone resolves it (Robolectric
 * `ShadowPackageManager`), else the app details, else the system settings. A screen that does not resolve is never
 * started, one that throws is skipped, and with none at all it is logged and answers false without crashing.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidReliabilitySettingsTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val logger = FakeLogger()
    private val packageUri = "package:${app.packageName}"
    private val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(packageUri))
    private val systemSettings = Intent(Settings.ACTION_SETTINGS)

    @Before
    fun onlyResolvableScreensStart() {
        shadowOf(app).checkActivities(true)
    }

    private fun handles(intent: Intent) {
        val handler = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.android.settings" } }
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, handler)
    }

    private fun nextStarted(): Intent? = shadowOf(app).nextStartedActivity

    private fun settings(
        sdkInt: Int = 34,
        exempt: Boolean = false,
        context: Context = app,
        channelBlocked: Boolean = false,
    ) = AndroidReliabilitySettings(context, logger, sdkInt = sdkInt, batteryExempt = { exempt }, alarmChannelBlocked = { channelBlocked })

    /** The row's own screen on API 34 (Exact alarms is Hidden there, but its screen still exists; Battery is App info). */
    private val api34Screens =
        mapOf(
            ChecklistRow.Notifications to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            ChecklistRow.FullScreenAlarm to Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            ChecklistRow.ExactAlarms to Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            ChecklistRow.DoNotDisturb to Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
            ChecklistRow.Battery to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ChecklistRow.Manufacturer to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ChecklistRow.Camera to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ChecklistRow.Microphone to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ChecklistRow.LockedScreenTest to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        )

    @Test
    @Config(sdk = [34])
    fun `with a resolver each row opens its own screen as a new task, for this app`() {
        val settings = settings()
        ChecklistRow.entries.forEach { row ->
            val own = settings.intentFor(row)
            assertEquals(api34Screens.getValue(row), own.action, "$row")
            handles(own)

            assertTrue(settings.open(row), "$row")

            val started = nextStarted()
            assertEquals(api34Screens.getValue(row), started?.action, "$row")
            assertTrue(started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0, "$row")
            assertNull(nextStarted(), "$row: only one screen opens")
        }
        val notifications = settings.intentFor(ChecklistRow.Notifications)
        assertEquals(app.packageName, notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(packageUri, settings.intentFor(ChecklistRow.FullScreenAlarm).dataString)
        assertEquals(packageUri, settings.intentFor(ChecklistRow.ExactAlarms).dataString)
        assertEquals(emptyList(), logger.events)
    }

    @Test
    @Config(sdk = [31])
    fun `on API 31 exact alarms open their own screen and the full-screen alarm the app details`() {
        val settings = settings(sdkInt = 31)
        val exact = settings.intentFor(ChecklistRow.ExactAlarms)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, exact.action)
        assertEquals(packageUri, exact.dataString)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.intentFor(ChecklistRow.FullScreenAlarm).action)

        handles(exact)
        assertTrue(settings.open(ChecklistRow.ExactAlarms))
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, nextStarted()?.action)
    }

    @Test
    @Config(sdk = [28])
    fun `below API 31 neither special access screen exists, so both open the app details`() {
        val settings = settings(sdkInt = 28)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.intentFor(ChecklistRow.ExactAlarms).action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.intentFor(ChecklistRow.FullScreenAlarm).action)
        assertEquals(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, settings.intentFor(ChecklistRow.DoNotDisturb).action)
    }

    @Test
    @Config(sdk = [34])
    fun `from API 31 battery always opens this app's App info, whatever the exemption (0_1_7 polish)`() {
        listOf(false, true).forEach { exempt ->
            val settings = settings(sdkInt = 31, exempt = exempt)
            val battery = settings.intentFor(ChecklistRow.Battery)
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, battery.action, "exempt: $exempt")
            assertEquals(packageUri, battery.dataString, "exempt: $exempt")
            val fallbacks = settings.targets(ChecklistRow.Battery).map { it.action }
            assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS), fallbacks, "no repeats")
        }
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings(sdkInt = 34).intentFor(ChecklistRow.Battery).action)
        assertEquals(emptyList(), logger.events)
    }

    @Test
    @Config(sdk = [28])
    fun `below API 31 battery opens the optimisation list while not exempt, and the app details once exempt`() {
        val optimised = settings(sdkInt = 28, exempt = false).intentFor(ChecklistRow.Battery)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, optimised.action)
        val exempt = settings(sdkInt = 28, exempt = true)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, exempt.intentFor(ChecklistRow.Battery).action)
        val fallbacks = exempt.targets(ChecklistRow.Battery).map { it.action }
        assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS), fallbacks, "no repeats")

        // Without the seam the exemption is read from PowerManager at the tap.
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        val platform = AndroidReliabilitySettings(app, logger, sdkInt = 28)
        power.setIgnoringBatteryOptimizations(app.packageName, false)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, platform.intentFor(ChecklistRow.Battery).action)
        power.setIgnoringBatteryOptimizations(app.packageName, true)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, platform.intentFor(ChecklistRow.Battery).action)
    }

    @Test
    @Config(sdk = [34])
    fun `notifications open the Alarms channel's settings when only that channel is blocked (review item 2)`() {
        val notifications = app.getSystemService(NotificationManager::class.java)
        val platform = AndroidReliabilitySettings(app, logger)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, platform.intentFor(ChecklistRow.Notifications).action, "no channel yet")

        notifications.createNotificationChannel(NotificationChannel(WakeNotifier.CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_NONE))
        val channel = platform.intentFor(ChecklistRow.Notifications)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel.action)
        assertEquals(app.packageName, channel.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(WakeNotifier.CHANNEL_ID, channel.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        handles(channel)
        assertTrue(platform.open(ChecklistRow.Notifications))
        val started = nextStarted()
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, started?.action)
        assertEquals(WakeNotifier.CHANNEL_ID, started?.getStringExtra(Settings.EXTRA_CHANNEL_ID))

        // With the app's notifications off, their page comes first (the channel's page cannot turn them on).
        shadowOf(notifications).setNotificationsEnabled(false)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, platform.intentFor(ChecklistRow.Notifications).action)
        assertEquals(emptyList(), logger.events)
    }

    @Test
    @Config(sdk = [34])
    fun `without the row's screen the app details open`() {
        handles(appDetails)
        handles(systemSettings)

        assertTrue(settings().open(ChecklistRow.DoNotDisturb))

        val details = nextStarted()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, details?.action)
        assertEquals(packageUri, details?.dataString)
        assertTrue(details!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(nextStarted())
        assertEquals(emptyList(), logger.events)
    }

    @Test
    @Config(sdk = [34])
    fun `without the row's screen or the app details the system settings open`() {
        handles(systemSettings)

        assertTrue(settings().open(ChecklistRow.Battery))

        val started = nextStarted()
        assertEquals(Settings.ACTION_SETTINGS, started?.action)
        assertTrue(started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(emptyList(), logger.events)
    }

    @Test
    @Config(sdk = [34])
    fun `with nothing that resolves nothing starts, it is logged and answers false`() {
        val settings = settings()

        ChecklistRow.entries.forEach { row -> assertFalse(settings.open(row), "$row") }

        assertNull(nextStarted(), "a target that does not resolve is never started")
        val nothing = LogEvent.OperationFailed(AndroidReliabilitySettings.OPEN, "no settings screen resolves")
        assertEquals(List<LogEvent>(ChecklistRow.entries.size) { nothing }, logger.events)
    }

    @Test
    @Config(sdk = [34])
    fun `a screen that throws is logged and the next one opens`() {
        handles(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))
        handles(appDetails)
        val refusing =
            object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) {
                    if (intent.action == Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS) throw SecurityException("not exported")
                    super.startActivity(intent)
                }
            }

        assertTrue(settings(context = refusing).open(ChecklistRow.DoNotDisturb))

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, nextStarted()?.action)
        assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(AndroidReliabilitySettings.OPEN, "SecurityException")), logger.events)
    }

    @Test
    @Config(sdk = [34])
    fun `a lookup that throws counts as not resolved, and nothing starts`() {
        val broken: Context =
            object : ContextWrapper(app) {
                override fun getPackageManager(): PackageManager = throw SecurityException("broken")
            }

        assertFalse(settings(context = broken).open(ChecklistRow.Notifications))

        assertNull(nextStarted())
        assertEquals(
            List<LogEvent>(3) { LogEvent.OperationFailed(AndroidReliabilitySettings.RESOLVE, "SecurityException") } +
                LogEvent.OperationFailed(AndroidReliabilitySettings.OPEN, "no settings screen resolves"),
            logger.events,
        )
    }

    @Test
    @Config(sdk = [28])
    fun `below API 31 a battery exemption read that throws is logged and offers the optimisation list`() {
        val broken: Context =
            object : ContextWrapper(app) {
                override fun getSystemService(name: String): Any? =
                    if (name == Context.POWER_SERVICE) throw IllegalStateException("broken") else super.getSystemService(name)
            }

        val battery = AndroidReliabilitySettings(broken, logger, sdkInt = 28).intentFor(ChecklistRow.Battery)

        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, battery.action)
        assertEquals(
            listOf<LogEvent>(LogEvent.OperationFailed(AndroidReliabilitySettings.READ_BATTERY, "IllegalStateException")),
            logger.events,
        )
    }

    @Test
    fun `the manifest queries every settings target the checklist opens, by action and data scheme`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val queries =
            Regex("<queries>(.*?)</queries>", RegexOption.DOT_MATCHES_ALL)
                .find(manifest)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        val queried =
            Regex("<intent>(.*?)</intent>", RegexOption.DOT_MATCHES_ALL)
                .findAll(queries)
                .map { intent ->
                    val body = intent.groupValues[1]
                    val action = Regex("""<action\s+android:name="([^"]+)"""").find(body)?.groupValues?.get(1)
                    val scheme = Regex("""<data\s+android:scheme="([^"]+)"""").find(body)?.groupValues?.get(1)
                    action to scheme
                }.toSet()
        val opened =
            listOf(28, 31, 34).flatMap { sdk ->
                listOf(true, false).flatMap { exempt ->
                    listOf(true, false).flatMap { blocked ->
                        ChecklistRow.entries.flatMap { row ->
                            settings(sdkInt = sdk, exempt = exempt, channelBlocked = blocked).targets(row).map {
                                it.action to
                                    it.data?.scheme
                            }
                        }
                    }
                }
            }

        assertEquals(opened.toSet(), queried)
    }
}
