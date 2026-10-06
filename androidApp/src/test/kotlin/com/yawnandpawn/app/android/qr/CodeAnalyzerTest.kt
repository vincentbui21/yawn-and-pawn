package com.yawnandpawn.app.android.qr

import android.content.Context
import androidx.camera.core.ImageProxy
import androidx.test.core.app.ApplicationProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.ui.qr.ScanResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.10: the analyser wrapper. Every frame is closed once it is read, a code counts after 3 frames in a row, and
 * analysing writes nothing to storage: the app's directories are unchanged, and no scanner source uses a storage API.
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

    private fun storage(): Map<String, List<String>> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dirs =
            listOfNotNull(context.filesDir, context.cacheDir, context.externalCacheDir, context.getExternalFilesDir(null), context.dataDir)
        return dirs.associate { dir ->
            dir.path to
                dir
                    .walkTopDown()
                    .filter(File::isFile)
                    .map { "${it.path}:${it.length()}" }
                    .sorted()
                    .toList()
        }
    }

    @Test
    fun `each frame is closed once read, and a code counts after 3 frames in a row`() {
        val stable = mutableListOf<ScanResult>()
        val analyzer = CodeAnalyzer(decoder = { _, done -> done(listOf(code)) }, onStable = { stable += it })
        val before = storage()
        val frames = List(7) { Frame() }

        frames.forEach { analyzer.analyze(it.proxy) }

        assertEquals(listOf(code, code), stable, "frames 3 and 6")
        frames.forEach { assertEquals(listOf("close"), it.calls, "the analyser only closes the frame") }
        assertEquals(before, storage(), "nothing is written while analysing")
    }

    @Test
    fun `a frame is closed only when its decoding is done, and a decoder that throws still closes it`() {
        var pending: ((List<ScanResult>) -> Unit)? = null
        val waiting = Frame()
        CodeAnalyzer(decoder = { _, done -> pending = done }, onStable = {}).analyze(waiting.proxy)
        assertEquals(emptyList(), waiting.calls)
        pending!!(emptyList())
        assertEquals(listOf("close"), waiting.calls)

        val failing = Frame()
        CodeAnalyzer(decoder = { _, _ -> error("model not loaded") }, onStable = {}).analyze(failing.proxy)
        assertEquals(listOf("close"), failing.calls)
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
    fun `no scanner source writes a frame or a code to storage`() {
        // Host tests run in the :androidApp project directory.
        val sources = File("src/main/kotlin/com/yawnandpawn/app/android/qr").listFiles { file -> file.extension == "kt" }.orEmpty()
        assertTrue(sources.size >= 4, "the scanner sources: ${sources.map { it.name }}")
        val banned =
            listOf(
                "FileOutputStream",
                "openFileOutput",
                "ImageCapture",
                "MediaStore",
                ".compress(",
                "writeBytes",
                "writeText",
                "toBitmap",
                "SharedPreferences",
                "DataStore",
                "Log.",
            )
        val found = sources.flatMap { file -> banned.filter { it in file.readText() }.map { "${file.name}: $it" } }
        assertEquals(emptyList(), found)
    }
}
