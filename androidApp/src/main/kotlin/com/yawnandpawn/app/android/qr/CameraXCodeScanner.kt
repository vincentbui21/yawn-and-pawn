package com.yawnandpawn.app.android.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.ui.qr.CodeScanner
import com.yawnandpawn.app.ui.qr.ScanEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A camera bound to a screen's lifecycle (Story 3.10 review: a seam, so host tests can run the real scanner). */
class StartedCamera(
    /** The camera's state; an error in it ends the scan ([cameraErrorEnds]). */
    val state: LiveData<CameraState>,
    /** Lights or puts out the torch; does nothing without a flash unit. */
    val torch: (Boolean) -> Unit,
    /** Unbinds the use cases. */
    val release: () -> Unit,
)

/** Starts the back camera with [UseCase]s bound to a lifecycle. */
fun interface CameraStarter {
    suspend fun start(
        owner: LifecycleOwner,
        useCases: List<UseCase>,
    ): StartedCamera
}

/** The production [CameraStarter]: CameraX's process camera provider, the back camera. */
class ProcessCameraStarter(
    private val context: Context,
) : CameraStarter {
    override suspend fun start(
        owner: LifecycleOwner,
        useCases: List<UseCase>,
    ): StartedCamera {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
        return StartedCamera(
            state = camera.cameraInfo.cameraState,
            torch = { on -> if (camera.cameraInfo.hasFlashUnit()) camera.cameraControl.enableTorch(on) },
            release = { provider.unbind(*useCases.toTypedArray()) },
        )
    }
}

/**
 * Whether [state] ends the scan (Story 3.10 review): binding succeeds even when the camera is in use, disabled by policy
 * (or refused by the camera privacy toggle) or broken; those errors arrive only in the camera state. A critical error,
 * or the camera in use by another app, ends it; CameraX retries the other recoverable errors itself.
 */
fun cameraErrorEnds(state: CameraState): Boolean {
    val error = state.error ?: return false
    return error.type == CameraState.ErrorType.CRITICAL ||
        error.code == CameraState.ERROR_CAMERA_IN_USE ||
        error.code == CameraState.ERROR_MAX_CAMERAS_IN_USE
}

/**
 * The production [CodeScanner] (Story 3.10): CameraX shows the back camera in a `PreviewView` and feeds frames to the
 * bundled ML Kit model through [CodeAnalyzer] (3 frames in a row, on device only, never stored). The camera is bound to
 * the screen's lifecycle while the feed is in composition, so it is released when the screen stops or the feed leaves.
 *
 * It never leaves the user stuck and never throws into the process that hosts `WakeService` (Story 3.10 review): every
 * way the camera can fail ends in [ScanEvent.CameraUnavailable], logged, so the check offers the fallback (3.9):
 * - no permission (at once, no camera started);
 * - any setup step throwing (ML Kit, the use cases, the provider, the binding), including an `Error`;
 * - an error in the camera state after binding ([cameraErrorEnds]: in use, disabled, fatal);
 * - [CodeAnalyzer.MAX_FAILURES] frames in a row that ML Kit fails to decode.
 *
 * The camera privacy toggle (Android 12+) has no public API to read (`SensorPrivacyManager` only says the toggle
 * exists). When the system refuses to open the camera it is the disabled error above; on a phone that mutes the camera
 * instead (black frames, no error) the scan keeps running: that case is the 3.11 watchdog's and the device checklist's
 * (3.14). The 5 s no-frame watchdog and the other mid-scan errors are Story 3.11.
 */
