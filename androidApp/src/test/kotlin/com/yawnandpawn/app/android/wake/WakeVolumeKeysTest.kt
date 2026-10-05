package com.yawnandpawn.app.android.wake

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.aSessionConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Story 2.8 (FR-SES-6): the wake screen swallows the volume keys only while it is in front, sends no session event for
 * them, lets the accessibility shortcut through, and the app owns no media session or media button receiver.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WakeVolumeKeysTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    // Installs the Compose test clock, so the ringing screen's animations never keep the main looper busy.
    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun key(
        action: Int,
        code: Int,
    ) = KeyEvent(action, code)

    @Test
    fun `while the wake screen is in front the volume keys are consumed, change nothing and send no session event`() {
        val app = WakeApp()
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), listOf(1L), beforeFirstUnlock = false))
        val screen = Robolectric.buildActivity(WakeActivity::class.java).setup()
        val audio = app.app.getSystemService(AudioManager::class.java)
        val stream = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val before = app.engine.state.value

        val consumed =
            listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE).flatMap { code ->
                listOf(
                    screen.get().dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, code)),
                    screen.get().dispatchKeyEvent(key(KeyEvent.ACTION_UP, code)),
                )
            }
        app.awaitUntil("the main looper settles") { true }

        assertEquals(List(6) { true }, consumed)
        assertEquals(stream, audio.getStreamVolume(AudioManager.STREAM_ALARM), "no stream change")
        assertSame(before, app.engine.state.value, "no UserInteracted")
        assertFalse(screen.get().isFinishing)
    }

    @Test
    fun `the accessibility shortcut passes, and after onPause nothing is consumed`() {
        val app = WakeApp()
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), listOf(1L), beforeFirstUnlock = false))
        val screen = Robolectric.buildActivity(WakeActivity::class.java).setup()
        val gate = screen.get().volumeKeys

        assertTrue(gate.consumes(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
        assertFalse(gate.consumes(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP), "both held: the shortcut")
        assertFalse(gate.consumes(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(gate.consumes(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN))

        screen.pause()

        assertFalse(gate.resumed)
        assertFalse(gate.consumes(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN), "another screen is in front")
        screen.resume()
        assertTrue(gate.consumes(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    @Test
    fun `the app declares no media button receiver and no media browser service`() {
        val app = WakeApp().app
        val packages = app.packageManager

        val mediaButtons =
            packages.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(app.packageName), PackageManager.GET_META_DATA)
        val browsers =
            packages.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(app.packageName), 0)

        assertEquals(emptyList(), mediaButtons.map { it.activityInfo.name })
        assertEquals(emptyList(), browsers.map { it.serviceInfo.name })
    }
}
