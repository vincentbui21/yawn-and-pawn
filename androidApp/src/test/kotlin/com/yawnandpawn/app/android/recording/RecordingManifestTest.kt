package com.yawnandpawn.app.android.recording

import android.Manifest
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 7.1: the merged manifest requests the microphone for the motivation messages and keeps it optional, so Play
 * still offers the app on a phone without one (next to `QrManifestTest`'s camera check).
 */
@RunWith(RobolectricTestRunner::class)
class RecordingManifestTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()

    @Test
    fun `the microphone is requested as a permission and is not a required feature`() {
        @Suppress("DEPRECATION") // The flags are the only way to read these parts of the merged manifest.
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_CONFIGURATIONS)
        val microphone: FeatureInfo? = info.reqFeatures.orEmpty().firstOrNull { it.name == PackageManager.FEATURE_MICROPHONE }

        assertTrue(Manifest.permission.RECORD_AUDIO in info.requestedPermissions.orEmpty())
        assertNotNull(microphone, "uses-feature android.hardware.microphone is declared")
        assertEquals(0, microphone.flags and FeatureInfo.FLAG_REQUIRED, "required=false: a phone without a microphone can install")
    }
}
