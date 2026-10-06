package com.yawnandpawn.app.ui.qr

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode

/**
 * A code the scanner read (Story 3.10): its [format] and [rawValue]. The raw value lives only in memory, between the
 * analyser and [code]; it is never logged ([toString] leaves it out), sent to the engine or stored.
 */
data class ScanResult(
    val format: CodeFormat,
    val rawValue: String,
) {
    /** The code as the check stores and compares it, or null for a blank value. */
    val code: RegisteredCode?
        get() = RegisteredCode.of(format, rawValue)

    override fun toString(): String = "ScanResult($format)"
}

/** What a running [CodeScanner] reports. */
sealed interface ScanEvent {
    /** The same code was seen in [ConsecutiveFrames.REQUIRED] frames in a row. */
    data class Detected(
        val result: ScanResult,
    ) : ScanEvent

    /** The camera cannot be used: the permission is missing, or the camera could not be started (Story 3.10). */
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
     * permission, and never asks for it.
     */
    @Composable
    fun Feed(
        torchOn: Boolean,
        onEvent: (ScanEvent) -> Unit,
    )
}

/**
 * The 3-frames rule (Story 3.10): a code counts only once the same code is seen in [required] analysed frames in a row,
 * so a half-read or passing code is never submitted. Each frame passes every code it holds; a frame without the code
 * being followed starts over. Once a code is reported its streak starts again, so a code held still is reported every
 * [required] frames (the [RepeatGate] decides what is submitted). Not thread-safe: one analyser thread feeds it.
 */
class ConsecutiveFrames(
    private val required: Int = REQUIRED,
) {
    private var candidate: ScanResult? = null
    private var streak = 0

    /** The code to report after this frame's [codes], or null. */
    fun frame(codes: List<ScanResult>): ScanResult? {
        val followed = candidate?.takeIf { it in codes }
        if (followed == null) {
            candidate = codes.firstOrNull()
            streak = if (candidate == null) 0 else 1
        } else {
            streak++
        }
        return if (streak >= required) {
            streak = 0
            candidate
        } else {
            null
        }
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
