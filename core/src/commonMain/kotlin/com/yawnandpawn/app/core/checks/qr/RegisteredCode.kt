package com.yawnandpawn.app.core.checks.qr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The symbology of a scanned code (FR-PWK-7). [storedName] (also the serial name) is stable: `app.db` stores it
 * (`check_config.code_format`) and so does a stored session. A scan only matches a code of the same format.
 */
@Serializable
enum class CodeFormat(
    val storedName: String,
) {
    @SerialName("QR_CODE")
    QrCode("QR_CODE"),

    @SerialName("AZTEC")
    Aztec("AZTEC"),

    @SerialName("DATA_MATRIX")
    DataMatrix("DATA_MATRIX"),

    @SerialName("PDF417")
    Pdf417("PDF417"),

    @SerialName("EAN_13")
    Ean13("EAN_13"),

    @SerialName("EAN_8")
    Ean8("EAN_8"),

    @SerialName("UPC_A")
    UpcA("UPC_A"),

    @SerialName("UPC_E")
    UpcE("UPC_E"),

    @SerialName("CODE_128")
    Code128("CODE_128"),

    @SerialName("CODE_39")
    Code39("CODE_39"),

    @SerialName("CODE_93")
    Code93("CODE_93"),

    @SerialName("CODABAR")
    Codabar("CODABAR"),

    @SerialName("ITF")
    Itf("ITF"),

    /** A format the scanner could not name; it still matches only itself. */
    @SerialName("UNKNOWN")
    Unknown("UNKNOWN"),
    ;

    companion object {
        /** The format stored as [name], or null for a name this build does not know. */
        fun fromStoredName(name: String?): CodeFormat? = entries.firstOrNull { it.storedName == name }
    }
}

/**
 * The code a QR/Barcode check waits for (FR-PWK-7): its [format] and [fingerprint], the lowercase hex SHA-256 of the
 * trimmed raw value in UTF-8. The raw value itself is never kept (default taken 2026-10-06, owner can change): a code can
 * hold private text, such as a Wi-Fi password, and only "is it the same code" matters. Two scans of the same code give the
 * same fingerprint, so comparing fingerprints is comparing trimmed values.
 */
@Serializable
data class RegisteredCode(
    val format: CodeFormat,
    val fingerprint: String,
) {
    companion object {
        /** The code [rawValue] of [format] as the check stores it, or null when the value is blank after trimming. */
        fun of(
            format: CodeFormat,
            rawValue: String,
        ): RegisteredCode? {
            val trimmed = rawValue.trim()
            return if (trimmed.isEmpty()) null else RegisteredCode(format, Sha256.hex(trimmed.encodeToByteArray()))
        }
    }
}
