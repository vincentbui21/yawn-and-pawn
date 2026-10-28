package com.yawnandpawn.app.android.recording

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.ui.recordings.MicrophoneAnswer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 7.2: the microphone permission asks only through the screen in front, and "Fix" opens the app's settings. */
@RunWith(RobolectricTestRunner::class)
class AndroidMicrophonePermissionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val permission = AndroidMicrophonePermission(app, FakeLogger())

    @Test
    fun `without a screen in front nothing is asked`() =
        runTest {
            assertFalse(permission.isGranted())
            assertEquals(MicrophoneAnswer.Denied, permission.request())
        }

    @Test
    fun `the dialog's answer is the request's answer, and a stopped screen answers no`() =
        runTest {
            var launches = 0
            val launch: () -> Unit = { launches++ }
            permission.attach(launch)

            val granted = async(start = CoroutineStart.UNDISPATCHED) { permission.request() }
            val same = async(start = CoroutineStart.UNDISPATCHED) { permission.request() }
            assertEquals(1, launches, "one dialog for both")
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            permission.onResult(true)
            assertEquals(MicrophoneAnswer.Granted, granted.await())
            assertEquals(MicrophoneAnswer.Granted, same.await())
            assertTrue(permission.isGranted())
            assertEquals(MicrophoneAnswer.Granted, permission.request(), "granted: no dialog")
            assertEquals(1, launches)

            shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { permission.request() }
            permission.detach(launch)
            assertEquals(MicrophoneAnswer.Denied, stopped.await())
            assertEquals(MicrophoneAnswer.Denied, permission.request(), "detached")
        }

    @Test
    fun `a no the dialog can still change is Denied, one it cannot is Blocked`() =
        runTest {
            permission.attach {}

            val denied = async(start = CoroutineStart.UNDISPATCHED) { permission.request() }
            permission.onResult(false)
            assertEquals(MicrophoneAnswer.Denied, denied.await())

            val blocked = async(start = CoroutineStart.UNDISPATCHED) { permission.request() }
            permission.onResult(false, blocked = true)
            assertEquals(MicrophoneAnswer.Blocked, blocked.await())
        }

    @Test
    fun `Fix opens the app's page in the system settings`() {
        permission.openSettings()

        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals("package:${app.packageName}", opened.dataString)
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
