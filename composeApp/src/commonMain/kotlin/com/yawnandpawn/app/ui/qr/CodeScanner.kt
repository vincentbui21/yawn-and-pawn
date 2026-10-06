package com.yawnandpawn.app.ui.qr

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode

/**
 * A code the scanner read (Story 3.10): its [format] and [rawValue]. The raw value lives only in memory, between the
 * analyser and [code]; it is never logged ([toString] leaves it out), sent to the engine or stored.
 *
 * A code with no text, such as a binary QR, is [binary]: [rawValue] then holds its bytes, one char (0 to 255) per byte
 * ([ofBytes]), and [code] fingerprints those bytes (Story 3.10 review), so it can be registered and scanned too.
 */
data class ScanResult(
    val format: CodeFormat,
    val rawValue: String,
    val binary: Boolean = false,
) {
    /** The code as the check stores and compares it, or null for a blank value. */
    val code: RegisteredCode?
        get() =
            if (binary) {
                RegisteredCode.ofBytes(format, ByteArray(rawValue.length) { rawValue[it].code.toByte() })
            } else {
                RegisteredCode.of(format, rawValue)
            }

    override fun toString(): String = "ScanResult($format)"

    companion object {
        private const val BYTE_MASK = 0xFF

        /** A code with no text, read as [bytes]. */
        fun ofBytes(
            format: CodeFormat,
            bytes: ByteArray,
        ): ScanResult = ScanResult(format, bytes.joinToString("") { (it.toInt() and BYTE_MASK).toChar().toString() }, binary = true)
    }
}

/** What a running [CodeScanner] reports. */
sealed interface ScanEvent {
    /**
     * Codes seen in [ConsecutiveFrames.REQUIRED] frames in a row: [results] holds every such code in view, in frame
     * order, and [others] says another code is in view but not stable yet (Story 3.10 review: per-code streaks).
     */
    data class Detected(
        val results: List<ScanResult>,
        val others: Boolean = false,
    ) : ScanEvent {
        constructor(result: ScanResult) : this(listOf(result))

        /** The one code in view, stable, or null when several codes are in view (none of them can be picked safely). */
        val single: ScanResult?
            get() = results.singleOrNull()?.takeIf { !others }
    }

    /**
     * The camera cannot be used: the permission is missing, the camera could not be started or failed (in use, disabled
     * by policy or the privacy toggle when the system refuses it, a fatal error), or every frame fails to decode.
     */
    data object CameraUnavailable : ScanEvent
}

/**
 * The camera code scanner port (Story 3.10, AD-9). The Android app provides the CameraX + bundled ML Kit scanner; tests
 * use `FakeCodeScanner` (the `:androidApp` host tests), so no host or device test needs a camera. Frames are analysed on
 * the device only and never stored.
 *
 * 3.6 hook: the QR "Try it" preview provides `LocalViewfinderFeed` with [Feed] and compares each stable code with the
 * draft's `CheckEntry.code` itself, sending no engine event.
 */
interface CodeScanner {
    /** Whether the app may use the camera now (the `CAMERA` permission is granted). Never asks. */
    fun cameraPermitted(): Boolean

    /**
     * The camera feed for a viewfinder, while it is in composition: it starts the camera, lights the torch while
     * [torchOn], reports each stable code and a camera that cannot be used through [onEvent] (on the main thread), and
     * releases the camera when it leaves composition. It reports [ScanEvent.CameraUnavailable] at once without the
     * permission, and never asks for it. It never throws: every failure is [ScanEvent.CameraUnavailable].
     */
    @Composable
    fun Feed(
        torchOn: Boolean,
        onEvent: (ScanEvent) -> Unit,
    )
}

/**
 * The 3-frames rule (Story 3.10): a code counts only once the same code is seen in [required] analysed frames in a row,
 * so a half-read or passing code is never submitted. Each code in view has its own streak (Story 3.10 review), so a
 * second code in view is never starved by the first; a code missing from a frame starts over. A report is due each time
 * a code's streak reaches a multiple of [required] (a code held still is reported every [required] frames; the
 * [RepeatGate] decides what is submitted), and it lists every stable code in view. Not thread-safe: one analyser thread
 * feeds it.
 */
class ConsecutiveFrames(
    private val required: Int = REQUIRED,
) {
    private var streaks: Map<ScanResult, Int> = emptyMap()

    /** The report after this frame's [codes], or null when none is due. */
    fun frame(codes: List<ScanResult>): ScanEvent.Detected? {
        val inView = codes.distinct()
        // Bounded: after the first `required` frames a streak cycles through required + 1 .. 2 × required.
        streaks = inView.associateWith { (streaks[it] ?: 0).let { s -> if (s < 2 * required) s + 1 else required + 1 } }
        if (streaks.values.none { it % required == 0 }) return null
        val stable = inView.filter { streaks.getValue(it) >= required }
        return ScanEvent.Detected(stable, others = stable.size < inView.size)
    }

    companion object {
        /** "The same value seen in 3 consecutive frames" (EXPERIENCE.md, FR-PWK-7). */
        const val REQUIRED = 3
    }
}

/**
 * The wake check's repeat rule (Story 3.10): the same code is submitted at most once per [windowMillis] (2 s), so one
 * wrong code held in front of the camera counts as one failed attempt per 2 s, not one per frame. Another code passes
 * at once. Times are monotonic milliseconds.
 */
class RepeatGate(
    private val windowMillis: Long = WINDOW_MILLIS,
) {
    private var last: RegisteredCode? = null
    private var lastAt = 0L

    /** Whether [code], seen at [nowMillis], is submitted; when it is, the window starts again. */
    fun admit(
        code: RegisteredCode,
        nowMillis: Long,
    ): Boolean {
        if (code == last && nowMillis - lastAt < windowMillis) return false
        last = code
        lastAt = nowMillis
        return true
    }

    companion object {
        /** "The same wrong code seen again within 2 s is not submitted twice." */
        const val WINDOW_MILLIS = 2_000L
    }
}
