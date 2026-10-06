package com.yawnandpawn.app.android.qr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.yawnandpawn.app.ui.qr.CameraPermission
import com.yawnandpawn.app.ui.qr.CodeScanner
import com.yawnandpawn.app.ui.qr.ConsecutiveFrames
import com.yawnandpawn.app.ui.qr.ScanEvent
import com.yawnandpawn.app.ui.qr.ScanResult

/**
 * The camera of host tests (Story 3.10; Epic 2 retro: no test needs a real camera). [frames] feeds analysed frames
 * through the real 3-frames rule, as `CodeAnalyzer` does, to the feed in composition. Without [permitted] the feed reports
 * the camera unavailable at once, as the CameraX scanner does.
 */
internal class FakeCodeScanner(
    var permitted: Boolean = true,
) : CodeScanner {
    private var listener: ((ScanEvent) -> Unit)? = null
    private val consecutive = ConsecutiveFrames()

    /** A feed is in composition (the camera would be bound). */
    val running: Boolean
        get() = listener != null

    /** The torch as the feed last asked for it. */
    var torchOn: Boolean = false
        private set

    override fun cameraPermitted(): Boolean = permitted

    @Composable
    override fun Feed(
        torchOn: Boolean,
        onEvent: (ScanEvent) -> Unit,
    ) {
        val events by rememberUpdatedState(onEvent)
        SideEffect { this.torchOn = torchOn }
        if (!permitted) {
            LaunchedEffect(Unit) { events(ScanEvent.CameraUnavailable) }
            return
        }
        DisposableEffect(Unit) {
            listener = { events(it) }
            onDispose { listener = null }
        }
    }

    /** [count] analysed frames that each hold [codes]; a stable code reaches the feed's listener. */
    fun frames(
        count: Int,
        vararg codes: ScanResult,
    ) {
        repeat(count) { consecutive.frame(codes.toList())?.let { emit(ScanEvent.Detected(it)) } }
    }

    /** CameraX could not start. */
    fun fail() = emit(ScanEvent.CameraUnavailable)

    private fun emit(event: ScanEvent) = checkNotNull(listener) { "no camera feed is running" }.invoke(event)
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
