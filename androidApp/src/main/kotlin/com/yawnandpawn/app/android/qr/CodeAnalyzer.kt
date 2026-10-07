package com.yawnandpawn.app.android.qr

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.yawnandpawn.app.ui.qr.ConsecutiveFrames
import com.yawnandpawn.app.ui.qr.ScanEvent
import com.yawnandpawn.app.ui.qr.ScanResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the codes in one camera frame (Story 3.10). It calls `done` once, from any thread, with the codes found (an
 * empty list when there are none), or with null when the decoder failed on the frame (Story 3.10 review: a failure is
 * not "no code", so a decoder that keeps failing is noticed). It only reads the frame in memory: it never keeps, copies
 * or writes it anywhere.
 */
fun interface FrameDecoder {
    fun decode(
        frame: ImageProxy,
        done: (List<ScanResult>?) -> Unit,
    )
}

/** A [FrameDecoder] that holds a model to release once the camera stops. */
interface CloseableFrameDecoder :
    FrameDecoder,
    AutoCloseable

/**
 * The analyser wrapper of the QR/Barcode scanner (Story 3.10): each frame goes to [decoder], is closed as soon as it is
 * read (so CameraX can deliver the next one, and no frame outlives its analysis), and its codes go through the 3-frames
 * rule ([ConsecutiveFrames]); a due report goes to [onStable]. Frames are analysed on the device only; nothing here
 * writes an image or a code to storage (`CodeAnalyzerTest`).
 *
 * Story 3.10 review: each frame is finished exactly once, whatever the decoder does (calls `done` twice, calls it and
 * then throws, or throws an `Error` such as `UnsatisfiedLinkError`), so a frame is never closed twice and never left
 * open (which would stall the analysis). After [maxFailures] failed frames in a row (a model or native library that
 * cannot run), [onFailing] is called once, so the scanner reports the camera unavailable instead of a viewfinder that
 * can never read the code.
 *
 * Story 3.11: a frame the decoder read (with or without a code) is a heartbeat for the wake check's watchdog: [onFrame]
 * is called at most once per [HEARTBEAT_MILLIS] of [elapsedMillis] (the monotonic clock), and never for a failed frame,
 * so a decoder that keeps failing looks like a camera with no frames. The [maxFailures] streak is reported again after
 * a frame decodes.
 */
class CodeAnalyzer(
    private val decoder: FrameDecoder,
    private val onStable: (ScanEvent.Detected) -> Unit,
    private val onFailing: () -> Unit = {},
    private val frames: ConsecutiveFrames = ConsecutiveFrames(),
    private val maxFailures: Int = MAX_FAILURES,
    private val onFrame: () -> Unit = {},
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) : ImageAnalysis.Analyzer {
    /** Failed frames in a row; guarded by [frames]. */
    private var failures = 0

    /** When the last heartbeat was sent; guarded by [frames]. */
    private var lastBeat: Long? = null

    override fun analyze(image: ImageProxy) {
        val finished = AtomicBoolean(false)
        val done = { codes: List<ScanResult>? -> if (finished.compareAndSet(false, true)) finish(image, codes) }
        // A decoder that throws must not stop the analysis or leak the frame: the frame counts as a failed one.
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            decoder.decode(image, done)
        } catch (e: Throwable) {
            done(null)
        }
    }

    private fun finish(
        image: ImageProxy,
        codes: List<ScanResult>?,
    ) {
        image.close()
        var failing = false
        var beat = false
        val report =
            synchronized(frames) {
                failures = if (codes == null) failures + 1 else 0
                failing = failures == maxFailures
                if (codes != null) {
                    val now = elapsedMillis()
                    beat = lastBeat.let { it == null || now - it >= HEARTBEAT_MILLIS }
                    if (beat) lastBeat = now
                }
                frames.frame(codes.orEmpty())
            }
        if (beat) onFrame()
        report?.let(onStable)
        if (failing) onFailing()
    }

    companion object {
        /** At most one heartbeat per 500 ms (Story 3.11): enough for a 5 s watchdog and a 1 s recovery. */
        const val HEARTBEAT_MILLIS = 500L

        /** Failed frames in a row before the scanner gives up (about a third of a second at 30 frames a second). */
        const val MAX_FAILURES = 10
    }
}
