package com.yawnandpawn.app.ui.checks

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckResult
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.ui.checksetup.CheckPreviewUiState
import com.yawnandpawn.app.ui.qr.ScanEvent
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType

/**
 * A QR/Barcode "Try it" (Story 3.10, the 3.6 preview): the viewfinder with the camera feed the editor provides, against
 * the draft's [code]. A stable code is checked by the core plugin's `validate`, sending no engine event: the registered
 * code (preferred among several in view) solves it, another code alone in view shows the wrong-code line. Without the
 * camera it shows "Camera isn't available.", as the real check does.
 */
class QrTrial private constructor(
    private val code: RegisteredCode,
    private val content: CheckContent.QrBarcode,
    private val done: Boolean,
    /** The different code shown last: held up, it is not counted (and buzzed) again on every report. */
    private val lastWrong: RegisteredCode? = null,
) : CheckTrial {
    override val state: CheckPreviewUiState
        get() = CheckPreviewUiState(content = content, done = done)

    override fun onIntent(intent: WakeIntent): CheckTrial =
        if (intent == WakeIntent.TorchToggled && !done) copy(content = content.copy(torchOn = !content.torchOn)) else this

    /** What the preview's camera reported. */
    fun onScan(event: ScanEvent): QrTrial =
        when {
            done -> {
                this
            }

            event == ScanEvent.CameraUnavailable -> {
                copy(content = content.copy(cameraAvailable = false))
            }

            event is ScanEvent.Detected -> {
                val scanned = event.results.mapNotNull { it.code }.firstOrNull { it == code } ?: event.single?.code
                scanned?.let(::checked) ?: this
            }

            else -> {
                this
            }
        }

    private fun checked(scanned: RegisteredCode): QrTrial =
        when (CoreCheckType.QrBarcode.validate(Puzzle.Code(code), position = 0, answer = CheckAnswer.Code(scanned))) {
            CheckResult.Correct, CheckResult.ItemCorrect -> {
                copy(done = true)
            }

            CheckResult.Wrong, CheckResult.WrongRestart -> {
                if (scanned == lastWrong) {
                    this
                } else {
                    copy(content = content.copy(wrongCode = true, wrongAttempts = content.wrongAttempts + 1), lastWrong = scanned)
                }
            }
        }

    private fun copy(
        content: CheckContent.QrBarcode = this.content,
        done: Boolean = this.done,
        lastWrong: RegisteredCode? = this.lastWrong,
    ) = QrTrial(code, content, done, lastWrong)

    companion object {
        /** A trial against the registered [code], scanning. */
        fun start(code: RegisteredCode): QrTrial = QrTrial(code, CheckContent.QrBarcode(), done = false)
    }
}
