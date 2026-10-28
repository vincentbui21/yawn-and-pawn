package com.yawnandpawn.app.core.checks.qr

import com.yawnandpawn.app.core.id.IdGenerator

/**
 * The app-made QR code to print (Story 7.10, FR-PWK-13): its text is `pps:` and a fresh UUID v4, so it holds no personal
 * or alarm data. A new payload is made each time the printable QR opens; it is never stored or logged. Only its
 * [registered] fingerprint is kept, exactly as for a scanned code, so a scan of the printed sheet matches it.
 */
object PrintableCode {
    /** What every printable code starts with. */
    const val PREFIX: String = "pps:"

    /** A new payload: [PREFIX] and a UUID v4 from [ids]. */
    fun newPayload(ids: IdGenerator): String = PREFIX + ids.newId()

    /** The code a QR/Barcode check stores for [payload]: the same as a scan of the printed QR code gives. */
    fun registered(payload: String): RegisteredCode =
        requireNotNull(RegisteredCode.of(CodeFormat.QrCode, payload)) { "a printable code is never blank" }
}
