package com.yawnandpawn.app.ui.qr

import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeMonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Story 3.11: the wake QR check's camera watchdog, recovery and latches, on the monotonic clock only. */
class CameraMonitorTest {
    private val clock = FakeMonotonicClock(elapsedMillis = 10_000)
    private val monitor = CameraMonitor()

    private fun now(): Long = clock.elapsedMillis()

    private fun after(millis: Long): Long {
        clock.advanceBy(millis.milliseconds)
        return now()
    }

    /** Frames every 500 ms (the analyser's heartbeat) for [millis]. */
    private fun heartbeats(millis: Long) {
        repeat((millis / HEARTBEAT).toInt()) {
            monitor.frame(after(HEARTBEAT))
            monitor.tick(now())
        }
    }

    @Test
    fun `bound with no frame is Starting at 4_9 s and NoFrames at 5_0 s, measured on the monotonic clock`() {
        val wall = FakeClock()
        monitor.bound(now())
        monitor.tick(after(4_900))
        assertEquals(CameraStatus.Starting, monitor.status, "4.9 s: the viewfinder, no message")
        assertFalse(monitor.everUnavailable)

        wall.advanceBy(1.hours) // A wall-clock change has no effect: the monitor only ever sees monotonic readings.
        monitor.tick(after(100))
        assertEquals(CameraStatus.Unavailable(CameraProblem.NoFrames), monitor.status, "5.0 s: the message")
        assertTrue(monitor.everUnavailable)
        assertTrue(monitor.offersFallback)
    }

    @Test
    fun `frames keep the camera Live and the watchdog never fires`() {
        monitor.bound(now())
        heartbeats(30_000)
        assertEquals(CameraStatus.Live, monitor.status)
        assertFalse(monitor.everUnavailable)
    }

    @Test
    fun `a stall mid-scan fires at the last frame plus 5 s`() {
        monitor.bound(now())
        heartbeats(2_000)
        val last = now()
        monitor.tick(last + 4_999)
        assertEquals(CameraStatus.Live, monitor.status)
        monitor.tick(last + 5_000)
        assertEquals(CameraStatus.Unavailable(CameraProblem.NoFrames), monitor.status)
    }

    @Test
    fun `an error mid-scan is unavailable at once, and 1 s of frames brings it back while the latch stays`() {
        monitor.bound(now())
        heartbeats(1_000)
        monitor.problem(CameraProblem.CameraError)
        assertEquals(CameraStatus.Unavailable(CameraProblem.CameraError), monitor.status)

        monitor.frame(after(HEARTBEAT))
        monitor.frame(after(HEARTBEAT))
        assertEquals(CameraStatus.Unavailable(CameraProblem.CameraError), monitor.status, "500 ms of frames: not yet")
        monitor.frame(after(HEARTBEAT))
        assertEquals(CameraStatus.Live, monitor.status, "1 s of frames: back")
        assertTrue(monitor.everUnavailable, "the link stays")
    }

    @Test
    fun `a disconnect recovers the same way, and a gap in the frames starts the recovery over`() {
        monitor.bound(now())
        monitor.problem(CameraProblem.Disconnected)
        monitor.frame(after(HEARTBEAT))
        monitor.frame(after(HEARTBEAT))
        monitor.frame(after(1_500)) // A gap longer than 1 s: the recovery starts again here.
        monitor.frame(after(HEARTBEAT))
        assertEquals(CameraStatus.Unavailable(CameraProblem.Disconnected), monitor.status)
        monitor.frame(after(HEARTBEAT))
        assertEquals(CameraStatus.Live, monitor.status)
    }

    @Test
    fun `the watchdog after a recovered problem fires again on a new stall`() {
        monitor.bound(now())
        monitor.tick(after(5_000))
        heartbeats(1_500)
        assertEquals(CameraStatus.Live, monitor.status, "NoFrames recovers with frames")
        monitor.tick(after(5_000))
        assertEquals(CameraStatus.Unavailable(CameraProblem.NoFrames), monitor.status)
    }

    @Test
    fun `a sticky problem ignores frames and other problems until the next bind`() {
        listOf(CameraProblem.NoPermission, CameraProblem.PrivacyBlocked, CameraProblem.BindFailed).forEach { sticky ->
            val monitor = CameraMonitor()
            monitor.bound(now())
            monitor.problem(sticky)
            repeat(10) { monitor.frame(after(HEARTBEAT)) }
            monitor.problem(CameraProblem.Disconnected)
            assertEquals(CameraStatus.Unavailable(sticky), monitor.status, "$sticky: black frames do not clear it")

            monitor.bound(now())
            assertEquals(CameraStatus.Starting, monitor.status, "$sticky: the next resume starts again")
            assertTrue(monitor.everUnavailable, "$sticky: the latch stays for the entry")
        }
    }

    @Test
    fun `a decoder that keeps failing sends no frames, so it cannot flap back, and the watchdog follows`() {
        monitor.bound(now())
        heartbeats(1_000)
        monitor.problem(CameraProblem.DecoderFailing)
        monitor.tick(after(5_000))
        assertEquals(CameraStatus.Unavailable(CameraProblem.DecoderFailing), monitor.status)
        heartbeats(1_500)
        assertEquals(CameraStatus.Live, monitor.status, "decoding again for 1 s")
    }

    @Test
    fun `a camera that reads no code for 60 s offers the fallback, keeps the viewfinder, and any code read stops it`() {
        monitor.bound(now())
        heartbeats(59_500)
        assertFalse(monitor.nothingRead)
        heartbeats(500)
        assertTrue(monitor.nothingRead)
        assertTrue(monitor.offersFallback)
        assertEquals(CameraStatus.Live, monitor.status, "the viewfinder stays")
        assertFalse(monitor.everUnavailable)

        val reading = CameraMonitor()
        reading.bound(now())
        reading.code(after(1_000))
        repeat(200) { reading.frame(after(HEARTBEAT)) }
        reading.tick(now())
        assertFalse(reading.nothingRead, "a code was read: the camera works")
        assertFalse(reading.offersFallback)
    }

    @Test
    fun `a new bind starts the watchdog and the nothing-read time over`() {
        monitor.bound(now())
        monitor.tick(after(4_900))
        monitor.bound(after(30.seconds.inWholeMilliseconds))
        monitor.tick(after(4_900))
        assertEquals(CameraStatus.Starting, monitor.status, "4.9 s after the resume")
        monitor.tick(after(100))
        assertEquals(CameraStatus.Unavailable(CameraProblem.NoFrames), monitor.status, "5.0 s after the resume")
    }

    private companion object {
        const val HEARTBEAT = 500L
    }
}
