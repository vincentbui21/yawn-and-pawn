package com.yawnandpawn.app.android.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.yawnandpawn.app.ui.qr.ConsecutiveFrames
import com.yawnandpawn.app.ui.qr.ScanResult

/**
 * Reads the codes in one camera frame (Story 3.10). It calls `done` exactly once, from any thread, with the codes found
 * (none on a failure). It only reads the frame in memory: it never keeps, copies or writes it anywhere.
 */
fun interface FrameDecoder {
    fun decode(
        frame: ImageProxy,
        done: (List<ScanResult>) -> Unit,
    )
}

/**
 * The analyser wrapper of the QR/Barcode scanner (Story 3.10): each frame goes to [decoder], is closed as soon as it is
 * read (so CameraX can deliver the next one, and no frame outlives its analysis), and its codes go through the 3-frames
 * rule ([ConsecutiveFrames]); a stable code goes to [onStable]. Frames are analysed on the device only; nothing here
 * writes an image or a code to storage (`CodeAnalyzerTest`).
 */
class CodeAnalyzer(
    private val decoder: FrameDecoder,
    private val onStable: (ScanResult) -> Unit,
    private val frames: ConsecutiveFrames = ConsecutiveFrames(),
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        // A decoder that throws must not stop the analysis or leak the frame: the frame counts as one without a code.
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            decoder.decode(image) { codes -> finish(image, codes) }
        } catch (e: RuntimeException) {
            finish(image, emptyList())
        }
    }

    private fun finish(
        image: ImageProxy,
        codes: List<ScanResult>,
    ) {
        image.close()
        synchronized(frames) { frames.frame(codes) }?.let(onStable)
    }
}
