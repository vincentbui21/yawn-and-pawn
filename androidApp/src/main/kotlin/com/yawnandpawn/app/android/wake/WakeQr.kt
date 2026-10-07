package com.yawnandpawn.app.android.wake

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.FallbackReason
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.ui.qr.CameraStatus
import com.yawnandpawn.app.ui.qr.CodeScanner
import com.yawnandpawn.app.ui.qr.RepeatGate
import com.yawnandpawn.app.ui.qr.ScanEvent
import com.yawnandpawn.app.ui.wake.CheckContent
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The QR/Barcode part of [WakeCheck] (Stories 3.10 and 3.11): the [scanner]'s camera ([Scan]) and its picture in the
 * viewfinder ([preview]), each stable code submitted as `CheckAnswerSubmitted(Code)` at most once per 2 s per code, the
 * torch, and whether the camera can be used.
 *
 * Story 3.11: the [camera] follows each entry (session, ring, entry) with a `CameraMonitor`: the scanner's heartbeats,
 * codes and problems, and a 5 s no-frame watchdog that ticks every [WATCHDOG_TICK] on the [monotonicClock] while the
 * screen is resumed. "Camera isn't available. Pick a fallback check." shows while the status is unavailable (or the
 * permission is missing, read now and never asked), and the viewfinder comes back after 1 s of frames. Once the camera
 * was unavailable on the entry, or read no code for 60 s, the fallback is asked for with reason `CameraUnavailable`
 * ([fallbackReason]) for the rest of the entry, so "Can't do this check?" stays (Story 3.9).
 */
internal class WakeQr(
    private val scanner: CodeScanner?,
    private val monotonicClock: MonotonicClock,
    logger: Logger,
) {
    /** The camera of the entry on screen, as the screen sees it. */
    private val camera = WakeCamera(monotonicClock, logger)

    /** The viewfinder's torch switch, UI only; kept through a pause, so the rebound camera lights it again. */
    var torchOn by mutableStateOf(false)
        private set

    /** At most one submission of the same code per 2 s. */
    private val repeats = RepeatGate()

    /** The registered code of the entry on screen, so a stable code that matches it is preferred (Story 3.10 review). */
    var expected: RegisteredCode? = null

    /** Whether the app may use the camera now (read now, never asked). */
    fun permitted(): Boolean = scanner?.cameraPermitted() == true

    /** Whether the camera can be used on [state]'s entry now: permitted, and not unavailable. */
    fun cameraAvailable(state: SessionState): Boolean = permitted() && camera.statusOf(CameraEntry.of(state)) !is CameraStatus.Unavailable

    /**
     * Why the fallback would be asked for on [state] now: on a QR/Barcode entry without the permission, or whose camera
     * was unavailable or read nothing on this entry, `CameraUnavailable`, so the link shows at once and stays; otherwise
     * failed attempts.
     */
    fun fallbackReason(state: SessionState): FallbackReason {
        val key = CameraEntry.of(state)
        val noCamera = key != null && (!permitted() || camera.offersFallback(key))
        return if (noCamera) FallbackReason.CameraUnavailable else FallbackReason.FailedAttempts
    }

    /**
     * Follows the entry [state] waits on (from the screen, after each composition): a new QR entry gets a new monitor,
     * started at once when the scan runs; a missing permission latches the fallback for the entry.
     */
    fun follow(state: SessionState) {
        val key = CameraEntry.of(state)
        camera.follow(key)
        if (key != null && !permitted()) camera.latch(key)
    }

    /** Whether [content] shows the live camera: a QR check with the camera available. */
    fun showsCamera(content: CheckContent?): Boolean = scanner != null && content is CheckContent.QrBarcode && content.cameraAvailable

    /**
     * The picture of the running scan for the viewfinder, for as long as [showsCamera]; null without a scanner. The
     * screen remembers it, so the grace countdown's redraws never rebuild it (Story 3.10 review).
     */
    fun preview(): (@Composable BoxScope.() -> Unit)? {
        val scan = scanner ?: return null
        return { scan.Preview() }
    }

    /**
     * The camera of the QR check, while the screen is resumed on a QR check with the permission (Story 3.11): composed
     * next to the screen, never inside the viewfinder, so it keeps running under the camera-unavailable message and a
     * camera that comes back is noticed. Each `ON_RESUME` binds it again and restarts the watchdog; the watchdog ticks
     * only while resumed. Each stable code is submitted through [send] (the engine decides).
     */
    @Composable
    fun Scan(send: (List<SessionEvent>) -> Unit) {
        val scan = scanner ?: return
        val sending by rememberUpdatedState(send)
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            // Added while resumed, the observer gets ON_RESUME at once: the first bind.
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_PAUSE) {
                        camera.watch(resumed = event == Lifecycle.Event.ON_RESUME)
                    }
                }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                camera.watch(resumed = false)
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(WATCHDOG_TICK)
                camera.tick()
            }
        }
        scan.Scan(torchOn = torchOn, onEvent = { onScan(it, sending) })
    }

    /**
     * What the scanner reported, which the [camera] sees first: a stable code is submitted (at most once per 2 s per
     * code). With several codes in view (Story 3.10 review), the registered one is submitted when it is among the stable
     * ones; a different code is submitted only when it is the one code in view, so a second code next to the right one
     * never counts as a failed attempt. The engine still decides every answer.
     */
    fun onScan(
        event: ScanEvent,
        send: (List<SessionEvent>) -> Unit,
    ) {
        camera.onEvent(event)
        if (event !is ScanEvent.Detected) return
        val codes = event.results.mapNotNull { it.code }
        val code = codes.firstOrNull { it == expected } ?: event.single?.code ?: return
        if (repeats.admit(code, monotonicClock.elapsedMillis())) {
            send(listOf(SessionEvent.UserInteracted, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Code(code))))
        }
    }

    /** The torch switch of the viewfinder. */
    fun toggleTorch() {
        torchOn = !torchOn
    }

    companion object {
        /** How often the watchdog reads the clock; "5.0 s" is then seen within a quarter second. */
        val WATCHDOG_TICK: Duration = 250.milliseconds
    }
}
