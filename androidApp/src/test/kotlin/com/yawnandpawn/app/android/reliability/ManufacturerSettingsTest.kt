package com.yawnandpawn.app.android.reliability

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.core.reliability.ManufacturerSettings
import com.yawnandpawn.app.testing.FakeLogger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 5.5: "Open settings" starts the maker's first screen this phone resolves, else the app details, else the system
 * settings; a screen that is not resolved is never started, one that throws is skipped, and the manifest's `<queries>`
 * names every package so API 30+ can resolve them.
 */
@RunWith(RobolectricTestRunner::class)
class ManufacturerSettingsTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val logger = FakeLogger()
    private val covered = DeviceMaker.entries.filter { it.covered }

    @Before
    fun onlyResolvableScreensStart() {
        shadowOf(app).checkActivities(true)
    }

    private fun installed(component: ComponentName) {
        shadowOf(app.packageManager).addActivityIfNotPresent(component)
    }

    private fun handles(intent: Intent) {
        val handler = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.android.settings" } }
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, handler)
    }

    private val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))

    private fun nextStarted(): Intent? = shadowOf(app).nextStartedActivity

    @Test
    fun `every covered maker has its screens, Other has none, and every package is queried`() {
        covered.forEach { maker -> assertTrue(OemIntents.components(maker).isNotEmpty(), "$maker") }
        assertEquals(emptyList(), OemIntents.components(DeviceMaker.Other))

        val manifest = File("src/main/AndroidManifest.xml").readText()
        val queries =
            Regex("<queries>(.*?)</queries>", RegexOption.DOT_MATCHES_ALL)
                .find(manifest)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        val queried = Regex("""<package\s+android:name="([^"]+)"""").findAll(queries).map { it.groupValues[1] }.toSet()
        assertEquals(OemIntents.packages, queried, "<queries> names exactly the packages of OemIntents")
    }

    @Test
    fun `with a resolvable screen each maker opens it as a new task`() {
        val settings = AndroidManufacturerSettings(app, logger)
        covered.forEach { maker ->
            val component = OemIntents.components(maker).last()
            installed(component)

            settings.open(maker)

            val started = nextStarted()
            assertEquals(component, started?.component, "$maker")
            assertTrue(started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0, "$maker")
            assertNull(nextStarted(), "$maker: only one screen opens")
        }
        assertEquals(emptyList(), logger.events, "unresolved screens are skipped without starting them")
    }

    @Test
    fun `the maker's screens are tried in order`() {
        val settings = AndroidManufacturerSettings(app, logger)
        val (first, second) = OemIntents.components(DeviceMaker.Huawei)
        installed(second)
        installed(first)

        settings.open(DeviceMaker.Huawei)

        assertEquals(first, nextStarted()?.component)
    }

    @Test
    fun `without a resolvable screen each maker opens the app details, and Other always does`() {
        handles(appDetails)
        val settings = AndroidManufacturerSettings(app, logger)

        (covered + DeviceMaker.Other).forEach { maker ->
            settings.open(maker)

            val started = nextStarted()
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started?.action, "$maker")
            assertEquals("package:${app.packageName}", started?.dataString, "$maker")
            assertNull(started?.component, "$maker: no maker screen")
        }
        assertEquals(emptyList(), logger.events)
    }

    @Test
    fun `a screen that throws is logged and the next one opens`() {
        val (first, second) = OemIntents.components(DeviceMaker.Xiaomi)
        installed(first)
        installed(second)
        val refusing =
            object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) {
                    if (intent.component == first) throw SecurityException("not exported")
                    super.startActivity(intent)
                }
            }

        AndroidManufacturerSettings(refusing, logger).open(DeviceMaker.Xiaomi)

        assertEquals(second, nextStarted()?.component)
        assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(AndroidManufacturerSettings.OPEN, "SecurityException")), logger.events)
    }

    @Test
    fun `with no app details screen the system settings open, and with neither it is logged without crashing`() {
        val settings = AndroidManufacturerSettings(app, logger)

        settings.open(DeviceMaker.Samsung)
        assertNull(nextStarted(), "nothing resolves: nothing started")
        assertEquals(
            List<LogEvent>(2) { LogEvent.OperationFailed(AndroidManufacturerSettings.OPEN, "ActivityNotFoundException") },
            logger.events,
        )

        handles(Intent(Settings.ACTION_SETTINGS))
        settings.open(DeviceMaker.Samsung)

        val started = nextStarted()
        assertEquals(Settings.ACTION_SETTINGS, started?.action)
        assertTrue(started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `a lookup that throws counts as not resolved`() {
        handles(appDetails)
        installed(OemIntents.components(DeviceMaker.OnePlus).single())
        val broken: Context =
            object : ContextWrapper(app) {
                override fun getPackageManager(): PackageManager = throw SecurityException("broken")
            }

        AndroidManufacturerSettings(broken, logger).open(DeviceMaker.OnePlus)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, nextStarted()?.action)
        assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(AndroidManufacturerSettings.RESOLVE, "SecurityException")), logger.events)
    }

    @Test
    fun `the app's graph binds the Android manufacturer settings`() {
        assertIs<AndroidManufacturerSettings>(GlobalContext.get().get<ManufacturerSettings>())
    }
}
