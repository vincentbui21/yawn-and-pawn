package com.yawnandpawn.app.android.qr

import android.content.Context
import android.os.Looper
import androidx.camera.core.ImageProxy
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.ui.qr.ScanEvent
import com.yawnandpawn.app.ui.qr.ScanResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.10: the analyser wrapper and the ML Kit decoder. Every frame is closed exactly once, a code counts after 3
 * frames in a row, a decoder that keeps failing is reported, and no scanner source can write a frame or a code anywhere.
 */
@RunWith(RobolectricTestRunner::class)
class CodeAnalyzerTest {
    private val code = ScanResult(CodeFormat.QrCode, "kitchen")

    /** A camera frame that records every call the analyser makes on it. */
    private class Frame {
        val calls = mutableListOf<String>()
        val proxy: ImageProxy =
            Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
                calls += method.name
                null
            } as ImageProxy
    }

    /** A barcode scanner whose every `process` gives [result]'s task; `close` is recorded. */
    private class FakeBarcodeScanner(
        private val result: () -> com.google.android.gms.tasks.Task<List<Barcode>>,
    ) {
        var processed = 0
        var closed = 0
        val scanner: BarcodeScanner =
            Proxy.newProxyInstance(BarcodeScanner::class.java.classLoader, arrayOf(BarcodeScanner::class.java)) { _, method, _ ->
                when (method.name) {
                    "process" -> result().also { processed++ }
                    "close" -> closed++
                    else -> null
                }
            } as BarcodeScanner
    }

    /** An in-memory ML Kit input (the fake scanners ignore it); ML Kit is started first, as `MlKitFrameDecoder.create` does. */
    private val input by lazy {
        MlKitContext.initializeIfNeeded(ApplicationProvider.getApplicationContext<Context>())
        InputImage.fromByteArray(ByteArray(16 * 16 * 3 / 2), 16, 16, 0, InputImage.IMAGE_FORMAT_NV21)
    }

    @Test
    fun `each frame is closed once read, and a code counts after 3 frames in a row`() {
        val stable = mutableListOf<ScanEvent.Detected>()
        val analyzer = CodeAnalyzer(decoder = { _, done -> done(listOf(code)) }, onStable = { stable += it })
        val frames = List(7) { Frame() }

        frames.forEach { analyzer.analyze(it.proxy) }

        assertEquals(listOf(ScanEvent.Detected(code), ScanEvent.Detected(code)), stable, "frames 3 and 6")
        frames.forEach { assertEquals(listOf("close"), it.calls, "the analyser only closes the frame") }
    }

    @Test
    fun `decoded frames send at most one heartbeat per 500 ms, and a failed decode sends none (Story 3_11)`() {
        var now = 1_000L
        var failing = false
        var beats = 0
        val analyzer =
            CodeAnalyzer(
                decoder = { _, done -> done(if (failing) null else emptyList()) },
                onStable = {},
                onFrame = { beats++ },
                elapsedMillis = { now },
            )

        repeat(20) {
            analyzer.analyze(Frame().proxy)
            now += 20
        }
        assertEquals(1, beats, "20 frames in 400 ms: one heartbeat")

        now = 1_500
        analyzer.analyze(Frame().proxy)
        assertEquals(2, beats, "500 ms after the last one: the next")

        failing = true
        now = 3_000
        repeat(5) { analyzer.analyze(Frame().proxy) }
        assertEquals(2, beats, "a failed decode is never a heartbeat")
    }

    @Test
    fun `a frame is closed only when its decoding is done, and a decoder that throws still closes it`() {
        var pending: ((List<ScanResult>?) -> Unit)? = null
        val waiting = Frame()
        CodeAnalyzer(decoder = { _, done -> pending = done }, onStable = {}).analyze(waiting.proxy)
        assertEquals(emptyList(), waiting.calls)
        pending!!(emptyList())
        assertEquals(listOf("close"), waiting.calls)

        val failing = Frame()
        CodeAnalyzer(decoder = { _, _ -> error("model not loaded") }, onStable = {}).analyze(failing.proxy)
        assertEquals(listOf("close"), failing.calls)

        val linkError = Frame()
        CodeAnalyzer(decoder = { _, _ -> throw UnsatisfiedLinkError("libbarhopper") }, onStable = {}).analyze(linkError.proxy)
        assertEquals(listOf("close"), linkError.calls, "an Error is caught too, so the analysis never stalls")
    }

    @Test
    fun `a frame is finished exactly once, whatever the decoder does after it called done`() {
        val stable = mutableListOf<ScanEvent.Detected>()
        val analyzer =
            CodeAnalyzer(
                decoder = { _, done ->
                    done(listOf(code))
                    done(emptyList())
                    error("x")
                },
                onStable = { stable += it },
            )
        val frames = List(3) { Frame() }

        frames.forEach { analyzer.analyze(it.proxy) }

        frames.forEach { assertEquals(listOf("close"), it.calls, "one close per frame") }
        assertEquals(listOf(ScanEvent.Detected(code)), stable, "the second call and the throw did not reset the streak")
    }

    @Test
    fun `a stable code reported to a listener that throws is still one close per frame`() {
        var reports = 0
        val analyzer =
            CodeAnalyzer(decoder = { _, done -> done(listOf(code)) }, onStable = {
                reports++
                error("listener")
            })
        val frames = List(3) { Frame() }

        frames.forEach { analyzer.analyze(it.proxy) }

        frames.forEach { assertEquals(listOf("close"), it.calls) }
        assertEquals(1, reports)
    }

    @Test
    fun `10 failed frames in a row report the decoder failing once, and a good frame starts the count again`() {
        var failing = 0
        var fail = true
        val analyzer =
            CodeAnalyzer(
                decoder = { _, done -> if (fail) done(null) else done(emptyList()) },
                onStable = {},
                onFailing = { failing++ },
            )

        repeat(CodeAnalyzer.MAX_FAILURES - 1) { analyzer.analyze(Frame().proxy) }
        assertEquals(0, failing)
        fail = false
        analyzer.analyze(Frame().proxy)
        fail = true
        repeat(CodeAnalyzer.MAX_FAILURES - 1) { analyzer.analyze(Frame().proxy) }
        assertEquals(0, failing, "a frame read without a code starts the count again")
        analyzer.analyze(Frame().proxy)
        assertEquals(1, failing)
        repeat(5) { analyzer.analyze(Frame().proxy) }
        assertEquals(1, failing, "reported once")

        var thrown = 0
        val throwing = CodeAnalyzer(decoder = { _, _ -> error("no model") }, onStable = {}, onFailing = { thrown++ })
        repeat(CodeAnalyzer.MAX_FAILURES) { throwing.analyze(Frame().proxy) }
        assertEquals(1, thrown, "a decoder that throws fails too")
    }

    @Test
    fun `for the ML Kit decoder no image is no code, and a failed task is a failed frame`() {
        val noImage = mutableListOf<List<ScanResult>?>()
        val idle = FakeBarcodeScanner { Tasks.forResult(emptyList()) }
        MlKitFrameDecoder(idle.scanner, inputOf = { null }).decode(Frame().proxy) { noImage += it }
        assertEquals(listOf<List<ScanResult>?>(emptyList()), noImage, "done once, with no code")
        assertEquals(0, idle.processed, "nothing reaches ML Kit without an image")

        val failed = mutableListOf<List<ScanResult>?>()
        val broken = FakeBarcodeScanner { Tasks.forException(IllegalStateException("model")) }
        MlKitFrameDecoder(broken.scanner, inputOf = { input }).decode(Frame().proxy) { failed += it }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf<List<ScanResult>?>(null), failed, "done once, as a failed frame")

        val read = mutableListOf<List<ScanResult>?>()
        val empty = FakeBarcodeScanner { Tasks.forResult(emptyList()) }
        MlKitFrameDecoder(empty.scanner, inputOf = { input }).decode(Frame().proxy) { read += it }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf<List<ScanResult>?>(emptyList()), read)

        MlKitFrameDecoder(empty.scanner, inputOf = { input }).close()
        assertEquals(1, empty.closed, "close releases the model")
    }

    @Test
    fun `a failing ML Kit task counts toward the failure streak through the analyser`() {
        var failing = 0
        val broken = FakeBarcodeScanner { Tasks.forException(IllegalStateException("model")) }
        val analyzer = CodeAnalyzer(MlKitFrameDecoder(broken.scanner, inputOf = { input }), onStable = {}, onFailing = { failing++ })
        val frames = List(CodeAnalyzer.MAX_FAILURES) { Frame() }

        frames.forEach { analyzer.analyze(it.proxy) }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, failing)
        frames.forEach { assertEquals(listOf("close"), it.calls) }
    }

    @Test
    fun `ML Kit formats map to the stored formats`() {
        assertEquals(CodeFormat.QrCode, MlKitFrameDecoder.formatOf(Barcode.FORMAT_QR_CODE))
        assertEquals(CodeFormat.Ean13, MlKitFrameDecoder.formatOf(Barcode.FORMAT_EAN_13))
        assertEquals(CodeFormat.UpcA, MlKitFrameDecoder.formatOf(Barcode.FORMAT_UPC_A))
        assertEquals(CodeFormat.Itf, MlKitFrameDecoder.formatOf(Barcode.FORMAT_ITF))
        assertEquals(CodeFormat.Unknown, MlKitFrameDecoder.formatOf(Barcode.FORMAT_UNKNOWN))
        val mapped =
            listOf(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_ITF,
            ).map(MlKitFrameDecoder::formatOf)
        assertEquals(CodeFormat.entries - CodeFormat.Unknown, mapped, "every named format, each once")
    }

    @Test
    fun `a binary code is read from its bytes`() {
        val bytes = byteArrayOf(0, -1, 10)
        val binary = ScanResult.ofBytes(CodeFormat.QrCode, bytes)

        assertTrue(binary.binary)
        assertEquals(
            com.yawnandpawn.app.core.checks.qr.RegisteredCode
                .ofBytes(CodeFormat.QrCode, bytes),
            binary.code,
        )
        assertEquals("ScanResult(QrCode)", binary.toString())
    }

    @Test
    fun `no scanner source can write a frame or a code to storage or the log`() {
        // Host tests run in the :androidApp project directory. The scanner's own sources, the common scanner, QR
        // registration and the wake QR check: everything that holds a frame or a raw value.
        val scanner = File("src/main/kotlin/com/yawnandpawn/app/android/qr").listFiles { file -> file.extension == "kt" }.orEmpty()
        val common =
            File("../composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/qr")
                .listFiles { file ->
                    file.extension == "kt"
                }.orEmpty()
        val wake = listOf("WakeCheck.kt", "WakeQr.kt", "WakeCamera.kt").map { File("src/main/kotlin/com/yawnandpawn/app/android/wake/$it") }
        val sources = scanner.toList() + common + wake
        assertTrue(scanner.size >= 4, "the scanner sources: ${scanner.map { it.name }}")
        assertTrue(common.any { it.name == "CodeScanner.kt" } && sources.all(File::isFile), "the common sources: $sources")
        val banned =
            listOf(
                "FileOutputStream",
                "openFileOutput",
                "outputStream",
                "File(",
                "ImageCapture",
                "MediaStore",
                ".compress(",
                "writeBytes",
                "writeText",
                "toBitmap",
                "Bitmap",
                "SharedPreferences",
                "DataStore",
                "Log.",
                "println",
            )
        val found = sources.flatMap { file -> banned.filter { it in file.readText() }.map { "${file.name}: $it" } }
        assertEquals(emptyList(), found)
        // A log line never holds a raw value (every log call here is one line).
        val logLines = sources.flatMap { file -> file.readLines().filter { "logger.log(" in it }.map { "${file.name}: ${it.trim()}" } }
        assertTrue(logLines.size >= 3, "the scanner logs its failures: $logLines")
        assertEquals(emptyList(), logLines.filter { "rawValue" in it || "rawBytes" in it || "result" in it })
    }
}
