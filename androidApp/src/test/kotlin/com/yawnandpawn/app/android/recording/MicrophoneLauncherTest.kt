package com.yawnandpawn.app.android.recording

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.recordings.MicrophoneAnswer
import com.yawnandpawn.app.ui.recordings.MicrophonePermission
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Story 7.2: `MainActivity` attaches its `RECORD_AUDIO` launcher to the app's own [MicrophonePermission], so a record
 * tap asks through the screen in front and the dialog's answer completes the request; the app never asks at start.
 */
@RunWith(RobolectricTestRunner::class)
class MicrophoneLauncherTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `a request asks RECORD_AUDIO through MainActivity, and the answer completes it`() =
        runTest {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val asked =
                        shadowOf(activity)
                            .lastRequestedPermission
                            ?.requestedPermissions
                            .orEmpty()
                            .toList()
                    assertFalse(Manifest.permission.RECORD_AUDIO in asked, "never asked at start: $asked")
                }
                val permission = GlobalContext.get().get<MicrophonePermission>()

                val answer = async(start = CoroutineStart.UNDISPATCHED) { permission.request() }

                scenario.onActivity { activity ->
                    val request = assertNotNull(shadowOf(activity).lastRequestedPermission, "the dialog was asked")
                    assertEquals(listOf(Manifest.permission.RECORD_AUDIO), request.requestedPermissions.toList())
                    shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
                    @Suppress("DEPRECATION") // How Robolectric hands a permission result to the activity result registry.
                    activity.onRequestPermissionsResult(
                        request.requestCode,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        intArrayOf(PackageManager.PERMISSION_GRANTED),
                    )
                }
                assertEquals(MicrophoneAnswer.Granted, answer.await())
            }
        }
}
