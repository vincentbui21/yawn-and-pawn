package com.yawnandpawn.app.android.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.ui.qr.QrMatrix

/**
 * The printable QR's code (Story 7.10): ZXing core makes a QR code with error correction M (it still scans with about
 * 15% of the sheet smudged or folded) and the 4-module quiet zone a QR code needs, one matrix cell per module. The
 * payload is ASCII (`pps:` and a UUID), so it is encoded as plain bytes with no character-set marker. A failure is
 * logged by its type only, never with the payload, and gives null.
 */
class ZxingQrEncoder(
    private val logger: Logger,
) {
    fun encode(payload: String): QrMatrix? =
        try {
            // Width and height 0: ZXing draws the smallest size, one pixel per module, quiet zone included.
            val bits = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, HINTS)
            QrMatrix.of(bits.width) { row, column -> bits.get(column, row) }
        } catch (e: WriterException) {
            failed(e)
        } catch (e: IllegalArgumentException) {
            failed(e)
        }

    private fun failed(cause: Exception): QrMatrix? {
        logger.log(LogEvent.OperationFailed(ENCODE, cause::class.simpleName.orEmpty()))
        return null
    }

    companion object {
        /** The quiet zone around the code, in modules (ISO/IEC 18004). */
        const val QUIET_ZONE = 4

        const val ENCODE = "encode printable QR"

        private val HINTS: Map<EncodeHintType, Any> =
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to QUIET_ZONE)
    }
}
