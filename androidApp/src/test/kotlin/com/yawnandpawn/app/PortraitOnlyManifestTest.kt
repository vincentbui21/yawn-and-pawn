package com.yawnandpawn.app

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.android.wake.WakeActivity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Portrait only (owner decision 2026-10-26): every activity of the app in the merged manifest is locked to portrait.
 * Library activities (Play Billing's proxy and the like) keep their own settings: they are translucent, and a translucent
 * activity must not lock its orientation on Android 8.0.
 */
@RunWith(RobolectricTestRunner::class)
class PortraitOnlyManifestTest {
    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()

    @After
    fun tearDown() {
        stopApp()
    }

    @Suppress("DEPRECATION") // The flag is the only way to read the activities of the merged manifest.
    private fun appActivities(): List<ActivityInfo> =
        app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES)
            .activities
            .orEmpty()
            .filter { it.name.startsWith("${app.packageName}.") }

    @Test
    fun `every activity of the app declares portrait`() {
        val activities = appActivities()

        val names = activities.map { it.name }
        assertTrue(MainActivity::class.java.name in names, "activities: $names")
        assertTrue(WakeActivity::class.java.name in names, "activities: $names")
        activities.forEach { info ->
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation, "${info.name} is not portrait")
        }
    }
}
