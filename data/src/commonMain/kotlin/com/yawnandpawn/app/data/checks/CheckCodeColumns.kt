package com.yawnandpawn.app.data.checks

import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode

/**
 * A QR/Barcode entry's registered code as it is stored in `check_config` (Story 3.10): `code_format` holds
 * [CodeFormat.storedName] and `code_value` the SHA-256 fingerprint of the trimmed value (never the raw value, so the
 * backed-up `app.db` holds no code content). Both are null for every other entry.
 *
 * 3.5 hook: the `check_config` entity mapping reads and writes the two columns through [columnsOf] and [registeredCodeOf].
 */
object CheckCodeColumns {
    /** The `code_format` and `code_value` of [code]; both null without a code. */
    fun columnsOf(code: RegisteredCode?): Pair<String?, String?> = code?.format?.storedName to code?.fingerprint

    /**
     * The code stored as [format] and [value], or null when either is missing, the format is one this build does not
     * know, or the value is not a fingerprint. The entry is then not ready, so it never rings as QR/Barcode
     * (`PlanResolver` gives the default entry) and the editor asks to scan a code again.
     */
    fun registeredCodeOf(
        format: String?,
        value: String?,
    ): RegisteredCode? {
        val known = CodeFormat.fromStoredName(format)
        return if (known == null || value == null || !FINGERPRINT.matches(value)) null else RegisteredCode(known, value)
    }

    /** A lowercase hex SHA-256 digest. */
    private val FINGERPRINT = Regex("[0-9a-f]{64}")
}
