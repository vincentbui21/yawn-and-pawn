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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import com.yawnandpawn.app.ui.qr.CameraProblem
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
    /** The camera's state; an error in it is a camera problem ([cameraProblem]). */
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
 * The camera problem in [state] (Stories 3.10 review and 3.11), or null: binding succeeds even when the camera is in use,
 * disabled by policy (or refused by the camera privacy toggle) or broken; those errors arrive only in the camera state.
 * - disabled: [CameraProblem.PrivacyBlocked], which ends the scan;
 * - in use by another app, or too many cameras open: [CameraProblem.Disconnected]; CameraX opens it again once free;
 * - any other critical error (fatal, stream configuration, Do Not Disturb): [CameraProblem.CameraError].
 *
 * CameraX retries the other recoverable errors itself: they are no problem here, and if the frames stop the wake
 * check's watchdog sees it.
 */
fun cameraProblem(state: CameraState): CameraProblem? {
    val error = state.error ?: return null
    return when {
        error.code == CameraState.ERROR_CAMERA_DISABLED -> CameraProblem.PrivacyBlocked
        error.code == CameraState.ERROR_CAMERA_IN_USE || error.code == CameraState.ERROR_MAX_CAMERAS_IN_USE -> CameraProblem.Disconnected
        error.type == CameraState.ErrorType.CRITICAL -> CameraProblem.CameraError
        else -> null
    }
}

/**
 * The production [CodeScanner] (Story 3.10): CameraX runs the back camera and feeds frames to the bundled ML Kit model
 * through [CodeAnalyzer] (3 frames in a row, on device only, never stored); [Preview] shows it in a `PreviewView`. The
 * camera is bound to the screen's lifecycle while the scan is in composition, so it is released when the screen stops or
 * the scan leaves.
 *
 * Story 3.11 splits [Scan] from [Preview]: one `Preview` use case is bound with the analysis, and it is active only
 * while a [Preview] gives it a surface (`setSurfaceProvider(null)` makes it inactive). The wake check keeps the analysis
 * running under its camera-unavailable message, which has no viewfinder, so a camera that comes back is noticed. [Feed]
 * (registration, "Try it") is a scan with its own preview.
 *
 * It never leaves the user stuck and never throws into the process that hosts `WakeService` (Story 3.10 review): every
 * way the camera can fail is a logged [ScanEvent.CameraUnavailable], so the check offers the fallback (3.9):
 * - no permission: [CameraProblem.NoPermission] at once, no camera started;
 * - any setup step throwing (ML Kit, the use cases, the provider, the binding), an `Error` too: [CameraProblem.BindFailed];
 * - an error in the camera state after binding ([cameraProblem]);
 * - [CodeAnalyzer.MAX_FAILURES] frames in a row that ML Kit fails to decode: [CameraProblem.DecoderFailing].
 *
 * A [sticky][CameraProblem.sticky] problem ends the scan, after which everything is released and the problem is
 * reported once. Any other problem is reported once per change while the camera stays bound (CameraX reopens a camera
 * another app let go, and the decoder may read again), and [ScanEvent.Frame] heartbeats tell the wake check when frames
 * come back.
 *
 * The camera privacy toggle (Android 12+) has no public API to read (`SensorPrivacyManager` only says the toggle
 * exists). When the system refuses to open the camera it is the disabled error above. On a phone that mutes the camera
 * instead (black frames, no error), the frames still decode with no code; the wake check's "nothing read" rule offers
 * the link (Story 3.11), and the device checklist (3.14) covers it.
 */