class CameraXCodeScanner(
    private val context: Context,
    private val logger: Logger,
    private val cameras: CameraStarter = ProcessCameraStarter(context),
    private val decoder: () -> CloseableFrameDecoder = { MlKitFrameDecoder.create(context) },
    /** Sees each scan's analyser; host tests feed it frames (they have no camera). */
    private val analyzerSet: (ImageAnalysis.Analyzer) -> Unit = {},
) : CodeScanner {
    override fun cameraPermitted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @Composable
    override fun Feed(
        torchOn: Boolean,
        onEvent: (ScanEvent) -> Unit,
    ) {
        val events by rememberUpdatedState(onEvent)
        if (!cameraPermitted()) {
            LaunchedEffect(Unit) { events(ScanEvent.CameraUnavailable) }
            return
        }
        val screen = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val previewView = remember(screen) { PreviewView(screen).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
        var camera by remember { mutableStateOf<StartedCamera?>(null) }
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        LaunchedEffect(lifecycleOwner, previewView) {
            scan(lifecycleOwner, previewView.surfaceProvider, onCamera = { camera = it }, events = { events(it) })
        }
        LaunchedEffect(camera, torchOn) {
            @Suppress("TooGenericExceptionCaught", "SwallowedException") // The torch is a nicety; it never ends a scan.
            try {
                camera?.torch(torchOn)
            } catch (e: Exception) {
                logger.log(LogEvent.OperationFailed("camera torch", e::class.simpleName.orEmpty()))
            }
        }
    }

    /**
     * Runs one scan until it is cancelled (the feed left) or the camera fails, which is logged and reported once as
     * [ScanEvent.CameraUnavailable]. Everything it started is released, each step on its own, whatever failed.
     */
    internal suspend fun scan(
        owner: LifecycleOwner,
        surface: Preview.SurfaceProvider,
        onCamera: (StartedCamera?) -> Unit,
        events: (ScanEvent) -> Unit,
    ) {
        val main = ContextCompat.getMainExecutor(context)
        // Why the scan ends: (step, reason) of the first failure, from the camera state or the analyser thread.
        val failure = CompletableDeferred<Pair<String, String>>()
        val stateObserver =
            Observer<CameraState> { state ->
                if (cameraErrorEnds(state)) failure.complete("camera error" to "code ${state.error?.code}")
            }
        var frames: CloseableFrameDecoder? = null
        var executor: ExecutorService? = null
        var analysis: ImageAnalysis? = null
        var started: StartedCamera? = null
        var reported = false
        try {
            frames = decoder()
            executor = Executors.newSingleThreadExecutor()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(surface) }
            analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val analyzer =
                CodeAnalyzer(
                    frames,
                    onStable = { report -> main.execute { if (!reported) events(report) } },
                    onFailing = { failure.complete("decode camera frames" to "${CodeAnalyzer.MAX_FAILURES} failed frames") },
                )
            analysis.setAnalyzer(executor, analyzer)
            analyzerSet(analyzer)
            started = cameras.start(owner, listOf(preview, analysis)).also(onCamera)
            started.state.observeForever(stateObserver)
            val (step, reason) = failure.await()
            logger.log(LogEvent.OperationFailed(step, reason))
        } catch (e: CancellationException) {
            // The feed left composition: not a failure. A cancelled CameraX future while the feed runs is one.
            if (!currentCoroutineContext().isActive) throw e
            logger.log(LogEvent.OperationFailed("start camera", e::class.simpleName.orEmpty()))
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable, // CameraX and ML Kit fail in many types, Errors too.
        ) {
            logger.log(LogEvent.OperationFailed("start camera", e::class.simpleName.orEmpty()))
        } finally {
            onCamera(null)
            quietly("observe camera") { started?.state?.removeObserver(stateObserver) }
            quietly("release camera") { started?.release?.invoke() }
            quietly("clear analyzer") { analysis?.clearAnalyzer() }
            quietly("stop analysis") { executor?.shutdown() }
            quietly("close decoder") { frames?.close() }
        }
        // Not cancelled: the camera failed (logged above). Reported once, after the camera was released.
        reported = true
        events(ScanEvent.CameraUnavailable)
    }

    private inline fun quietly(
        step: String,
        block: () -> Unit,
    ) {
        @Suppress("TooGenericExceptionCaught")
        try {
            block()
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(step, e::class.simpleName.orEmpty()))
        }
    }
}
