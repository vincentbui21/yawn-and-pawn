package com.yawnandpawn.app.core.checks.qr

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckResult
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Story 7.10: the printable QR's payload and the code registered for it. */
class PrintableCodeTest {
    private val uuidV4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    @Test
    fun `the payload is pps and a UUID v4, new each time`() {
        val ids = UuidV4IdGenerator()
        val first = PrintableCode.newPayload(ids)
        val second = PrintableCode.newPayload(ids)

        assertTrue(first.startsWith("pps:"), first)
        assertTrue(uuidV4.matches(first.removePrefix("pps:")), "a UUID v4 after the prefix")
        assertEquals(40, first.length, "nothing else: no personal or alarm data")
        assertNotEquals(first, second)
    }

    @Test
    fun `the payload takes its id from the generator`() {
        val ids = IdGenerator { "00000000-0000-4000-8000-000000000001" }

        assertEquals("pps:00000000-0000-4000-8000-000000000001", PrintableCode.newPayload(ids))
    }

    @Test
    fun `the registered code is a QR code with the fingerprint a scan of the same text gives`() {
        val payload = "pps:00000000-0000-4000-8000-000000000001"
        val registered = PrintableCode.registered(payload)

        assertEquals(CodeFormat.QrCode, registered.format)
        assertEquals(RegisteredCode.of(CodeFormat.QrCode, payload), registered)
        assertTrue(payload !in registered.fingerprint, "only the fingerprint is kept")
        val entry = CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, code = registered)
        assertEquals(
            CheckResult.Correct,
            CheckType.QrBarcode.validate(entry.puzzle(seed = 0), 0, CheckAnswer.Code(RegisteredCode.of(CodeFormat.QrCode, payload)!!)),
        )
    }

    @Test
    fun `a blank payload is refused`() {
        assertFailsWith<IllegalArgumentException> { PrintableCode.registered(" ") }
    }
}
