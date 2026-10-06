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
 *
 * The retail family is compared as one (Story 3.10 review): the same printed code can be read as EAN-13 or as UPC-A, and
 * UPC-E is a short UPC-A, so a UPC-A value becomes its EAN-13 (a leading 0) and a UPC-E value is expanded to UPC-A, then
 * to EAN-13, before hashing; [format] is then [CodeFormat.Ean13]. A code with no text (a binary QR) is fingerprinted from
 * its bytes ([ofBytes]).
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
            if (trimmed.isEmpty()) return null
            val (family, value) = RetailCodes.normalised(format, trimmed)
            return RegisteredCode(family, Sha256.hex(value.encodeToByteArray()))
        }

        /** A code with no text, such as a binary QR, from its raw [bytes] (as read, not trimmed); null when empty. */
        fun ofBytes(
            format: CodeFormat,
            bytes: ByteArray,
        ): RegisteredCode? = if (bytes.isEmpty()) null else RegisteredCode(format, Sha256.hex(bytes))
    }
}

/** EAN-13, UPC-A and UPC-E as one family (Story 3.10 review): a well-formed UPC value is compared as its EAN-13. */
internal object RetailCodes {
    private const val UPC_A_LENGTH = 12
    private const val UPC_E_SHORT = 6
    private const val UPC_E_WITH_SYSTEM = 7
    private const val UPC_E_FULL = 8
    private const val LAST_LOW_SIXTH = 2
    private const val SIXTH_THREE = 3
    private const val SIXTH_FOUR = 4
    private const val BASE = 10
    private const val ODD_WEIGHT = 3

    /** [value] of [format] as compared: UPC-A and UPC-E as EAN-13; any other format, or a malformed value, unchanged. */
    fun normalised(
        format: CodeFormat,
        value: String,
    ): Pair<CodeFormat, String> {
        val upcA =
            when (format) {
                CodeFormat.UpcA -> value.takeIf { it.length == UPC_A_LENGTH && it.all(Char::isDigit) }
                CodeFormat.UpcE -> upcAOfUpcE(value)
                else -> null
            }
        return if (upcA == null) format to value else CodeFormat.Ean13 to "0$upcA"
    }

    /**
     * The UPC-A of the UPC-E [value]: 8 digits (number system 0 or 1, six digits, check digit), or 6 or 7 digits without
     * the number system (then 0) or without the check digit (then computed). Null for anything else.
     */
    fun upcAOfUpcE(value: String): String? {
        val (system, body, check) = upcEParts(value) ?: return null
        val last = body.last()
        val five = UPC_E_SHORT - 1
        val sixth = last.digitToInt()
        val expanded =
            when {
                sixth <= LAST_LOW_SIXTH -> body.take(2) + last + "0000" + body.substring(2, five)
                sixth == SIXTH_THREE -> body.take(SIXTH_THREE) + "00000" + body.substring(SIXTH_THREE, five)
                sixth == SIXTH_FOUR -> body.take(SIXTH_FOUR) + "00000" + body[SIXTH_FOUR]
                else -> body.take(five) + "0000" + last
            }
        val digits = "$system$expanded"
        return digits + (check ?: checkDigit(digits))
    }

    /** The number system, the six digits and the check digit (null when missing) of a UPC-E [value], or null. */
    private fun upcEParts(value: String): Triple<Char, String, Char?>? {
        val parts =
            when {
                !value.all(Char::isDigit) -> null
                value.length == UPC_E_SHORT -> Triple('0', value, null)
                value.length == UPC_E_WITH_SYSTEM -> Triple(value.first(), value.drop(1), null)
                value.length == UPC_E_FULL -> Triple(value.first(), value.substring(1, UPC_E_WITH_SYSTEM), value.last())
                else -> null
            }
        return parts?.takeIf { it.first == '0' || it.first == '1' }
    }

    /** The UPC-A check digit of its first 11 [digits]: the odd positions weigh 3, the even ones 1. */
    private fun checkDigit(digits: String): Char {
        val sum = digits.mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) ODD_WEIGHT else 1 }.sum()
        return ((BASE - sum % BASE) % BASE).digitToChar()
    }
}
