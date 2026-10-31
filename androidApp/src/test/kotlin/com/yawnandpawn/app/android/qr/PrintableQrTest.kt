package com.yawnandpawn.app.android.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.PrintableCode
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.ui.qr.QrMatrix
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 7.10: the printable code decodes (ZXing) to the code registered for the alarm, its quiet zone is there, a code
 * that cannot be made is logged without its text, the page puts it 14 cm square in the middle of A4 and Letter, and with
 * no screen to print from nothing is printed.
 */
class PrintableQrTest {
    private val logger = FakeLogger()
    private val encoder = ZxingQrEncoder(logger)

    /** [matrix] as an image, [scale] pixels per module, read back by ZXing's QR reader. */
    private fun read(
        matrix: QrMatrix,
        scale: Int = 4,
    ): Result {
        val side = matrix.size * scale
        val pixels = IntArray(side * side) { i -> if (matrix.isDark(i / side / scale, i % side / scale)) BLACK else WHITE }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels))))
    }

    private fun decode(matrix: QrMatrix): String = read(matrix).text

    @Test
    fun `a printable code decodes to the code registered for the alarm`() {
        repeat(5) {
            val payload = PrintableCode.newPayload(UuidV4IdGenerator())
            val matrix = assertNotNull(encoder.encode(payload))

            val read = decode(matrix)

            assertEquals(payload, read)
            assertEquals(PrintableCode.registered(payload), RegisteredCode.of(CodeFormat.QrCode, read), "a scan matches")
        }
        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun `the code is version 3 with error correction M and a 4-module quiet zone`() {
        val matrix = assertNotNull(encoder.encode("pps:00000000-0000-4000-8000-000000000001"))

        // 40 characters fit version 3 (29 modules) at level M; the quiet zone adds 4 on each side.
        assertEquals(29 + 2 * ZxingQrEncoder.QUIET_ZONE, matrix.size)
        val edge = 0 until ZxingQrEncoder.QUIET_ZONE
        val far = matrix.size - ZxingQrEncoder.QUIET_ZONE until matrix.size
        for (i in 0 until matrix.size) {
            for (j in edge + far) {
                assertFalse(matrix.isDark(i, j), "quiet zone at $i, $j")
                assertFalse(matrix.isDark(j, i), "quiet zone at $j, $i")
            }
        }
        assertTrue(matrix.isDark(ZxingQrEncoder.QUIET_ZONE, ZxingQrEncoder.QUIET_ZONE), "the top-left finder starts there")
        assertEquals("M", read(matrix).resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL], "decoded error correction")
    }

    @Test
    fun `a code that cannot be made gives null and is logged without its text`() {
        val tooLong = "pps:" + "x".repeat(5_000)

        assertNull(encoder.encode(tooLong))

        val event = logger.events.single()
        assertEquals(LogEvent.OperationFailed(ZxingQrEncoder.ENCODE, "WriterException"), event)
        assertFalse(event.toString().contains("pps:"), "no payload in the log")
    }

    @Test
    fun `the page puts the code 14 cm square in the middle of A4 and Letter, the line under its quiet zone`() {
        val fourteenCm = 14f / 2.54f * 72f
        // The widest quiet zone: version 1, 4 of its 21 modules.
        val widestQuietZone = fourteenCm * ZxingQrEncoder.QUIET_ZONE / VERSION_1_MODULES
        val pages =
            mapOf(
                "A4" to (QrPageLayout.pointsOfMils(A4_WIDTH_MILS) to QrPageLayout.pointsOfMils(A4_HEIGHT_MILS)),
                "Letter" to (QrPageLayout.pointsOfMils(LETTER_WIDTH_MILS) to QrPageLayout.pointsOfMils(LETTER_HEIGHT_MILS)),
            )
        for ((name, size) in pages) {
            val (width, height) = size
            val layout = QrPageLayout.of(width, height)

            assertClose(fourteenCm, layout.side, "$name: 14 cm")
            assertClose(layout.left, width - layout.left - layout.side, "$name: centred across")
            assertClose(layout.top, height - layout.top - layout.side, "$name: centred down")
            assertClose(width / 2, layout.lineCenter, "$name: the line is centred")
            assertTrue(layout.lineBaseline > layout.top + layout.side, "$name: the line is under the code")
            val lineTop = layout.lineBaseline - QrPageLayout.LINE_TEXT_SIZE
            assertTrue(lineTop > layout.top + layout.side + widestQuietZone, "$name: the line stays out of the quiet zone")
            assertTrue(layout.left > 0f && layout.top > 0f, "$name: the code fits the page")
            assertTrue(layout.lineBaseline < height, "$name: on the page")
        }
        assertClose(595.3f, QrPageLayout.pointsOfMils(A4_WIDTH_MILS), "A4 is 595 x 842 points")
        assertClose(612f, QrPageLayout.pointsOfMils(LETTER_WIDTH_MILS), "Letter is 612 x 792 points")
    }

    @Test
    fun `on A5 portrait and A4 landscape the code shrinks so its quiet zone and the line stay on the page`() {
        val fourteenCm = 14f / 2.54f * 72f
        val pages =
            mapOf(
                "A5 portrait" to (QrPageLayout.pointsOfMils(A5_WIDTH_MILS) to QrPageLayout.pointsOfMils(A5_HEIGHT_MILS)),
                "A4 landscape" to (QrPageLayout.pointsOfMils(A4_HEIGHT_MILS) to QrPageLayout.pointsOfMils(A4_WIDTH_MILS)),
            )
        for ((name, size) in pages) {
            val (width, height) = size
            val layout = QrPageLayout.of(width, height)
            val quiet = layout.side * ZxingQrEncoder.QUIET_ZONE / VERSION_1_MODULES

            assertTrue(layout.side < fourteenCm, "$name: smaller than 14 cm, was ${layout.side}")
            assertTrue(layout.left - quiet >= -TOLERANCE, "$name: quiet zone on the page at the left")
            assertTrue(layout.left + layout.side + quiet <= width + TOLERANCE, "$name: quiet zone on the page at the right")
            assertTrue(layout.top - quiet >= -TOLERANCE, "$name: quiet zone on the page at the top")
            assertClose(layout.top, height - layout.top - layout.side, "$name: centred down")
            assertTrue(
                layout.lineBaseline - QrPageLayout.LINE_TEXT_SIZE > layout.top + layout.side + quiet,
                "$name: line under the quiet zone",
            )
            assertTrue(layout.lineBaseline + QrPageLayout.LINE_TEXT_SIZE <= height + TOLERANCE, "$name: the line is on the page")
        }
    }

    @Test
    fun `with no screen to print from, nothing is printed and the failure is logged without the code`() {
        val printer = AndroidQrPrinter(encoder, logger)
        val matrix = assertNotNull(printer.encode("pps:00000000-0000-4000-8000-000000000001"))

        var callbacks = 0
        assertFalse(printer.print(matrix, "Scan this to stop your alarm.", "Yawn & Pawn", { callbacks++ }, { callbacks++ }))
        assertEquals(0, callbacks, "no callback after false")

        val event = logger.events.single()
        assertEquals(LogEvent.OperationFailed(AndroidQrPrinter.PRINT, "no screen in front"), event)
        assertFalse(event.toString().contains("pps:"))
    }

    private fun assertClose(
        expected: Float,
        actual: Float,
        message: String,
    ) = assertTrue(abs(expected - actual) < TOLERANCE, "$message: expected $expected, was $actual")

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val TOLERANCE = 0.05f
        const val VERSION_1_MODULES = 21f

        // PrintAttributes.MediaSize.ISO_A4 and NA_LETTER, in thousandths of an inch.
        const val A4_WIDTH_MILS = 8268
        const val A4_HEIGHT_MILS = 11693
        const val LETTER_WIDTH_MILS = 8500
        const val LETTER_HEIGHT_MILS = 11000
        const val A5_WIDTH_MILS = 5827
        const val A5_HEIGHT_MILS = 8268
    }
}
