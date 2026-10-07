package com.yawnandpawn.app.android.qr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.yawnandpawn.app.ui.qr.CameraPermission
import com.yawnandpawn.app.ui.qr.CameraProblem
import com.yawnandpawn.app.ui.qr.CodeScanner
import com.yawnandpawn.app.ui.qr.ConsecutiveFrames
import com.yawnandpawn.app.ui.qr.ScanEvent
import com.yawnandpawn.app.ui.qr.ScanResult

/**
 * The camera of host tests (Story 3.10; Epic 2 retro: no test needs a real camera). [frames] feeds analysed frames
 * through the real 3-frames rule, as `CodeAnalyzer` does, to the scan in composition. Without [permitted] the scan reports
 * the camera unavailable at once, as the CameraX scanner does.
 *
 * Story 3.11: [heartbeat] is the analyser's frame heartbeat (frames send none by themselves), [fail] a camera problem;
 * [starts] and [stops] count the binds and releases, and [previews] the viewfinders drawing the picture.
 */
internal class FakeCodeScanner(
    var permitted: Boolean = true,
    /** Each started camera reports itself open at once, as a quick CameraX start does; else the test calls [open]. */
    private val opensAtOnce: Boolean = true,
) : CodeScanner {
    private var listener: ((ScanEvent) -> Unit)? = null
    private val consecutive = ConsecutiveFrames()

    /** A scan is in composition (the camera would be bound). */
    val running: Boolean
        get() = listener != null

    /** The torch as the scan last asked for it; off once the scan is released. */
    var torchOn: Boolean = false
        private set

    /** How many times a camera was started (bound), as the CameraX scanner binds it once per scan in composition. */
    var starts = 0
        private set

    /** How many times a started camera was released (the scan left composition). */
    var stops = 0
        private set

    /** Viewfinders drawing the scan's picture now. */
    var previews = 0
        private set

    override fun cameraPermitted(): Boolean = permitted

    @Composable
    override fun Scan(
        torchOn: Boolean,
        onEvent: (ScanEvent) -> Unit,
    ) {
        val events by rememberUpdatedState(onEvent)
        if (!permitted) {
            LaunchedEffect(Unit) { events(ScanEvent.CameraUnavailable(CameraProblem.NoPermission)) }
            return
        }
        SideEffect { this.torchOn = torchOn }
        DisposableEffect(Unit) {
            starts++
            listener = { events(it) }
            if (opensAtOnce) open()
            onDispose {
                stops++
                listener = null
                this@FakeCodeScanner.torchOn = false
            }
        }
    }

    @Composable
    override fun Preview() {
        DisposableEffect(Unit) {
            previews++
            onDispose { previews-- }
        }
    }

    @Composable
    override fun Feed(
        torchOn: Boolean,
        onEvent: (ScanEvent) -> Unit,
    ) {
        Scan(torchOn, onEvent)
        if (permitted) Preview()
    }

    /** [count] analysed frames that each hold [codes]; a due report of stable codes reaches the scan's listener. */
    fun frames(
        count: Int,
        vararg codes: ScanResult,
    ) {
        repeat(count) { consecutive.frame(codes.toList())?.let(::emit) }
    }

    /** The camera reports itself open (CameraX's `OPEN` state). */
    fun open() = emit(ScanEvent.Opened)

    /** The analyser's heartbeat: frames arrive. */
    fun heartbeat() = emit(ScanEvent.Frame)

    /** The running camera reports [problem] (the real scanner's failures are in `CameraXCodeScannerTest`). */
    fun fail(problem: CameraProblem = CameraProblem.CameraError) = emit(ScanEvent.CameraUnavailable(problem))

    private fun emit(event: ScanEvent) = checkNotNull(listener) { "no camera scan is running" }.invoke(event)
}

/** A camera permission under test control: [answer] is what the dialog answers. */
internal class FakeCameraPermission(
    var granted: Boolean = true,
    private val answer: Boolean = granted,
) : CameraPermission {
    var requests = 0
        private set

    var settingsOpened = 0
        private set

    override fun isGranted(): Boolean = granted

    override suspend fun request(): Boolean {
        requests++
        granted = answer
        return answer
    }

    override fun openSettings() {
        settingsOpened++
    }
}
