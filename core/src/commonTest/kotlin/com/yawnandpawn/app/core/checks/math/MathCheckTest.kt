package com.yawnandpawn.app.core.checks.math

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckResult
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.checks.SeedDeriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/** The Math check (FR-PWK-5): generation over 10,000 seeds per difficulty, the two forms and exact validation. */
class MathCheckTest {
    private val math = CheckType.Math

    private fun problems(
        seed: Long,
        difficulty: Difficulty,
        count: Int = 1,
    ): List<MathProblem> = (math.generate(seed, difficulty, count) as Puzzle.Math).problems

    /** 10,000 seeds as the session derives them, so the test sees the seeds production uses. */
    private val seeds: List<Long> = List(10_000) { SeedDeriver.seed("session-$it", 1 + it % 3, it % 4, 0) }

    @Test
    fun `Easy is a plus b or a minus b with a and b in 10 to 99 and a at least b for a subtraction`() {
        var plus = 0
        var minus = 0
        seeds.forEach { seed ->
            val problem = problems(seed, Difficulty.Easy).single()
            val (a, b) = problem.operands.also { assertEquals(2, it.size, "$problem") }
            assertTrue(a in 10..99 && b in 10..99, "$problem")
            when (problem.operators.single()) {
                MathOperator.Plus -> {
                    plus++
                    assertEquals(a + b, problem.answer)
                }

                MathOperator.Minus -> {
                    minus++
                    assertTrue(a >= b, "$problem")
                    assertEquals(a - b, problem.answer)
                }

                MathOperator.Times -> {
                    fail("Easy has no multiplication: $problem")
                }
            }
        }
        assertTrue(plus > 4_500 && minus > 4_500, "both operations are used: $plus plus, $minus minus")
    }

    @Test
    fun `Medium is a times b plus c with a and c in 10 to 99 and b in 2 to 9`() {
        seeds.forEach { seed ->
            val problem = problems(seed, Difficulty.Medium).single()
            assertEquals(listOf(MathOperator.Times, MathOperator.Plus), problem.operators)
            val (a, b, c) = problem.operands
            assertTrue(a in 10..99 && b in 2..9 && c in 10..99 && problem.operands.size == 3, "$problem")
            assertEquals(a * b + c, problem.answer)
        }
    }

    @Test
    fun `Hard is a times b plus c times d with a and c in 10 to 99 and b and d in 2 to 9`() {
        seeds.forEach { seed ->
            val problem = problems(seed, Difficulty.Hard).single()
            assertEquals(listOf(MathOperator.Times, MathOperator.Plus, MathOperator.Times), problem.operators)
            assertEquals(4, problem.operands.size, "$problem")
            val (ab, cd) = problem.operands.chunked(2)
            assertTrue(ab[0] in 10..99 && ab[1] in 2..9 && cd[0] in 10..99 && cd[1] in 2..9, "$problem")
            assertEquals(ab[0] * ab[1] + cd[0] * cd[1], problem.answer)
        }
    }

    @Test
    fun `every operand value of each range is used`() {
        val easy = seeds.flatMap { problems(it, Difficulty.Easy).single().operands }.toSet()
        val multipliers = seeds.map { problems(it, Difficulty.Medium).single().operands[1] }.toSet()

        assertEquals((10..99).toSet(), easy)
        assertEquals((2..9).toSet(), multipliers)
    }

    @Test
    fun `every answer is between 0 and 9,999 and the spoken form says the display form in words`() {
        val words = mapOf("+" to "plus", "−" to "minus", "×" to "times")
        Difficulty.entries.forEach { difficulty ->
            seeds.forEach { seed ->
                val problem = problems(seed, difficulty).single()
                assertTrue(problem.answer in 0..9_999, "$problem")
                assertTrue(Regex("""\d{1,2}( [+−×] \d{1,2})+""").matches(problem.display), problem.display)
                val spokenFromDisplay = problem.display.split(" ").joinToString(" ") { words[it] ?: it }
                assertEquals(spokenFromDisplay, problem.spoken)
            }
        }
    }

