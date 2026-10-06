package com.yawnandpawn.app.android.call

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.wake.WakeApp
import com.yawnandpawn.app.android.wake.WakeScope
import com.yawnandpawn.app.android.wake.WakeService
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaPlayer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 2.7 (FR-SES-8): the call adapter pauses the ring for a phone call and resumes it after, from the audio mode only,
 * in the real wake service and engine. Other apps taking audio focus never affect the alarm.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CallDetectorTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val alarm = anAlarm(id = "alarm-a", requestCode = 1000)
    private val fired = AlarmFired(alarm.id, Instant.parse("2027-03-08T06:00:00Z"))

    /** A call state the test sets; the real adapter reads the audio mode. */
    private class FakeCallState(
        var inCall: Boolean = false,
    ) : CallState {
        override fun inCall(): Boolean = inCall
    }

    private fun ring(app: WakeApp) {
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        app.ring(fired)
        app.awaitRinging()
    }

    private fun paused(app: WakeApp): Boolean = (app.engine.state.value as? SessionState.Ring)?.session?.paused == true

    private fun detector(app: WakeApp): CallDetector = app.koin.get()

    @Test
    fun `the audio mode means a call when in a call, in a VoIP call or ringing for an incoming call`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = context.getSystemService(AudioManager::class.java)
        val calls = AudioModeCallState(context)

        val byMode =
            listOf(
                AudioManager.MODE_NORMAL,
                AudioManager.MODE_IN_CALL,
                AudioManager.MODE_IN_COMMUNICATION,
                AudioManager.MODE_RINGTONE,
            ).associateWith { mode ->
                audio.mode = mode
                calls.inCall()
            }

        assertEquals(
            mapOf(
                AudioManager.MODE_NORMAL to false,
                AudioManager.MODE_IN_CALL to true,
                AudioManager.MODE_IN_COMMUNICATION to true,
                AudioManager.MODE_RINGTONE to true,
            ),
            byMode,
        )
        audio.mode = AudioManager.MODE_NORMAL
    }

    @Test
    fun `a call during the ring pauses sound and vibration, and its end resumes them`() {
        val calls = FakeCallState()
        val app = WakeApp(calls = calls)
        ring(app)

        calls.inCall = true
        detector(app).onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        app.awaitUntil("the session pauses for the call") { paused(app) }

        assertTrue(app.player.isPaused)
        assertFalse(app.vibrator.isVibrating)
        calls.inCall = false
        detector(app).onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        app.awaitUntil("the ring resumes after the call") { !paused(app) && !app.player.isPaused }
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
        assertTrue(app.vibrator.isVibrating)
    }

    @Test
    fun `another app taking audio focus with the mode normal changes nothing`() {
        val app = WakeApp()
        ring(app)
        val before = app.engine.state.value

        detector(app).onFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        detector(app).onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        app.awaitUntil("the checks on the main thread ran") { true }

        assertSame(before, app.engine.state.value, "no session event")
        assertFalse(app.player.isPaused || app.player.isMuted, "never paused or ducked")
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
    }

    @Test
    fun `an alarm that rings during a call is paused before its first audible frame`() {
        val app = WakeApp(calls = FakeCallState(inCall = true))
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })

        app.ring(fired)
        app.awaitUntil("the session pauses for the call") { paused(app) }

        assertTrue(app.mediaPlayers.isNotEmpty())
        assertTrue(app.mediaPlayers.none { Shadow.extract<ShadowMediaPlayer>(it).isReallyPlaying }, "never audible")
        assertFalse(app.vibrator.isVibrating)
    }

    /** Restores a ring that a call paused when the process died, with the phone [inCall] or not. */
    private fun restorePausedRing(inCall: Boolean): WakeApp {
        val store = FakeActiveSessionStore()
        val app = WakeApp(store = store, calls = FakeCallState(inCall))
        store.row = SessionJson.encode(SessionState.Ringing(aSession().copy(pausedAt = app.now())))
        app.startService(WakeService.intent(app.app, WakeService.ACTION_RESTORE))
        app.awaitUntil("the ring is restored") { app.engine.state.value is SessionState.Ringing && app.player.sound != null }
        return app
    }

    @Test
    fun `a ring restored while the call goes on is paused again`() {
        val app = restorePausedRing(inCall = true)

        app.awaitUntil("CallStarted is sent again") { paused(app) }

        assertTrue(app.player.isPaused)
    }

    @Test
    fun `a ring restored after the call ended is not stuck paused`() {
        val app = restorePausedRing(inCall = false)
        app.awaitUntil("the checks on the main thread ran") { true }

        assertFalse(paused(app))
        assertFalse(app.player.isPaused)
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
    }

    @Test
    fun `calls change nothing while snoozed`() {
        val store = FakeActiveSessionStore()
        val app = WakeApp(store = store, calls = FakeCallState(inCall = true))
        val snoozed = SessionState.Snoozed(aSession().copy(snoozeEnd = Deadline.after(app.now(), 9.minutes), interactionDeadline = null))
        store.row = SessionJson.encode(snoozed)
        runBlocking { app.engine.restore() }
        val before = app.engine.state.value

        detector(app).check()
        app.awaitUntil("the checks on the main thread ran") { true }

        assertIs<SessionState.Snoozed>(app.engine.state.value)
        assertSame(before, app.engine.state.value)
    }

    @Test
    fun `without a mode listener (API 26 to 30) a call's end is found by polling while paused`() {
        val calls = FakeCallState()
        val app = WakeApp(calls = calls)
        ring(app)
        // The service's own detector is the API 31+ kind (no poll); this one polls like on API 26 to 30.
        val polling = CallDetector(calls, app.engine, app.runtime, app.koin.get<WakeScope>(), sdkInt = 30, pollEvery = 50.milliseconds)
        polling.start()
        calls.inCall = true
        polling.check()
        app.awaitUntil("the session pauses for the call") { paused(app) }

        calls.inCall = false

        app.awaitUntil("the poll finds the call's end without any callback") {
            // The poll waits on the main looper's clock.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            !paused(app)
        }
        polling.stop()
    }
}
