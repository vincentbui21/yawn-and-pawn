package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PendingChangeJsonTest {
    private val values =
        listOf(
            SettingValue.BaseFeeTier(2),
            SettingValue.MaxSnoozes(4),
            SettingValue.GraceSeconds(30),
            SettingValue.Checks(
                CheckPlan(
                    CheckMode.All,
                    listOf(
                        CheckEntry(CheckType.MemorySequence(), Difficulty.Hard, 2),
                        CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, RegisteredCode.of(CodeFormat.QrCode, "door")),
                    ),
                ),
            ),
        )

    @Test
    fun `every value round-trips, and each one names its field`() {
        values.forEach { value -> assertEquals(value, PendingChangeJson.decode(PendingChangeJson.encode(value))) }
        assertEquals(LockedField.entries.toList(), values.map { it.field })
        assertEquals(listOf(true, true, false, false), values.map { it.field.global })
    }

    @Test
    fun `the stored format is pinned`() {
        assertEquals("""{"type":"BaseFee","tier":2}""", PendingChangeJson.encode(SettingValue.BaseFeeTier(2)))
        assertEquals("""{"type":"MaxSnoozes","count":4}""", PendingChangeJson.encode(SettingValue.MaxSnoozes(4)))
        assertEquals("""{"type":"GraceSeconds","seconds":30}""", PendingChangeJson.encode(SettingValue.GraceSeconds(30)))
        assertEquals(SettingValue.GraceSeconds(25), PendingChangeJson.decode("""{"type":"GraceSeconds","seconds":25,"later":1}"""))
    }

    @Test
    fun `a value that does not decode is none`() {
        assertNull(PendingChangeJson.decode("not json"))
        assertNull(PendingChangeJson.decode("""{"type":"Volume","percent":10}"""))
    }

    @Test
    fun `the global list round-trips, and a damaged one reads as none`() {
        val changes =
            listOf(
                PendingChange(null, SettingValue.BaseFeeTier(1), Occurrence("a", Instant.parse("2027-03-09T07:30:00Z"))),
                PendingChange(null, SettingValue.MaxSnoozes(5), Occurrence("b", Instant.parse("2027-03-09T06:00:00Z"))),
            )

        assertEquals(changes, PendingChangeJson.decodeGlobal(PendingChangeJson.encodeGlobal(changes)))
        assertTrue(PendingChangeJson.decodeGlobal("[{").isEmpty())
    }
}
