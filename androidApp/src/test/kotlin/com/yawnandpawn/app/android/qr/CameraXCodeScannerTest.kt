package com.yawnandpawn.app.android.qr

import android.Manifest
import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.launchActivity
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.ui.qr.CameraProblem
import com.yawnandpawn.app.ui.qr.ScanEvent
import com.yawnandpawn.app.ui.qr.ScanResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.lang.reflect.Proxy
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 3.10 review: the real CameraX scanner on the host, with the camera binding and the model replaced. Every way the
 * camera can fail is a logged `CameraUnavailable`, and nothing is thrown into the process (which also hosts
 * `WakeService`). Story 3.11: a sticky problem ends the scan with everything released; any other one is reported once
 * per change while the camera stays bound, and decoded frames send heartbeats.
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

    /** How many times the camera reported itself open ([ScanEvent.Opened], kept out of [events]). */
    private var opens = 0

    private fun record(event: ScanEvent) {
        if (event == ScanEvent.Opened) opens++ else events += event
    }

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
        launchActivity<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.setContent { scanner.Feed(torchOn = true, onEvent = ::record) } }
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

    private fun unavailable(problem: CameraProblem): ScanEvent = ScanEvent.CameraUnavailable(problem)

    private fun stateError(code: Int): CameraState = CameraState.create(CameraState.Type.CLOSED, CameraState.StateError.create(code))

    @Test
    fun `a model that cannot load is camera unavailable, with nothing started and no crash`() {
        granted()
        val cameras = FakeCameras()
        feed(CameraXCodeScanner(app, logger, cameras, decoder = { error("no model") }))

        assertEquals(listOf(unavailable(CameraProblem.BindFailed)), events)
        assertEquals(0, cameras.started, "the camera is never bound")
        assertEquals(listOf("start camera"), failures())
    }

    @Test
    fun `a native library that cannot load (an Error) is camera unavailable too`() {
        granted()
        feed(CameraXCodeScanner(app, logger, FakeCameras(), decoder = { throw UnsatisfiedLinkError("libbarhopper_v3") }))

        assertEquals(listOf(unavailable(CameraProblem.BindFailed)), events)
    }

    @Test
    fun `a camera that cannot be bound is camera unavailable, and the model is released`() {
        granted()
        val decoder = FakeDecoder()
        feed(CameraXCodeScanner(app, logger, FakeCameras(failure = IllegalArgumentException("no back camera")), decoder = { decoder }))

        assertEquals(listOf(unavailable(CameraProblem.BindFailed)), events)
        assertEquals(1, decoder.closed)
        assertEquals(listOf("start camera"), failures())
    }

    @Test
    fun `the camera disabled (policy or privacy toggle) is camera unavailable at once and ends the scan, released`() {
        granted()
        val cameras = FakeCameras()
        val decoder = FakeDecoder()
        feed(CameraXCodeScanner(app, logger, cameras, decoder = { decoder })) { root ->
            assertEquals(1, cameras.started)
            assertEquals(1, root.previewViews().size, "the viewfinder shows the camera")
            assertEquals(emptyList(), events, "an open camera is fine")
            assertTrue(cameras.torch.isNotEmpty() && cameras.torch.all { it }, "the torch is lit as asked: ${cameras.torch}")

            cameras.state.value = stateError(CameraState.ERROR_CAMERA_DISABLED)
            composeRule.waitForIdle()

            assertEquals(listOf(unavailable(CameraProblem.PrivacyBlocked)), events)
            assertEquals(1, cameras.released, "the camera is released")
            assertEquals(1, decoder.closed)
            assertFalse(cameras.state.hasObservers(), "no observer is left")
        }
    }

    @Test
    fun `another app taking the camera or a fatal error is reported at once, once per change, the camera kept bound`() {
        listOf(
            CameraState.ERROR_CAMERA_IN_USE to CameraProblem.Disconnected,
            CameraState.ERROR_MAX_CAMERAS_IN_USE to CameraProblem.Disconnected,
            CameraState.ERROR_CAMERA_FATAL_ERROR to CameraProblem.CameraError,
        ).forEach { (code, problem) ->
            events.clear()
            granted()
            val cameras = FakeCameras()
            val decoder = FakeDecoder()
            feed(CameraXCodeScanner(app, logger, cameras, decoder = { decoder })) {
                cameras.state.value = stateError(code)
                cameras.state.value = CameraState.create(CameraState.Type.OPENING, CameraState.StateError.create(code))
                composeRule.waitForIdle()
                assertEquals(listOf(unavailable(problem)), events, "error $code: once, at once")
                assertEquals(0, cameras.released, "error $code: bound, so CameraX can open it again")
                assertTrue(cameras.state.hasObservers())

                cameras.state.value = CameraState.create(CameraState.Type.OPEN)
                cameras.state.value = stateError(code)
                composeRule.waitForIdle()
                assertEquals(listOf(unavailable(problem), unavailable(problem)), events, "error $code: open again, then again")
            }
            assertEquals(1, cameras.released, "error $code: leaving the screen releases it")
            assertEquals(1, decoder.closed)
        }
    }

    @Test
    fun `the wake scan binds the camera with no picture, and its preview draws one`() {
        granted()
        val cameras = FakeCameras()
        val scanner = CameraXCodeScanner(app, logger, cameras, decoder = { FakeDecoder() })
        var shown by mutableStateOf(false)
        launchActivity<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    scanner.Scan(torchOn = false, onEvent = ::record)
                    if (shown) scanner.Preview()
                }
            }
            composeRule.waitForIdle()
            scenario.onActivity { assertEquals(emptyList(), it.window.decorView.previewViews(), "no picture") }
            assertEquals(1, cameras.started)
            assertEquals(listOf("Preview", "ImageAnalysis"), cameras.useCases.map { it::class.simpleName })

            shown = true
            composeRule.waitForIdle()
            scenario.onActivity {
                assertEquals(
                    1,
                    it.window.decorView
                        .previewViews()
                        .size,
                    "the picture",
                )
            }
            shown = false
            composeRule.waitForIdle()
            scenario.onActivity { assertEquals(emptyList(), it.window.decorView.previewViews()) }
            assertEquals(1, cameras.started, "the picture comes and goes; the camera stays")
            assertEquals(0, cameras.released)
        }
        assertEquals(emptyList(), events)
    }

    @Test
    fun `each time the camera opens it says so and lights the torch as the switch shows (screen off and on, review)`() {
        granted()
        val cameras = FakeCameras()
        feed(CameraXCodeScanner(app, logger, cameras, decoder = { FakeDecoder() })) {
            assertEquals(1, opens)
            assertEquals(listOf(true), cameras.torch, "lit once the camera is open")

            // The screen goes off: CameraX closes the camera at ON_STOP and opens it again at ON_START, torch off.
            cameras.state.value = CameraState.create(CameraState.Type.CLOSED)
            cameras.state.value = CameraState.create(CameraState.Type.OPEN)
            composeRule.waitForIdle()
            assertEquals(2, opens)
            assertEquals(listOf(true, true), cameras.torch, "lit again on the open")
            assertEquals(1, cameras.started)
        }
    }

    @Test
    fun `a scan composed, removed and composed again binds twice and lights the torch on each (review)`() {
        granted()
        val cameras = FakeCameras()
        val scanner = CameraXCodeScanner(app, logger, cameras, decoder = { FakeDecoder() })
        var scanning by mutableStateOf(true)
        launchActivity<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.setContent { if (scanning) scanner.Scan(torchOn = true, onEvent = ::record) } }
            composeRule.waitForIdle()
            scanning = false
            composeRule.waitForIdle()
            scanning = true
            composeRule.waitForIdle()
        }
        assertEquals(2, cameras.started)
        assertEquals(listOf(true, true), cameras.torch)
        assertEquals(emptyList(), events)
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
    fun `a decoder that keeps failing is camera unavailable with the camera kept bound, and decoding again sends heartbeats`() {
        granted()
        val code = ScanResult(CodeFormat.QrCode, "kitchen")
        var failing = false
        val cameras = FakeCameras()
        var analyzer: ImageAnalysis.Analyzer? = null
        val decoder = FakeDecoder { _, done -> done(if (failing) null else listOf(code)) }
        feed(CameraXCodeScanner(app, logger, cameras, decoder = { decoder }, analyzerSet = { analyzer = it })) {
            repeat(3) { analyzer!!.analyze(frame()) }
            composeRule.waitForIdle()
            assertEquals(listOf(ScanEvent.Frame, ScanEvent.Detected(code)), events, "a heartbeat, then the stable code")

            failing = true
            repeat(CodeAnalyzer.MAX_FAILURES) { analyzer!!.analyze(frame()) }
            composeRule.waitForIdle()

            assertEquals(listOf(ScanEvent.Frame, ScanEvent.Detected(code), unavailable(CameraProblem.DecoderFailing)), events)
            assertEquals(0, cameras.released, "Story 3.11: the analysis goes on, so a decoder that reads again is noticed")
            assertEquals(listOf("decode camera frames"), failures())

            failing = false
            ShadowSystemClock.advanceBy(Duration.ofMillis(CodeAnalyzer.HEARTBEAT_MILLIS))
            analyzer!!.analyze(frame())
            composeRule.waitForIdle()
            assertEquals(ScanEvent.Frame, events.last(), "decoding again: a heartbeat")
        }
        assertEquals(1, cameras.released)
    }

    @Test
    fun `without the permission the feed is camera unavailable exactly once, with no viewfinder and no camera`() {
        granted(false)
        val cameras = FakeCameras()
        var decoders = 0
        val scanner = CameraXCodeScanner(app, logger, cameras, decoder = { FakeDecoder().also { decoders++ } })

        assertFalse(scanner.cameraPermitted())
        feed(scanner) { root -> assertEquals(emptyList(), root.previewViews()) }

        assertEquals(listOf(unavailable(CameraProblem.NoPermission)), events)
        assertEquals(0, cameras.started)
        assertEquals(0, decoders, "ML Kit is never started")
    }

    @Test
    fun `which camera states are which problem`() {
        fun problem(
            code: Int,
            type: CameraState.Type = CameraState.Type.CLOSED,
        ) = cameraProblem(CameraState.create(type, CameraState.StateError.create(code)))

        assertNull(cameraProblem(CameraState.create(CameraState.Type.OPEN)))
        assertEquals(CameraProblem.Disconnected, problem(CameraState.ERROR_CAMERA_IN_USE))
        assertEquals(CameraProblem.Disconnected, problem(CameraState.ERROR_MAX_CAMERAS_IN_USE))
        assertEquals(CameraProblem.PrivacyBlocked, problem(CameraState.ERROR_CAMERA_DISABLED))
        assertEquals(CameraProblem.CameraError, problem(CameraState.ERROR_CAMERA_FATAL_ERROR))
        assertEquals(CameraProblem.CameraError, problem(CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED))
        assertEquals(CameraProblem.CameraError, problem(CameraState.ERROR_STREAM_CONFIG))
        assertNull(problem(CameraState.ERROR_OTHER_RECOVERABLE_ERROR, CameraState.Type.OPENING), "CameraX retries it")
        assertTrue(CameraProblem.PrivacyBlocked.sticky && !CameraProblem.Disconnected.sticky && !CameraProblem.CameraError.sticky)
    }
}
