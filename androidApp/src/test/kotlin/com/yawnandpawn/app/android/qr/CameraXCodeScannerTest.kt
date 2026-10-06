package com.yawnandpawn.app.android.qr

import android.Manifest
import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.view.PreviewView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.ui.qr.ScanEvent
import com.yawnandpawn.app.ui.qr.ScanResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 3.10 review: the real CameraX scanner on the host, with the camera binding and the model replaced. Every way the
 * camera can fail ends in exactly one `CameraUnavailable`, logged, with everything released, and nothing is thrown into
 * the process (which also hosts `WakeService`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraXCodeScannerTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val logger = FakeLogger()
    private val events = mutableListOf<ScanEvent>()

    private class FakeDecoder(
        private val reading: FrameDecoder = FrameDecoder { _, done -> done(emptyList()) },
    ) : CloseableFrameDecoder {
        var closed = 0

        override fun decode(
            frame: ImageProxy,
            done: (List<ScanResult>?) -> Unit,
        ) = reading.decode(frame, done)

        override fun close() {
            closed++
        }
    }

    /** The camera binding: [failure] is thrown by `start`; else the camera state is [state]. */
    private class FakeCameras(
        private val failure: Throwable? = null,
    ) : CameraStarter {
        val state = MutableLiveData(CameraState.create(CameraState.Type.OPEN))
        var started = 0
        var released = 0
        val torch = mutableListOf<Boolean>()
        var useCases: List<UseCase> = emptyList()

        override suspend fun start(
            owner: LifecycleOwner,
            useCases: List<UseCase>,
        ): StartedCamera {
            failure?.let { throw it }
            started++
            this.useCases = useCases
            return StartedCamera(state, torch = { torch += it }, release = { released++ })
        }
    }

    private fun frame(): ImageProxy =
        Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, _, _ -> null } as ImageProxy

    private fun granted(granted: Boolean = true) {
        if (granted) {
            shadowOf(
                app,
            ).grantPermissions(Manifest.permission.CAMERA)
        } else {
            shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        }
    }

    /** Runs [scanner]'s feed on a screen, then [block] with the screen's root view. */
    private fun feed(
        scanner: CameraXCodeScanner,
        block: (View) -> Unit = {},
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { scanner.Feed(torchOn = true, onEvent = { event -> events += event }) } }
            composeRule.waitForIdle()
            scenario.onActivity { block(it.window.decorView) }
        }
    }

    private fun View.previewViews(): List<PreviewView> =
        when (this) {
            is PreviewView -> listOf(this)
            is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).previewViews() }
            else -> emptyList()
        }

    private fun failures(): List<String> = logger.events.filterIsInstance<LogEvent.OperationFailed>().map { it.operation }

    @Test
    fun `a model that cannot load is camera unavailable, with nothing started and no crash`() {
        granted()
        val cameras = FakeCameras()
        feed(CameraXCodeScanner(app, logger, cameras, decoder = { error("no model") }))

        assertEquals(listOf<ScanEvent>(ScanEvent.CameraUnavailable), events)
        assertEquals(0, cameras.started, "the camera is never bound")
        assertEquals(listOf("start camera"), failures())
    }

    @Test
    fun `a native library that cannot load (an Error) is camera unavailable too`() {
        granted()
        feed(CameraXCodeScanner(app, logger, FakeCameras(), decoder = { throw UnsatisfiedLinkError("libbarhopper_v3") }))

        assertEquals(listOf<ScanEvent>(ScanEvent.CameraUnavailable), events)
    }

    @Test
    fun `a camera that cannot be bound is camera unavailable, and the model is released`() {
        granted()
        val decoder = FakeDecoder()
        feed(CameraXCodeScanner(app, logger, FakeCameras(failure = IllegalArgumentException("no back camera")), decoder = { decoder }))

        assertEquals(listOf<ScanEvent>(ScanEvent.CameraUnavailable), events)
        assertEquals(1, decoder.closed)
        assertEquals(listOf("start camera"), failures())
    }

    @Test
    fun `an error in the camera state after binding (in use, disabled, fatal) is camera unavailable at once, released`() {
        listOf(CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_CAMERA_DISABLED, CameraState.ERROR_CAMERA_FATAL_ERROR).forEach { code ->
            events.clear()
            granted()
            val cameras = FakeCameras()
            val decoder = FakeDecoder()
            feed(CameraXCodeScanner(app, logger, cameras, decoder = { decoder })) { root ->
                assertEquals(1, cameras.started)
                assertEquals(1, root.previewViews().size, "the viewfinder shows the camera")
                assertEquals(emptyList(), events, "an open camera is fine")
                assertTrue(cameras.torch.isNotEmpty() && cameras.torch.all { it }, "the torch is lit as asked: ${cameras.torch}")

                cameras.state.value = CameraState.create(CameraState.Type.CLOSED, CameraState.StateError.create(code))
                composeRule.waitForIdle()

                assertEquals(listOf<ScanEvent>(ScanEvent.CameraUnavailable), events, "error $code")
                assertEquals(1, cameras.released, "error $code: the camera is released")
                assertEquals(1, decoder.closed)
                assertFalse(cameras.state.hasObservers(), "no observer is left")
            }
        }
    }

    @Test
    fun `a recoverable error is left to CameraX, and leaving the screen releases everything`() {
        granted()
        val cameras = FakeCameras()
        val decoder = FakeDecoder()
        feed(CameraXCodeScanner(app, logger, cameras, decoder = { decoder })) {
            cameras.state.value =
                CameraState.create(CameraState.Type.OPENING, CameraState.StateError.create(CameraState.ERROR_OTHER_RECOVERABLE_ERROR))
            composeRule.waitForIdle()
            assertEquals(emptyList(), events)
        }
        composeRule.waitForIdle()
        assertEquals(emptyList(), events, "leaving is not a failure")
        assertEquals(1, cameras.released)
        assertEquals(1, decoder.closed)
        assertEquals(emptyList(), failures())
    }

    @Test
    fun `frames that ML Kit keeps failing to decode are camera unavailable, and stable codes before that are reported`() {
        granted()
        val code = ScanResult(CodeFormat.QrCode, "kitchen")
        var failing = false
        val cameras = FakeCameras()
        var analyzer: ImageAnalysis.Analyzer? = null
        val decoder = FakeDecoder { _, done -> done(if (failing) null else listOf(code)) }
        feed(CameraXCodeScanner(app, logger, cameras, decoder = { decoder }, analyzerSet = { analyzer = it })) {
            repeat(3) { analyzer!!.analyze(frame()) }
            composeRule.waitForIdle()
            assertEquals(listOf<ScanEvent>(ScanEvent.Detected(code)), events)

            failing = true
            repeat(CodeAnalyzer.MAX_FAILURES) { analyzer!!.analyze(frame()) }
            composeRule.waitForIdle()

            assertEquals(listOf(ScanEvent.Detected(code), ScanEvent.CameraUnavailable), events)
            assertEquals(1, cameras.released)
            assertEquals(listOf("decode camera frames"), failures())
        }
    }

    @Test
    fun `without the permission the feed is camera unavailable exactly once, with no viewfinder and no camera`() {
        granted(false)
        val cameras = FakeCameras()
        var decoders = 0
        val scanner = CameraXCodeScanner(app, logger, cameras, decoder = { FakeDecoder().also { decoders++ } })

        assertFalse(scanner.cameraPermitted())
        feed(scanner) { root -> assertEquals(emptyList(), root.previewViews()) }

        assertEquals(listOf<ScanEvent>(ScanEvent.CameraUnavailable), events)
        assertEquals(0, cameras.started)
        assertEquals(0, decoders, "ML Kit is never started")
    }

    @Test
    fun `which camera states end the scan`() {
        fun ends(
            code: Int,
            type: CameraState.Type = CameraState.Type.CLOSED,
        ) = cameraErrorEnds(CameraState.create(type, CameraState.StateError.create(code)))

        assertFalse(cameraErrorEnds(CameraState.create(CameraState.Type.OPEN)))
        assertTrue(ends(CameraState.ERROR_CAMERA_IN_USE))
        assertTrue(ends(CameraState.ERROR_MAX_CAMERAS_IN_USE))
        assertTrue(ends(CameraState.ERROR_CAMERA_DISABLED))
        assertTrue(ends(CameraState.ERROR_CAMERA_FATAL_ERROR))
        assertTrue(ends(CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED))
        assertTrue(ends(CameraState.ERROR_STREAM_CONFIG))
        assertFalse(ends(CameraState.ERROR_OTHER_RECOVERABLE_ERROR, CameraState.Type.OPENING), "CameraX retries it")
    }
}
