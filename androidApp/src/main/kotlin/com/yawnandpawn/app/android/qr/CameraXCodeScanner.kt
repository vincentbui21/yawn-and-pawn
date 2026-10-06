package com.yawnandpawn.app.android.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.ui.qr.CodeScanner
import com.yawnandpawn.app.ui.qr.ScanEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors

/**
 * The production [CodeScanner] (Story 3.10): CameraX shows the back camera in a `PreviewView` and feeds frames to the
 * bundled ML Kit model through [CodeAnalyzer] (3 frames in a row, on device only, never stored). The camera is bound to
 * the screen's lifecycle while the feed is in composition, so it is released when the screen stops or the feed leaves.
 * Without the permission, or when CameraX cannot start (no back camera, the camera in use), it reports
 * [ScanEvent.CameraUnavailable] at once. The 5 s no-frame watchdog and mid-scan errors are Story 3.11.
 */
class CameraXCodeScanner(
    private val context: Context,
    private val logger: Logger,
    private val decoder: () -> MlKitFrameDecoder = { MlKitFrameDecoder() },
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
        var camera by remember { mutableStateOf<Camera?>(null) }
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        LaunchedEffect(lifecycleOwner, previewView) {
            val executor = Executors.newSingleThreadExecutor()
            val frames = decoder()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val main = ContextCompat.getMainExecutor(context)
            analysis.setAnalyzer(executor, CodeAnalyzer(frames, onStable = { main.execute { events(ScanEvent.Detected(it)) } }))
            var provider: ProcessCameraProvider? = null
            try {
                provider = ProcessCameraProvider.awaitInstance(context)
                camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                awaitCancellation()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // CameraX reports a failed start in several types.
            ) {
                logger.log(LogEvent.OperationFailed("start camera", e::class.simpleName.orEmpty()))
                events(ScanEvent.CameraUnavailable)
            } finally {
                camera = null
                provider?.unbind(preview, analysis)
                analysis.clearAnalyzer()
                executor.shutdown()
                frames.close()
            }
        }
        LaunchedEffect(camera, torchOn) {
            camera?.takeIf { it.cameraInfo.hasFlashUnit() }?.cameraControl?.enableTorch(torchOn)
        }
    }
}
