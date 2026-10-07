package com.yawnandpawn.app.ui.qr

import com.yawnandpawn.app.testing.FakeMonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Story 3.11: the wake QR check's camera watchdog, recovery and latches, on the monotonic clock only (the monitor never
 * sees the wall clock; a wall-clock jump during the ring is checked in `QrCameraFailureTest`).
 */
class CameraMonitorTest {
    private val clock = FakeMonotonicClock(elapsedMillis = 10_000)
    private val monitor = CameraMonitor()

    private fun now(): Long = clock.elapsedMillis()

    private fun after(millis: Long): Long {
        clock.advanceBy(millis.milliseconds)
        return now()
    }

    /** Bound and open at once, as a quick CameraX start. */
    private fun start() {
        monitor.bound(now())
        monitor.opened(now())
    }

    /** Frames every 500 ms (the analyser's heartbeat) for [millis]. */
    private fun heartbeats(millis: Long) {
        repeat((millis / HEARTBEAT).toInt()) {
            monitor.frame(after(HEARTBEAT))
            monitor.tick(now())
        }
    }

    @Test
    fun `open with no frame is Starting at 4_9 s and NoFrames at 5_0 s`() {
        start()
        monitor.tick(after(4_900))
        assertEquals(CameraStatus.Starting, monitor.status, "4.9 s: the viewfinder, no message")
        assertFalse(monitor.everUnavailable)

        monitor.tick(after(100))
        assertEquals(CameraStatus.Unavailable(CameraProblem.NoFrames), monitor.status, "5.0 s: the message")
        assertTrue(monitor.everUnavailable)
        assertTrue(monitor.offersFallback)
    }

    @Test
    fun `the 5 s count from the camera's open, not the bind, so a slow cold start is not a dead camera (review)`() {
        val bound = now()
        monitor.bound(bound)
        monitor.tick(after(4_000))
        monitor.opened(now()) // CameraX took 4 s to open the camera.
        monitor.tick(bound + 8_400)
        assertEquals(CameraStatus.Starting, monitor.status, "8.4 s after the bind, 4.4 s after the open")
        monitor.frame(bound + 8_500) // The first decoded frame, 4.5 s after the open: a cold start, not a dead camera.
        monitor.tick(bound + 8_500)
        assertEquals(CameraStatus.Live, monitor.status)
        assertFalse(monitor.everUnavailable)

        val never = CameraMonitor()
        never.bound(bound)
        never.tick(bound + 14_999)
        assertEquals(CameraStatus.Starting, never.status, "not open yet")
        never.tick(bound + 15_000)
        assertEquals(CameraStatus.Unavailable(CameraProblem.NoFrames), never.status, "a camera that never opens: 15 s at most")
    }

    @Test
    fun `frames keep the camera Live and the watchdog never fires`() {
        start()
        heartbeats(30_000)
        assertEquals(CameraStatus.Live, monitor.status)
        assertFalse(monitor.everUnavailable)
    }

    @Test
    fun `a stall mid-scan fires at the last frame plus 5 s`() {
        start()
        heartbeats(2_000)
        val last = now()
        monitor.tick(last + 4_999)
        assertEquals(CameraStatus.Live, monitor.status)
        monitor.tick(last + 5_000)
        assertEquals(CameraStatus.Unavailable(CameraProblem.NoFrames), monitor.status)
    }

    @Test
    fun `an error mid-scan is unavailable at once, and 1 s of frames brings it back while the latch stays`() {
        start()
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
    fun `recovery boundary - frames every 100 ms from f are still unavailable at f + 900 and Live at f + 1000 (review)`() {
        start()
        monitor.problem(CameraProblem.Disconnected)
        val f = after(3_000)
        monitor.frame(f)
        (1..9).forEach { monitor.frame(f + it * 100L) }
        assertEquals(CameraStatus.Unavailable(CameraProblem.Disconnected), monitor.status, "f + 900")
        monitor.frame(f + 1_000)
        assertEquals(CameraStatus.Live, monitor.status, "f + 1000")
    }

    @Test
    fun `a disconnect recovers the same way, and a gap in the frames starts the recovery over`() {
        start()
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
        start()
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
    fun `a failing decoder stays unavailable while no frame decodes (the analyser sends none), and 1 s of decoding brings it back`() {
        start()
        heartbeats(1_000)
        monitor.problem(CameraProblem.DecoderFailing)
        repeat(20) { monitor.tick(after(HEARTBEAT)) } // 10 s of failed frames: no heartbeat (CodeAnalyzerTest).
        assertEquals(CameraStatus.Unavailable(CameraProblem.DecoderFailing), monitor.status, "the watchdog does not replace it")
        monitor.frame(after(HEARTBEAT))
        monitor.frame(after(HEARTBEAT))
        assertEquals(CameraStatus.Unavailable(CameraProblem.DecoderFailing), monitor.status, "one decoded heartbeat is not enough")
        monitor.frame(after(HEARTBEAT))
        assertEquals(CameraStatus.Live, monitor.status, "decoding again for 1 s")
    }

    @Test
    fun `a camera that reads no code for 60 s offers the fallback at exactly 60 s and keeps the viewfinder`() {
        val bound = now()
        start()
        heartbeats(59_500)
        monitor.tick(bound + 59_999)
        assertFalse(monitor.nothingRead, "59.999 s")
        monitor.frame(bound + 60_000)
        monitor.tick(bound + 60_000)
        assertTrue(monitor.nothingRead, "60 s")
        assertTrue(monitor.offersFallback)
        assertEquals(CameraStatus.Live, monitor.status, "the viewfinder stays")
        assertFalse(monitor.everUnavailable)
    }

    @Test
    fun `the 60 s count from the last code read, so one wrong code does not disarm it (review)`() {
        val bound = now()
        start()
        monitor.code(bound + 1_000) // A wrong code at 1 s, then black frames.
        clock.set(bound + 1_000)
        heartbeats(59_500)
        monitor.tick(bound + 60_999)
        assertFalse(monitor.nothingRead, "59.999 s after the code")
        monitor.tick(bound + 61_000)
        assertTrue(monitor.nothingRead, "60 s after the code")

        val reading = CameraMonitor()
        reading.bound(now())
        repeat(120) {
            reading.code(after(HEARTBEAT)) // Codes keep being read.
            reading.tick(now())
        }
        assertFalse(reading.nothingRead, "codes read all along: the camera works")
    }

    @Test
    fun `a new bind starts the watchdog and the nothing-read time over`() {
        start()
        monitor.tick(after(4_900))
        monitor.bound(after(30.seconds.inWholeMilliseconds))
        monitor.opened(now())
        monitor.tick(after(4_900))
        assertEquals(CameraStatus.Starting, monitor.status, "4.9 s after the resume")
        monitor.tick(after(100))
        assertEquals(CameraStatus.Unavailable(CameraProblem.NoFrames), monitor.status, "5.0 s after the resume")
    }

    private companion object {
        const val HEARTBEAT = 500L
    }
}
