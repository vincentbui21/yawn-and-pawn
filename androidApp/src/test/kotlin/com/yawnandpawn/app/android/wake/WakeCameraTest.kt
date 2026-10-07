package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.ui.qr.CameraProblem
import com.yawnandpawn.app.ui.qr.CameraStatus
import com.yawnandpawn.app.ui.qr.ScanEvent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Story 3.11 review: the wake QR camera's per-entry monitor, latch and watchdog, outside any screen. */
class WakeCameraTest {
    private val clock = FakeMonotonicClock(elapsedMillis = 1_000_000)
    private val camera = WakeCamera(clock, FakeLogger())
    private val a = CameraEntry("session", ringIndex = 1, entry = 0, fallback = false)
    private val b = a.copy(entry = 1)

    @Test
    fun `a paused screen's watchdog does not run - 30 s paused is still Starting, with no latch`() {
        camera.follow(a)
        camera.watch(resumed = true)
        camera.onEvent(ScanEvent.Opened)
        camera.watch(resumed = false)
        clock.advanceBy(30.seconds)
        camera.tick()
        assertEquals(CameraStatus.Starting, camera.statusOf(a))
        assertFalse(camera.offersFallback(a))
    }

    @Test
    fun `the latch is the entry's - a new entry starts with none and its own watchdog from 0`() {
        camera.follow(a)
        camera.watch(resumed = true)
        camera.onEvent(ScanEvent.CameraUnavailable(CameraProblem.Disconnected))
        assertTrue(camera.offersFallback(a))

        clock.advanceBy(10.seconds)
        camera.follow(b)
        camera.onEvent(ScanEvent.Opened)
        assertFalse(camera.offersFallback(b), "the latch ends with its entry")
        assertEquals(CameraStatus.Starting, camera.statusOf(b))
        clock.set(clock.elapsedMillis() + 4_900)
        camera.tick()
        assertEquals(CameraStatus.Starting, camera.statusOf(b), "4.9 s on the new entry")
        clock.set(clock.elapsedMillis() + 100)
        camera.tick()
        assertEquals(CameraStatus.Unavailable(CameraProblem.NoFrames), camera.statusOf(b), "5.0 s on the new entry")
    }

    @Test
    fun `a status change into unavailable is logged once, with the problem name only`() {
        val logger = FakeLogger()
        val logged = WakeCamera(clock, logger)
        logged.follow(a)
        logged.watch(resumed = true)
        repeat(3) { logged.onEvent(ScanEvent.CameraUnavailable(CameraProblem.CameraError)) }
        assertEquals(1, logger.events.size)
    }
}