class CameraXCodeScanner(
    private val context: Context,
    private val logger: Logger,
    private val cameras: CameraStarter = ProcessCameraStarter(context),
    private val decoder: () -> CloseableFrameDecoder = { MlKitFrameDecoder.create(context) },
    /** Sees each scan's analyser; host tests feed it frames (they have no camera). */
    private val analyzerSet: (ImageAnalysis.Analyzer) -> Unit = {},
) : CodeScanner {
    /** The preview use case of the running [Scan], for [Preview]. */
    private val scanPreview = mutableStateOf<Preview?>(null)

    override fun cameraPermitted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @Composable
    override fun Scan(
        torchOn: Boolean,
        onEvent: (ScanEvent) -> Unit,
    ) = RunScan(torchOn, onEvent, scanPreview)

    @Composable
    override fun Preview() = ShowPreview(scanPreview.value)

    @Composable
    override fun Feed(
        torchOn: Boolean,
        onEvent: (ScanEvent) -> Unit,
    ) {
        val own = remember { mutableStateOf<Preview?>(null) }
        RunScan(torchOn, onEvent, own)
        ShowPreview(own.value)
    }

    @Composable
    private fun RunScan(
        torchOn: Boolean,
        onEvent: (ScanEvent) -> Unit,
        preview: MutableState<Preview?>,
    ) {
        val events by rememberUpdatedState(onEvent)
        if (!cameraPermitted()) {
            LaunchedEffect(Unit) { events(ScanEvent.CameraUnavailable(CameraProblem.NoPermission)) }
            return
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        val torch by rememberUpdatedState(torchOn)
        var camera by remember { mutableStateOf<StartedCamera?>(null) }
        LaunchedEffect(lifecycleOwner) {
            scan(
                lifecycleOwner,
                onCamera = { started, use ->
                    camera = started
                    preview.value = use
                },
                events = { events(it) },
                torchOn = { torch },
            )
        }
        // The switch flipped: the open camera follows. Each time the camera opens (the bind, and again after the screen was
        // off, when CameraX closed it) the scan lights the torch as the switch shows it (Story 3.11 review).
        var switched by remember { mutableStateOf(torchOn) }
        LaunchedEffect(torchOn) {
            if (torchOn != switched) {
                switched = torchOn
                camera?.let { open -> quietly("camera torch") { open.torch(torchOn) } }
            }
        }
    }

    /** The `PreviewView` of [use], drawn while in composition; nothing without the permission. */
    @Composable
    private fun ShowPreview(use: Preview?) {
        if (!cameraPermitted()) return
        val screen = LocalContext.current
        val previewView = remember(screen) { PreviewView(screen).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        DisposableEffect(use, previewView) {
            quietly("show camera") { use?.setSurfaceProvider(previewView.surfaceProvider) }
            onDispose { quietly("hide camera") { use?.setSurfaceProvider(null) } }
        }
    }

    /**
     * Runs one scan until it is cancelled (the scan left) or a sticky problem ends it, which is logged and reported once
     * as [ScanEvent.CameraUnavailable]. Other problems and heartbeats are reported while it runs, on the main thread.
     * Everything it started is released, each step on its own, whatever failed.
     */
    internal suspend fun scan(
        owner: LifecycleOwner,
        onCamera: (StartedCamera?, Preview?) -> Unit,
        events: (ScanEvent) -> Unit,
        torchOn: () -> Boolean = { false },
    ) {
        var started: StartedCamera? = null
        val reports = ScanReports(events, onOpen = { quietly("camera torch") { started?.torch?.invoke(torchOn()) } })
        var frames: CloseableFrameDecoder? = null
        var executor: ExecutorService? = null
        var analysis: ImageAnalysis? = null
        var end = Ending("start camera", "", CameraProblem.BindFailed)
        try {
            frames = decoder()
            executor = Executors.newSingleThreadExecutor()
            val preview = Preview.Builder().build()
            analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val analyzer = reports.analyzer(frames)
            analysis.setAnalyzer(executor, analyzer)
            analyzerSet(analyzer)
            started = cameras.start(owner, listOf(preview, analysis)).also { onCamera(it, preview) }
            started.state.observeForever(reports.stateObserver)
            end = reports.ending.await()
            logger.log(LogEvent.OperationFailed(end.step, end.reason))
        } catch (e: CancellationException) {
            // The scan left composition: not a failure. A cancelled CameraX future while the scan runs is one.
            if (!currentCoroutineContext().isActive) throw e
            logger.log(LogEvent.OperationFailed("start camera", e::class.simpleName.orEmpty()))
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable, // CameraX and ML Kit fail in many types, Errors too.
        ) {
            logger.log(LogEvent.OperationFailed("start camera", e::class.simpleName.orEmpty()))
        } finally {
            reports.ended = true
            onCamera(null, null)
            quietly("observe camera") { started?.state?.removeObserver(reports.stateObserver) }
            quietly("release camera") { started?.release?.invoke() }
            quietly("clear analyzer") { analysis?.clearAnalyzer() }
            quietly("stop analysis") { executor?.shutdown() }
            quietly("close decoder") { frames?.close() }
        }
        // Not cancelled: a sticky problem ended the scan (logged above). Reported once, after the camera was released.
        events(ScanEvent.CameraUnavailable(end.problem))
    }

    /**
     * What one scan reports while it runs, on the main thread (Story 3.11): stable codes, heartbeats and each problem
     * that is not sticky, once per change and logged; a sticky problem from the camera state completes [ending]. Nothing
     * is reported once [ended] (the scan's own ending is reported by [scan]).
     */
    private inner class ScanReports(
        private val events: (ScanEvent) -> Unit,
        private val onOpen: () -> Unit,
    ) {
        private val main = ContextCompat.getMainExecutor(context)

        /** Why the scan ends: the first sticky failure in the camera state. */
        val ending = CompletableDeferred<Ending>()

        /** Main thread only: the scan ended. */
        var ended = false

        /** Main thread only: the last problem reported. */
        private var reported: CameraProblem? = null

        val stateObserver =
            Observer<CameraState> { state ->
                // Open (the bind, or again after a stop or another app): the watchdog's start, and the torch as the switch
                // shows it, since CameraX does not keep it lit across a close (Story 3.11 review).
                if (state.type == CameraState.Type.OPEN && !ended) {
                    events(ScanEvent.Opened)
                    onOpen()
                }
                val problem = cameraProblem(state)
                val reason = "code ${state.error?.code}"
                when {
                    // Open again without an error: a later camera error is reported again (a failing decoder stays reported).
                    problem == null -> {
                        if (state.type == CameraState.Type.OPEN && reported != CameraProblem.DecoderFailing) reported = null
                    }

                    problem.sticky -> {
                        ending.complete(Ending("camera error", reason, problem))
                    }

                    else -> {
                        report(problem, "camera error", reason)
                    }
                }
            }

        /** The analyser of [frames], reporting through these reports. */
        fun analyzer(frames: CloseableFrameDecoder): CodeAnalyzer =
            CodeAnalyzer(
                frames,
                onStable = { detected -> main.execute { if (!ended) events(detected) } },
                onFailing = {
                    val reason = "${CodeAnalyzer.MAX_FAILURES} failed frames"
                    main.execute { report(CameraProblem.DecoderFailing, "decode camera frames", reason) }
                },
                onFrame = {
                    main.execute {
                        if (!ended) {
                            // Decoding again: a later failing streak is reported again.
                            if (reported == CameraProblem.DecoderFailing) reported = null
                            events(ScanEvent.Frame)
                        }
                    }
                },
            )

        private fun report(
            problem: CameraProblem,
            step: String,
            reason: String,
        ) {
            if (ended || problem == reported) return
            reported = problem
            logger.log(LogEvent.OperationFailed(step, reason))
            events(ScanEvent.CameraUnavailable(problem))
        }
    }

    /** Why a scan ended: the failed [step], its [reason] (no code, no image) and the [problem] reported. */
    private data class Ending(
        val step: String,
        val reason: String,
        val problem: CameraProblem,
    )

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
