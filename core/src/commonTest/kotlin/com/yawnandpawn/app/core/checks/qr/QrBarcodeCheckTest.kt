package com.yawnandpawn.app.core.checks.qr

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckResult
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.session.SessionJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.10: the QR/Barcode plugin, its registered code and the SHA-256 fingerprint. */
class QrBarcodeCheckTest {
    private val qr = CheckType.QrBarcode
    private val code = RegisteredCode.of(CodeFormat.Ean13, "4006381333931")!!
    private val entry = CheckEntry(qr, Difficulty.Medium, count = 1, code = code)

    private fun scan(
        value: String,
        format: CodeFormat = CodeFormat.Ean13,
    ): CheckAnswer = CheckAnswer.Code(RegisteredCode.of(format, value)!!)

    @Test
    fun `SHA-256 matches the FIPS 180-4 test vectors`() {
        val vectors =
            mapOf(
                "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" to
                    "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
                "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu" to
                    "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
                "a".repeat(1_000_000) to "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            )

        vectors.forEach { (input, expected) -> assertEquals(expected, Sha256.hex(input.encodeToByteArray()), "${input.length} bytes") }
    }

    @Test
    fun `a registered code keeps the format and the fingerprint of the trimmed value, never the value`() {
        val padded = RegisteredCode.of(CodeFormat.QrCode, "  abc\n")!!

        assertEquals(RegisteredCode(CodeFormat.QrCode, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), padded)
        assertNull(RegisteredCode.of(CodeFormat.QrCode, " \t\n"), "a blank code cannot be registered")
        assertNotEquals(RegisteredCode.of(CodeFormat.QrCode, "abc"), RegisteredCode.of(CodeFormat.Aztec, "abc"))
        // UTF-8: a non-ASCII value has its own fingerprint, the same on every scan.
        assertEquals(RegisteredCode.of(CodeFormat.QrCode, "café"), RegisteredCode.of(CodeFormat.QrCode, "café "))
        assertNotEquals(RegisteredCode.of(CodeFormat.QrCode, "cafe"), RegisteredCode.of(CodeFormat.QrCode, "café"))
    }

    @Test
    fun `formats keep their stored names`() {
        CodeFormat.entries.forEach { assertEquals(it, CodeFormat.fromStoredName(it.storedName)) }
        assertEquals(CodeFormat.Ean13, CodeFormat.fromStoredName("EAN_13"))
        assertNull(CodeFormat.fromStoredName("MAXICODE"))
        assertNull(CodeFormat.fromStoredName(null))
        assertEquals(
            CodeFormat.entries.size,
            CodeFormat.entries
                .map { it.storedName }
                .toSet()
                .size,
        )
    }

    @Test
    fun `only the same format and the same trimmed value pass`() {
        val puzzle = entry.puzzle(seed = 5)

        assertEquals(Puzzle.Code(code), puzzle)
        assertEquals(1, puzzle.size)
        assertEquals(CheckResult.Correct, qr.validate(puzzle, 0, scan("4006381333931")))
        assertEquals(CheckResult.Correct, qr.validate(puzzle, 0, scan(" 4006381333931 ")), "trimmed")
        assertEquals(CheckResult.Wrong, qr.validate(puzzle, 0, scan("5901234123457")), "another code")
        assertEquals(CheckResult.Wrong, qr.validate(puzzle, 0, scan("4006381333931", CodeFormat.Code128)), "another format")
        assertEquals(CheckResult.Wrong, qr.validate(puzzle, 0, scan("4006381333931", CodeFormat.UpcA)), "not a UPC-A shape")
        assertEquals(CheckResult.Wrong, qr.validate(puzzle, 1, scan("4006381333931")), "no second item")
        assertEquals(CheckResult.Wrong, qr.validate(puzzle, 0, CheckAnswer.Number("4006381333931")), "typed digits")
        assertEquals(CheckResult.Wrong, qr.validate(Puzzle.Placeholder, 0, scan("4006381333931")), "another puzzle")
    }

    @Test
    fun `EAN-13, UPC-A and UPC-E are one family, so the same printed code passes however it is read`() {
        val upcA = RegisteredCode.of(CodeFormat.UpcA, "012345678905")!!
        val asUpcA = entry.copy(code = upcA).puzzle(1)

        assertEquals(RegisteredCode.of(CodeFormat.Ean13, "0012345678905"), upcA, "UPC-A is stored as its EAN-13")
        assertEquals(CodeFormat.Ean13, upcA.format)
        assertEquals(CheckResult.Correct, qr.validate(asUpcA, 0, scan("0012345678905")), "registered UPC-A, scanned EAN-13")
        assertEquals(CheckResult.Correct, qr.validate(puzzle(code), 0, scan("4006381333931")))
        assertEquals(CheckResult.Wrong, qr.validate(asUpcA, 0, scan("012345678906", CodeFormat.UpcA)), "another UPC-A")

        // UPC-E 01234565 is UPC-A 012345000065 (the four expansion rules, by the sixth digit).
        val upcE = RegisteredCode.of(CodeFormat.UpcE, "01234565")!!
        assertEquals(RegisteredCode.of(CodeFormat.UpcA, "012345000065"), upcE)
        assertEquals(CheckResult.Correct, qr.validate(puzzle(upcE), 0, scan("0012345000065")), "registered UPC-E, scanned EAN-13")
        assertEquals("012345000065", RetailCodes.upcAOfUpcE("0123456"), "no check digit: computed")
        assertEquals("012345000065", RetailCodes.upcAOfUpcE("123456"), "neither: number system 0, check digit computed")
        assertEquals("112345000062", RetailCodes.upcAOfUpcE("1123456"), "number system 1")
        assertEquals("012000003455", RetailCodes.upcAOfUpcE("01234505"), "sixth digit 0 to 2")
        assertEquals("012300000455", RetailCodes.upcAOfUpcE("01234535"), "sixth digit 3")
        assertEquals("012340000055", RetailCodes.upcAOfUpcE("01234545"), "sixth digit 4")
        assertNull(RetailCodes.upcAOfUpcE("21234565"), "number system 0 or 1 only")
        assertNull(RetailCodes.upcAOfUpcE("0123456".dropLast(2)), "too short")
        assertNull(RetailCodes.upcAOfUpcE("0123456A"), "digits only")
        assertEquals(CodeFormat.UpcE to "ABC", RetailCodes.normalised(CodeFormat.UpcE, "ABC"), "a malformed value is kept")
        assertEquals(CodeFormat.UpcA to "12345", RetailCodes.normalised(CodeFormat.UpcA, "12345"))
        assertEquals(CodeFormat.Ean8 to "96385074", RetailCodes.normalised(CodeFormat.Ean8, "96385074"), "EAN-8 stays itself")
    }

    @Test
    fun `a binary code with no text is fingerprinted from its bytes`() {
        val bytes = byteArrayOf(0, -1, 10, 32)
        val binary = RegisteredCode.ofBytes(CodeFormat.QrCode, bytes)!!

        assertEquals(RegisteredCode(CodeFormat.QrCode, Sha256.hex(bytes)), binary)
        assertEquals(binary, RegisteredCode.ofBytes(CodeFormat.QrCode, bytes.copyOf()), "the same bytes, the same code")
        assertNotEquals(binary, RegisteredCode.ofBytes(CodeFormat.QrCode, byteArrayOf(0, -1, 10)), "bytes are not trimmed")
        assertNull(RegisteredCode.ofBytes(CodeFormat.QrCode, ByteArray(0)))
        assertEquals(CheckResult.Correct, qr.validate(puzzle(binary), 0, CheckAnswer.Code(binary)))
    }

    @Test
    fun `every format encodes as its stored name`() {
        CodeFormat.entries.forEach { format ->
            assertEquals("\"${format.storedName}\"", SessionJson.json.encodeToString(CodeFormat.serializer(), format))
            assertEquals(format, SessionJson.json.decodeFromString(CodeFormat.serializer(), "\"${format.storedName}\""))
        }
    }

    private fun puzzle(code: RegisteredCode): Puzzle = entry.copy(code = code).puzzle(1)

    @Test
    fun `an entry without a code is not ready and can never be passed`() {
        val bare = entry.copy(code = null)

        assertTrue(entry.isReady)
        assertFalse(bare.isReady)
        assertEquals(Puzzle.Code(null), bare.puzzle(1))
        assertEquals(Puzzle.Code(null), qr.generate(1, Difficulty.Hard, 3), "generate has no entry, so no code")
        assertEquals(CheckResult.Wrong, qr.validate(bare.puzzle(1), 0, scan("4006381333931")))
        assertTrue(CheckPlan.DEFAULT_ENTRY.isReady, "every other type is ready")
    }

    @Test
    fun `the code serializes with the entry, and an entry without one encodes as before`() {
        val json = SessionJson.json
        val plan = CheckPlan(CheckMode.All, listOf(entry))
        val encoded = json.encodeToString(CheckPlan.serializer(), plan)

        assertEquals(
            """{"mode":"All","entries":[{"type":{"type":"QrBarcode"},"difficulty":"Medium","count":1,""" +
                """"code":{"format":"EAN_13","fingerprint":"${code.fingerprint}"}}]}""",
            encoded,
        )
        assertEquals(plan, json.decodeFromString(CheckPlan.serializer(), encoded))
        assertEquals(
            """{"type":{"type":"Math"},"difficulty":"Medium","count":3}""",
            json.encodeToString(CheckEntry.serializer(), CheckEntry(CheckType.Math, Difficulty.Medium, count = 3)),
        )
        val answer: CheckAnswer = CheckAnswer.Code(code)
        assertEquals(answer, json.decodeFromString(CheckAnswer.serializer(), json.encodeToString(CheckAnswer.serializer(), answer)))
        val puzzle: Puzzle = Puzzle.Code(code)
        assertEquals(puzzle, json.decodeFromString(Puzzle.serializer(), json.encodeToString(Puzzle.serializer(), puzzle)))
    }
}