    @Test
    fun `the display and spoken forms read like the examples`() {
        val sum = MathProblem(listOf(47, 38), listOf(MathOperator.Plus))
        val difference = MathProblem(listOf(47, 38), listOf(MathOperator.Minus))
        val medium = MathProblem(listOf(23, 4, 17), listOf(MathOperator.Times, MathOperator.Plus))
        val hard = MathProblem(listOf(12, 3, 14, 5), listOf(MathOperator.Times, MathOperator.Plus, MathOperator.Times))

        assertEquals(Triple("47 + 38", "47 plus 38", 85), Triple(sum.display, sum.spoken, sum.answer))
        assertEquals(Triple("47 − 38", "47 minus 38", 9), Triple(difference.display, difference.spoken, difference.answer))
        assertEquals(Triple("23 × 4 + 17", "23 times 4 plus 17", 109), Triple(medium.display, medium.spoken, medium.answer))
        assertEquals(Triple("12 × 3 + 14 × 5", "12 times 3 plus 14 times 5", 106), Triple(hard.display, hard.spoken, hard.answer))
    }

    @Test
    fun `the answer multiplies before it adds or subtracts`() {
        assertEquals(14, MathProblem(listOf(2, 3, 4), listOf(MathOperator.Plus, MathOperator.Times)).answer)
        assertEquals(4, MathProblem(listOf(10, 2, 3), listOf(MathOperator.Minus, MathOperator.Times)).answer)
        assertEquals(4, MathProblem(listOf(5, 3, 2), listOf(MathOperator.Minus, MathOperator.Plus)).answer)
        assertEquals(7, MathProblem(listOf(7), emptyList()).answer)
        assertFailsWith<IllegalArgumentException> { MathProblem(listOf(1, 2), emptyList()) }
        assertFailsWith<IllegalArgumentException> { MathProblem(emptyList(), emptyList()) }
    }

    @Test
    fun `a puzzle has count problems and a count outside 1 to 10 is brought into it`() {
        assertEquals(1..10, math.countRange)
        assertEquals(3, math.defaultCount)
        assertEquals(listOf(1, 3, 10, 1, 10), listOf(1, 3, 10, 0, 99).map { problems(SEED, Difficulty.Medium, it).size })
        assertEquals(
            problems(SEED, Difficulty.Medium, 3).take(1),
            problems(SEED, Difficulty.Medium, 1),
            "count adds problems after the first",
        )
    }

    @Test
    fun `a right answer is ItemCorrect until the last problem, which is Correct`() {
        val puzzle = math.generate(SEED, Difficulty.Medium, 3) as Puzzle.Math
        val results = puzzle.problems.mapIndexed { position, problem -> math.validate(puzzle, position, answer(problem.answer)) }

        assertEquals(listOf(CheckResult.ItemCorrect, CheckResult.ItemCorrect, CheckResult.Correct), results)
    }

    @Test
    fun `only the exact non-negative integer is right, leading zeros ignored`() {
        val puzzle = Puzzle.Math(listOf(MathProblem(listOf(47, 38), listOf(MathOperator.Plus))))
        val zero = Puzzle.Math(listOf(MathProblem(listOf(38, 38), listOf(MathOperator.Minus))))

        listOf("85", "085", "0000000000000000000000085").forEach { digits ->
            assertEquals(CheckResult.Correct, math.validate(puzzle, 0, answer(digits)), digits)
        }
        listOf("0", "00").forEach { assertEquals(CheckResult.Correct, math.validate(zero, 0, answer(it)), it) }
        val wrong = listOf("", "84", "86", "-85", "+85", " 85", "85 ", "8 5", "85.0", "8a", "８５", "99999999999999999999999", "0")
        wrong.forEach { digits ->
            assertEquals(CheckResult.Wrong, math.validate(puzzle, 0, answer(digits)), "'$digits'")
        }
    }

    @Test
    fun `another puzzle, answer or position is Wrong and never throws`() {
        val puzzle = math.generate(SEED, Difficulty.Easy, 2) as Puzzle.Math
        val right = answer(puzzle.problems[0].answer)

        assertEquals(CheckResult.ItemCorrect, math.validate(puzzle, 0, right), "the control case is right")
        assertEquals(CheckResult.Wrong, math.validate(Puzzle.Placeholder, 0, right))
        assertEquals(CheckResult.Wrong, math.validate(puzzle, 0, CheckAnswer.Placeholder))
        assertEquals(CheckResult.Wrong, math.validate(puzzle, 0, CheckAnswer.ImageMatched))
        assertEquals(CheckResult.Wrong, math.validate(puzzle, -1, right))
        assertEquals(CheckResult.Wrong, math.validate(puzzle, 2, right))
    }

    private fun answer(value: Int): CheckAnswer = CheckAnswer.Number(value.toString())

    private fun answer(digits: String): CheckAnswer = CheckAnswer.Number(digits)

    private companion object {
        const val SEED = 20_260_926L
    }
}
