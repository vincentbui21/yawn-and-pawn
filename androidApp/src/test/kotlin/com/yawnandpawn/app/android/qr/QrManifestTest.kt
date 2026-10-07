package com.yawnandpawn.app.android.qr

import android.Manifest
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 3.10 review: the merged manifest keeps the bundled model (no Play services model download), the camera optional,
 * and ML Kit off the app's start (no init provider, so an alarm's cold start never waits for it); the app never asks for
 * the camera when it starts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrManifestTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()

    @Suppress("DEPRECATION") // The flags are the only way to read these parts of the merged manifest.
    private fun packageInfo(flags: Int) = app.packageManager.getPackageInfo(app.packageName, flags)

    @Test
    fun `ML Kit's init provider is removed, its components stay, and no model is downloaded`() {
        val providers = packageInfo(PackageManager.GET_PROVIDERS).providers.orEmpty().map { it.name }
        val services = packageInfo(PackageManager.GET_SERVICES).services.orEmpty().map { it.name }

        @Suppress("DEPRECATION")
        val metaData = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData

        assertFalse(providers.any { "MlKitInitProvider" in it }, "providers: $providers")
        assertTrue("com.google.mlkit.common.internal.MlKitComponentDiscoveryService" in services, "ML Kit is merged: $services")
        assertFalse(metaData?.containsKey("com.google.mlkit.vision.DEPENDENCIES") == true, "the bundled model, no download")
    }

    @Test
    fun `the camera is requested as a permission and is not a required feature`() {
        val info = packageInfo(PackageManager.GET_PERMISSIONS or PackageManager.GET_CONFIGURATIONS)
        val camera: FeatureInfo? = info.reqFeatures.orEmpty().firstOrNull { it.name == "android.hardware.camera" }

        assertTrue(Manifest.permission.CAMERA in info.requestedPermissions.orEmpty())
        assertNotNull(camera, "uses-feature android.hardware.camera is declared")
        assertEquals(0, camera.flags and FeatureInfo.FLAG_REQUIRED, "required=false: a phone without a camera can install")
    }

    @Test
    fun `the app never asks for the camera when it starts`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                val asked =
                    shadowOf(activity)
                        .lastRequestedPermission
                        ?.requestedPermissions
                        .orEmpty()
                        .toList()
                assertFalse(Manifest.permission.CAMERA in asked, "asked at start: $asked")
            }
        }
    }
}
