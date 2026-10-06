package com.yawnandpawn.app.android.qr

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.ui.qr.ScanResult

/**
 * The [FrameDecoder] of the bundled ML Kit barcode model (Story 3.10): the model ships in the APK
 * (`com.google.mlkit:barcode-scanning`), so it works offline with no download through Play services. Every format is
 * read; the frame is passed to ML Kit in memory and never stored. [close] releases the model.
 */
class MlKitFrameDecoder(
    private val scanner: BarcodeScanner =
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()),
) : FrameDecoder,
    AutoCloseable {
    @OptIn(ExperimentalGetImage::class)
    override fun decode(
        frame: ImageProxy,
        done: (List<ScanResult>) -> Unit,
    ) {
        val image = frame.image
        if (image == null) {
            done(emptyList())
            return
        }
        scanner
            .process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
            .addOnCompleteListener { task ->
                val codes = if (task.isSuccessful) task.result.orEmpty().mapNotNull(::scanResultOf) else emptyList()
                done(codes)
            }
    }

    override fun close() = scanner.close()

    companion object {
        /** The code ML Kit read, or null without a raw value. */
        fun scanResultOf(barcode: Barcode): ScanResult? = barcode.rawValue?.let { ScanResult(formatOf(barcode.format), it) }

        /** ML Kit's `Barcode.FORMAT_*` as the stored [CodeFormat]. */
        fun formatOf(format: Int): CodeFormat = FORMATS[format] ?: CodeFormat.Unknown

        private val FORMATS: Map<Int, CodeFormat> =
            mapOf(
                Barcode.FORMAT_QR_CODE to CodeFormat.QrCode,
                Barcode.FORMAT_AZTEC to CodeFormat.Aztec,
                Barcode.FORMAT_DATA_MATRIX to CodeFormat.DataMatrix,
                Barcode.FORMAT_PDF417 to CodeFormat.Pdf417,
                Barcode.FORMAT_EAN_13 to CodeFormat.Ean13,
                Barcode.FORMAT_EAN_8 to CodeFormat.Ean8,
                Barcode.FORMAT_UPC_A to CodeFormat.UpcA,
                Barcode.FORMAT_UPC_E to CodeFormat.UpcE,
                Barcode.FORMAT_CODE_128 to CodeFormat.Code128,
                Barcode.FORMAT_CODE_39 to CodeFormat.Code39,
                Barcode.FORMAT_CODE_93 to CodeFormat.Code93,
                Barcode.FORMAT_CODABAR to CodeFormat.Codabar,
                Barcode.FORMAT_ITF to CodeFormat.Itf,
            )
    }
}
