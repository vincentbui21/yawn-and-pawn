package com.yawnandpawn.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.yawnandpawn.app.android.qr.MlKitFrameDecoder
import com.yawnandpawn.app.android.qr.QrPageLayout
import com.yawnandpawn.app.android.qr.ZxingQrEncoder
import com.yawnandpawn.app.android.qr.drawQrPage
import com.yawnandpawn.app.android.qr.writeQrPdf
import com.yawnandpawn.app.core.checks.qr.PrintableCode
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.core.log.Logger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Story 7.10 on the managed device: the printable page is read by the app's own bundled ML Kit scanner as the code
 * registered for the alarm, both drawn as it prints (A4, the code 14 cm square in the middle) and, since review pass 1,
 * written as the print adapter writes it ([writeQrPdf], A4 with a printer's margins) and rendered from the PDF with
 * [PdfRenderer].
 *
 * The names are underscored sentences: with minSdk 26 the test APK rejects spaces in method names.
 */
@RunWith(AndroidJUnit4::class)
class PrintableQrDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val payload = PrintableCode.newPayload(UuidV4IdGenerator())
    private val matrix = requireNotNull(ZxingQrEncoder(Logger { }).encode(payload))

    @Test
    fun the_printed_page_scans_with_ML_Kit_as_the_registered_code() {
        val layout = QrPageLayout.of(QrPageLayout.pointsOfMils(A4_WIDTH_MILS), QrPageLayout.pointsOfMils(A4_HEIGHT_MILS))
        // The page at 2 pixels per point (144 dpi), white like paper.
        val bitmap =
            Bitmap.createBitmap((layout.pageWidth * SCALE).toInt(), (layout.pageHeight * SCALE).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(SCALE, SCALE)
        drawQrPage(canvas, layout, matrix, LINE)

        assertEquals(PrintableCode.registered(payload), codeOf(scan(bitmap)))
    }

    @Test
    fun the_printed_PDF_page_scans_with_ML_Kit_as_the_registered_code() {
        val attributes =
            PrintAttributes
                .Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf", "pdf", DPI, DPI))
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                // A printer's margins (half an inch): the page is still drawn in page coordinates.
                .setMinMargins(PrintAttributes.Margins(MARGIN_MILS, MARGIN_MILS, MARGIN_MILS, MARGIN_MILS))
                .build()
        // The test's own temporary file, deleted at the end; the app writes only to the print service's file.
        val pdf = File.createTempFile("printable-qr", ".pdf", context.cacheDir)
        val bitmap =
            try {
                pdf.outputStream().use { writeQrPdf(context, attributes, matrix, LINE, it) }
                render(pdf)
            } finally {
                pdf.delete()
            }

        val barcode = scan(bitmap)
        assertEquals(PrintableCode.registered(payload), codeOf(barcode))
        // The code is 14 cm square in the middle of the page (review): ML Kit's box, in pixels at SCALE, within 10%.
        val layout = QrPageLayout.of(bitmap.width / SCALE, bitmap.height / SCALE)
        val side = layout.side * SCALE
        val box = requireNotNull(barcode.boundingBox)
        assertEquals("14 cm wide", side, box.width().toFloat(), side * TOLERANCE)
        assertEquals("14 cm high", side, box.height().toFloat(), side * TOLERANCE)
        assertEquals("centred across", (layout.left * SCALE) + side / 2, box.exactCenterX(), side * TOLERANCE)
        assertEquals("centred down", (layout.top * SCALE) + side / 2, box.exactCenterY(), side * TOLERANCE)
    }

    /** Page 0 of [pdf], which must be one A4 page, at [SCALE] pixels per point (144 dpi), on white like paper. */
    private fun render(pdf: File): Bitmap =
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(1, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    assertEquals(QrPageLayout.pointsOfMils(A4_WIDTH_MILS), page.width.toFloat(), 1f)
                    assertEquals(QrPageLayout.pointsOfMils(A4_HEIGHT_MILS), page.height.toFloat(), 1f)
                    val bitmap =
                        Bitmap.createBitmap((page.width * SCALE).toInt(), (page.height * SCALE).toInt(), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }

    /** The one QR code ML Kit (bundled, as the app scans) reads in [bitmap]. */
    private fun scan(bitmap: Bitmap): Barcode {
        MlKitContext.initializeIfNeeded(context.applicationContext)
        val scanner =
            BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val barcodes =
            try {
                Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } finally {
                scanner.close()
            }
        assertEquals(1, barcodes.size)
        return barcodes.single()
    }

    /** [barcode] as the check registers it. */
    private fun codeOf(barcode: Barcode): RegisteredCode? = MlKitFrameDecoder.scanResultOf(barcode)?.code

    private companion object {
        const val LINE = "Scan this to stop your alarm."
        const val SCALE = 2f
        const val DPI = 300
        const val MARGIN_MILS = 500
        const val TIMEOUT_SECONDS = 30L
        const val TOLERANCE = 0.1f

        // PrintAttributes.MediaSize.ISO_A4, in thousandths of an inch.
        const val A4_WIDTH_MILS = 8268
        const val A4_HEIGHT_MILS = 11693
    }
}
