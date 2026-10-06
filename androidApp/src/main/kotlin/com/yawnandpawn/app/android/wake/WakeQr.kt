package com.yawnandpawn.app.android.wake

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.session.FallbackReason
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.ui.qr.CodeScanner
import com.yawnandpawn.app.ui.qr.RepeatGate
import com.yawnandpawn.app.ui.qr.ScanEvent
import com.yawnandpawn.app.ui.wake.CheckContent

/**
 * The QR/Barcode part of [WakeCheck] (Story 3.10): the [scanner]'s feed in the viewfinder ([feed]), each stable code
 * submitted as `CheckAnswerSubmitted(Code)` at most once per 2 s per code, the torch, and whether the camera can be used
 * (the permission granted, read now and never asked, and the scanner not failed). While it cannot, the fallback is asked
 * for with reason `CameraUnavailable` ([fallbackReason]), so "Can't do this check?" shows at once (Story 3.9).
 */
internal class WakeQr(
    private val scanner: CodeScanner?,
    private val monotonicClock: MonotonicClock,
) {
    /** The scanner said the camera cannot be used; recovery is Story 3.11. */
    private var cameraFailed by mutableStateOf(false)

    /** The viewfinder's torch toggle, UI only. */
    var torchOn by mutableStateOf(false)
        private set

    /** At most one submission of the same code per 2 s. */
    private val repeats = RepeatGate()

    /** The registered code of the entry on screen, so a stable code that matches it is preferred (Story 3.10 review). */
    var expected: RegisteredCode? = null

    /** Whether the camera can be used now. */
    fun cameraAvailable(): Boolean = scanner?.cameraPermitted() == true && !cameraFailed

    /**
     * Why the fallback would be asked for on [state] now: on a QR/Barcode entry whose camera is unavailable
     * `CameraUnavailable`, so the link shows at once; otherwise failed attempts.
     */
    fun fallbackReason(state: SessionState): FallbackReason {
        val entry = (state as? SessionState.Active)?.session?.checkRun?.currentEntry
        val noCamera = entry?.type == CheckType.QrBarcode && !cameraAvailable()
        return if (noCamera) FallbackReason.CameraUnavailable else FallbackReason.FailedAttempts
    }

    /** Whether [content] shows the live camera: a QR check with the camera available. */
    fun showsCamera(content: CheckContent?): Boolean = scanner != null && content is CheckContent.QrBarcode && content.cameraAvailable

    /**
     * The live camera feed of the QR check's viewfinder, for as long as [showsCamera]: the screen remembers it, so the
     * grace countdown's redraws never rebind the camera (Story 3.10 review); the torch is read inside it. Each stable
     * code is submitted through [send] (the engine decides). Null without a scanner.
     */
    fun feed(send: (List<SessionEvent>) -> Unit): (@Composable BoxScope.() -> Unit)? {
        val camera = scanner ?: return null
        return { camera.Feed(torchOn = torchOn, onEvent = { onScan(it, send) }) }
    }

    /**
     * What the scanner reported: a stable code is submitted (at most once per 2 s per code); a dead camera is shown.
     * With several codes in view (Story 3.10 review), the registered one is submitted when it is among the stable ones;
     * a different code is submitted only when it is the one code in view, so a second code next to the right one never
     * counts as a failed attempt. The engine still decides every answer.
     */
    fun onScan(
        event: ScanEvent,
        send: (List<SessionEvent>) -> Unit,
    ) {
        when (event) {
            is ScanEvent.Detected -> {
                val codes = event.results.mapNotNull { it.code }
                val code = codes.firstOrNull { it == expected } ?: event.single?.code ?: return
                if (repeats.admit(code, monotonicClock.elapsedMillis())) {
                    send(listOf(SessionEvent.UserInteracted, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Code(code))))
                }
            }

            ScanEvent.CameraUnavailable -> {
                cameraFailed = true
            }
        }
    }

    /** The torch toggle of the viewfinder. */
    fun toggleTorch() {
        torchOn = !torchOn
    }
}
