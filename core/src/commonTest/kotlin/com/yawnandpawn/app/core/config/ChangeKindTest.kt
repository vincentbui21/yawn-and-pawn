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
import kotlin.test.assertFailsWith

class ChangeKindTest {
    private val weakening = ChangeKind.Weakening
    private val strengthening = ChangeKind.Strengthening
    private val same = ChangeKind.NoChange

    @Test
    fun `numeric fields - lower fee, more snoozes and a longer grace window weaken`() {
        val cases: List<Triple<SettingValue, SettingValue, ChangeKind>> =
            listOf(
                Triple(SettingValue.BaseFeeTier(3), SettingValue.BaseFeeTier(1), weakening),
                Triple(SettingValue.BaseFeeTier(3), SettingValue.BaseFeeTier(5), strengthening),
                Triple(SettingValue.BaseFeeTier(3), SettingValue.BaseFeeTier(3), same),
                Triple(SettingValue.MaxSnoozes(2), SettingValue.MaxSnoozes(5), weakening),
                Triple(SettingValue.MaxSnoozes(2), SettingValue.MaxSnoozes(1), strengthening),
                Triple(SettingValue.MaxSnoozes(2), SettingValue.MaxSnoozes(2), same),
                Triple(SettingValue.GraceSeconds(20), SettingValue.GraceSeconds(30), weakening),
                Triple(SettingValue.GraceSeconds(20), SettingValue.GraceSeconds(15), strengthening),
                Triple(SettingValue.GraceSeconds(20), SettingValue.GraceSeconds(20), same),
            )
        cases.forEach { (old, new, kind) -> assertEquals(kind, classify(old, new), "$old -> $new") }
    }

    @Test
    fun `values of two different fields are never compared`() {
        assertFailsWith<IllegalArgumentException> { classify(SettingValue.BaseFeeTier(1), SettingValue.MaxSnoozes(1)) }
    }

    private fun entry(
        type: CheckType,
        difficulty: Difficulty = Difficulty.Medium,
        count: Int = type.defaultCount,
        code: RegisteredCode? = null,
    ) = CheckEntry(type, difficulty, count, code)

    private fun plan(
        mode: CheckMode,
        vararg entries: CheckEntry,
    ) = CheckPlan(mode, entries.toList())

    @Test
    fun `check plans - stronger only in All mode with every old type at least as hard`() {
        val math = entry(CheckType.Math)
        val word = entry(CheckType.WordUnscramble)
        val memory = entry(CheckType.MemorySequence())
        val cases: List<Triple<CheckPlan, CheckPlan, ChangeKind>> =
            listOf(
                // Equal, or the same entries in another order (All or Random mode): a reorder is never held back.
                Triple(plan(CheckMode.All, math, word), plan(CheckMode.All, math, word), same),
                Triple(plan(CheckMode.Random, math, word), plan(CheckMode.Random, word, math), same),
                // The Epic 1 placeholder ("I'm up" alone passes it) adds nothing: dropping it is no change.
                Triple(plan(CheckMode.All, CheckPlan.PLACEHOLDER_ENTRY, math), plan(CheckMode.All, math), same),
                Triple(plan(CheckMode.All, math, word), plan(CheckMode.All, word, math), same),
                // All mode, every old type kept at least as hard (and something harder).
                Triple(plan(CheckMode.Random, math), plan(CheckMode.All, math), strengthening),
                Triple(plan(CheckMode.Random, math, word), plan(CheckMode.All, math, word, memory), strengthening),
                Triple(plan(CheckMode.All, math), plan(CheckMode.All, math.copy(difficulty = Difficulty.Hard)), strengthening),
                Triple(plan(CheckMode.All, math), plan(CheckMode.All, math.copy(count = 5)), strengthening),
                // Anything else weakens.
                Triple(plan(CheckMode.All, math, word), plan(CheckMode.All, math), weakening),
                Triple(plan(CheckMode.All, math, word), plan(CheckMode.Random, math, word), weakening),
                Triple(plan(CheckMode.Random, math), plan(CheckMode.Random, math, word), weakening),
                Triple(plan(CheckMode.All, math), plan(CheckMode.All, math.copy(difficulty = Difficulty.Easy)), weakening),
                Triple(plan(CheckMode.All, math), plan(CheckMode.All, math.copy(count = 1)), weakening),
                Triple(plan(CheckMode.All, math), plan(CheckMode.All, word), weakening),
            )
        cases.forEach { (old, new, kind) -> assertEquals(kind, classifyChecks(old, new), "$old -> $new") }
    }

    @Test
    fun `a new registered code is never a weaker plan, and a type without difficulty ignores it`() {
        val first = RegisteredCode.of(CodeFormat.QrCode, "kitchen")!!
        val second = RegisteredCode.of(CodeFormat.QrCode, "bathroom")!!
        val qr = entry(CheckType.QrBarcode, code = first)

        assertEquals(same, classifyChecks(plan(CheckMode.Random, qr), plan(CheckMode.Random, qr.copy(code = second))))
        assertEquals(
            same,
            classify(SettingValue.Checks(plan(CheckMode.All, qr)), SettingValue.Checks(plan(CheckMode.All, qr.copy(code = second)))),
        )
        // QR/Barcode has no difficulty: a different stored difficulty changes nothing.
        assertEquals(same, classifyChecks(plan(CheckMode.All, qr), plan(CheckMode.All, qr.copy(difficulty = Difficulty.Easy))))
    }
}
