package com.yawnandpawn.app.debug

import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.wake.AlarmSound
import com.yawnandpawn.app.android.wake.AlarmVolume
import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import com.yawnandpawn.app.android.wake.FakePlaybackFactory
import com.yawnandpawn.app.android.wake.SoundResolver
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.stopApp
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 2.11: the debug-only [WakeStatus] reports playing only for an open, prepared, audible ring. */
@RunWith(RobolectricTestRunner::class)
class WakeStatusTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()
    private val logger = FakeLogger()
    private val playbacks = FakePlaybackFactory()
    private val player =
        AndroidAlarmPlayer(
            playbacks,
            SoundResolver { AlarmSound.Default },
            AlarmVolume(app, logger),
            FakeMonotonicClock(elapsedMillis = 1_000),
            CoroutineScope(StandardTestDispatcher()),
            logger,
        )

    private fun withPlayer() = restartKoin(app, module { single { player } })

    private fun ring() = player.play(SOUND, VOLUME, gradual = false, rampStartPercent = 0)

    @Test
    fun `a prepared, audible ring is playing`() {
        withPlayer()
        ring()

        assertTrue(WakeStatus.isPlaying())
    }

    @Test
    fun `muted, paused or stopped is not playing`() {
        withPlayer()
        ring()

        player.mute()
        assertFalse(WakeStatus.isPlaying(), "muted (grace)")
        player.unmuteTo(VOLUME)
        assertTrue(WakeStatus.isPlaying(), "unmuted")

        player.pause()
        assertFalse(WakeStatus.isPlaying(), "paused (a call)")
        player.resume()
        assertTrue(WakeStatus.isPlaying(), "resumed")

        player.stop(restoreVolume = true)
        assertFalse(WakeStatus.isPlaying(), "stopped")
    }

    @Test
    fun `a sound still preparing, or no ring at all, is not playing`() {
        withPlayer()
        assertFalse(WakeStatus.isPlaying(), "no ring")

        playbacks.stalling += AlarmSound.Default
        ring()
        assertFalse(WakeStatus.isPlaying(), "still preparing")
    }

    @Test
    fun `without a Koin graph nothing is playing`() {
        stopApp()

        assertFalse(WakeStatus.isPlaying())
    }

    private companion object {
        const val SOUND = "test:default"
        const val VOLUME = 80
    }
}
