package com.yawnandpawn.app.android.qr

import android.content.Context
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.common.sdkinternal.MlKitContext
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
 *
 * A frame without an image has no code (`done(emptyList())`); a failed ML Kit task is a failed frame (`done(null)`), so
 * `CodeAnalyzer` notices a model that keeps failing (Story 3.10 review). [inputOf] turns the frame into ML Kit's input
 * (replaced in tests, which have no camera image).
 */
class MlKitFrameDecoder(
    private val scanner: BarcodeScanner,
    private val inputOf: (ImageProxy) -> InputImage? = ::mediaInput,
) : CloseableFrameDecoder {
    override fun decode(
        frame: ImageProxy,
        done: (List<ScanResult>?) -> Unit,
    ) {
        val input = inputOf(frame)
        if (input == null) {
            done(emptyList())
            return
        }
        scanner
            .process(input)
            .addOnCompleteListener { task ->
                done(if (task.isSuccessful) task.result.orEmpty().mapNotNull(::scanResultOf) else null)
            }
    }

    override fun close() = scanner.close()

    companion object {
        /**
         * The decoder of the bundled model. ML Kit is started here, when a scanner first starts, not by its init provider
         * at every app start (removed in the manifest, Story 3.10 review): an alarm's cold start never waits for it.
         */
        fun create(context: Context): MlKitFrameDecoder {
            MlKitContext.initializeIfNeeded(context.applicationContext)
            val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
            return MlKitFrameDecoder(BarcodeScanning.getClient(options))
        }

        @OptIn(ExperimentalGetImage::class)
        private fun mediaInput(frame: ImageProxy): InputImage? =
            frame.image?.let { InputImage.fromMediaImage(it, frame.imageInfo.rotationDegrees) }

        /** The code ML Kit read: its text, or its bytes for a code with no text (a binary QR); null with neither. */
        fun scanResultOf(barcode: Barcode): ScanResult? {
            val format = formatOf(barcode.format)
            return barcode.rawValue?.let { ScanResult(format, it) }
                ?: barcode.rawBytes?.takeIf { it.isNotEmpty() }?.let { ScanResult.ofBytes(format, it) }
        }

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
