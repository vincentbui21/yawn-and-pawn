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
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val alarm = anAlarm(id = "alarm-a", requestCode = 1000)
    private val fired = AlarmFired(alarm.id, Instant.parse("2027-03-08T06:00:00Z"))

    /** The audio mode is shared by every `AudioManager`; no test leaves a call behind. */
    @After
    fun normalMode() {
        audio.mode = AudioManager.MODE_NORMAL
    }

    /**
     * The phone (Robolectric's audio manager) enters audio [mode], as the dialer or a VoIP app does. Only the test sets
     * it: the app never switches the mode (NoHostageApis), so a call mode goes through this helper, not an assignment.
     */
    private fun phoneMode(mode: Int) {
        audio.mode = mode
    }

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

    /** Runs the main looper's clock [by] (the detector's delays) while waiting until [condition] holds. */
    private fun WakeApp.awaitAfter(
        by: Duration,
        what: String,
        condition: () -> Boolean,
    ) = awaitUntil(what) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(by.inWholeMilliseconds))
        condition()
    }

    /** The alarm's focus listener, as the platform calls it (through the wake module's wiring). */
    private fun focusChange(change: Int) = checkNotNull(shadowOf(audio).lastAudioFocusRequest).listener.onAudioFocusChange(change)

    @Test
    fun `the audio mode means a call when in a call, in a VoIP call or ringing for an incoming call`() {
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
    }

    @Test
    fun `call screening (API 30) and redirected calls (API 33) count as a call from the level that has them`() {
        val screening = AudioManager.MODE_CALL_SCREENING
        val redirects = setOf(AudioManager.MODE_CALL_REDIRECT, AudioManager.MODE_COMMUNICATION_REDIRECT)

        assertFalse(screening in AudioModeCallState.callModes(29))
        assertTrue(screening in AudioModeCallState.callModes(30))
        assertTrue(redirects.none { it in AudioModeCallState.callModes(32) })
        assertTrue(redirects.all { it in AudioModeCallState.callModes(33) })
        val calls = AudioModeCallState(context)
        for (mode in redirects + screening) {
            audio.mode = mode
            assertTrue(calls.inCall(), "mode $mode")
        }
    }

    @Test
    fun `a call during the ring pauses sound and vibration, and its end resumes them`() {
        val calls = FakeCallState()
        val app = WakeApp(calls = calls)
        ring(app)

        calls.inCall = true
        focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        app.awaitUntil("the session pauses for the call") { paused(app) }

        assertTrue(app.player.isPaused)
        assertFalse(app.vibrator.isVibrating)
        calls.inCall = false
        focusChange(AudioManager.AUDIOFOCUS_GAIN)
        app.awaitUntil("the ring resumes after the call") { !paused(app) && !app.player.isPaused }
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
        assertTrue(app.vibrator.isVibrating)
        assertEquals(1f, app.player.gain, "the set volume, gain 1")
    }

    @Test
    fun `a mode change alone pauses and resumes the ring (API 31+)`() {
        val app = WakeApp()
        ring(app)

        phoneMode(AudioManager.MODE_IN_CALL)
        app.awaitUntil("the mode listener pauses the session") { paused(app) }
        assertTrue(app.player.isPaused)

        audio.mode = AudioManager.MODE_NORMAL
        app.awaitUntil("the mode listener resumes the ring") { !paused(app) && !app.player.isPaused }
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
    }

    @Test
    fun `the mode listener is registered while watching and removed by the returned function`() {
        var changes = 0
        val unwatch = checkNotNull(AudioModeCallState(context).watch { changes++ })

        phoneMode(AudioManager.MODE_IN_CALL)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, changes)
        unwatch()
        audio.mode = AudioManager.MODE_NORMAL
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, changes, "no callback once unwatched")
    }

    @Test
    fun `another app taking audio focus with the mode normal changes nothing`() {
        val app = WakeApp()
        ring(app)
        val before = app.engine.state.value

        focusChange(AudioManager.AUDIOFOCUS_LOSS)
        focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
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
    fun `a ring restored after the call ended plays, and the detector sends nothing (the reducer cleared the pause)`() {
        val app = restorePausedRing(inCall = false)
        app.awaitUntil("the checks on the main thread ran") { true }
        val restored = app.engine.state.value

        detector(app).check()
        app.awaitUntil("the check on the main thread ran") { true }

        assertSame(restored, app.engine.state.value, "no CallStarted or CallEnded")
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

        detector(app).start()
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
        val polling =
            CallDetector(
                app.koin.get(),
                app.engine,
                app.runtime,
                app.koin.get<WakeScope>(),
                app.koin.get(),
                app.koin.get(),
                sdkInt = 30,
                pollEvery = 50.milliseconds,
            )
        polling.start()
        calls.inCall = true
        polling.check()
        app.awaitUntil("the session pauses for the call") { paused(app) }

        calls.inCall = false

        app.awaitAfter(100.milliseconds, "the poll finds the call's end without any callback") { !paused(app) }
        polling.stop()
    }

    @Test
    @Config(sdk = [30])
    fun `on API 30 the service's detector finds a call and its end from the audio mode by polling`() {
        val app = WakeApp()
        ring(app)

        phoneMode(AudioManager.MODE_IN_CALL)
        app.awaitAfter(1100.milliseconds, "the poll pauses the session for the call") { paused(app) }
        assertTrue(app.player.isPaused)

        audio.mode = AudioManager.MODE_NORMAL
        app.awaitAfter(1100.milliseconds, "the poll finds the call's end") { !paused(app) && !app.player.isPaused }
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
    }

    @Test
    fun `a call event the engine could not commit is checked again, even with a mode listener`() {
        val store = FakeActiveSessionStore()
        val calls = FakeCallState()
        val app = WakeApp(store = store, calls = calls)
        ring(app)
        store.commitFailure = DomainError.StorageFailure("disk full")

        calls.inCall = true
        focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        app.awaitUntil("the check on the main thread ran") { true }
        assertFalse(paused(app), "CallStarted was not committed")
        store.commitFailure = null

        app.awaitAfter(1100.milliseconds, "the recheck pauses the session") { paused(app) }
        assertTrue(app.player.isPaused)
    }

    @Test
    fun `a call mode stuck for the cap is logged and ignored until it changes, so the ring is never silent forever`() {
        val calls = FakeCallState()
        val app = WakeApp(calls = calls)
        ring(app)
        val guard = app.koin.get<StuckCallGuard>()
        val capped =
            CallDetector(guard, app.engine, app.runtime, app.koin.get<WakeScope>(), app.koin.get(), app.koin.get(), pauseCap = 2.seconds)
        capped.start()
        calls.inCall = true
        capped.check()
        app.awaitUntil("the session pauses for the call") { paused(app) }

        app.awaitAfter(1.seconds, "the cap ends the pause") { !paused(app) && !app.player.isPaused }

        assertTrue(app.logs().any { "call mode lasted" in it }, "the stuck mode is logged")
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
        detector(app).check()
        app.awaitUntil("the checks on the main thread ran") { true }
        assertFalse(paused(app), "not paused again while the mode stays stuck")
        // The mode leaves the call once: the next call pauses the ring again.
        calls.inCall = false
        capped.check()
        app.awaitUntil("the checks on the main thread ran") { true }
        calls.inCall = true
        capped.check()
        app.awaitUntil("a new call pauses the ring") { paused(app) }
        capped.stop()
    }

    @Test
    fun `a ring starts the call adapter even when the wake service did not start`() {
        val store = FakeActiveSessionStore()
        val app = WakeApp(store = store, calls = FakeCallState(inCall = true))
        store.row = SessionJson.encode(SessionState.Ringing(aSession()))

        // The restore at app start rings; the service start is only requested (the platform may refuse it).
        runBlocking { app.engine.restore() }

        app.awaitUntil("the ring's own start of the adapter pauses it for the call") { paused(app) }
        assertTrue(app.player.isPaused)
    }

    @Test
    fun `a check after the adapter stopped does nothing`() {
        val calls = FakeCallState()
        val app = WakeApp(calls = calls)
        ring(app)
        detector(app).stop()

        calls.inCall = true
        detector(app).check()
        app.awaitUntil("the main thread ran") { true }

        assertFalse(paused(app))
    }

    @Test
    fun `an emergency ring is silent during a call and rings when it ends`() {
        val calls = FakeCallState(inCall = true)
        val app = WakeApp(calls = calls)

        app.runtime.startEmergency(fired.scheduledAt, volumePercent = 60, cause = "test")
        app.awaitUntil("the emergency ring opened") { app.player.sound != null }
        assertTrue(app.player.isPaused)
        assertFalse(app.vibrator.isVibrating)

        calls.inCall = false
        detector(app).check()
        app.awaitUntil("the emergency ring rings after the call") { !app.player.isPaused }
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
        assertTrue(app.vibrator.isVibrating)
        app.runtime.stopEmergency()
    }
}
