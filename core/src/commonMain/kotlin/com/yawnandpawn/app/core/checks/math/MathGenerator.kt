package com.yawnandpawn.app.core.checks.math

import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.SeededRandom

/**
 * The Math check's problems (FR-PWK-5, owner-approved default 2026-09-26):
 * - Easy: `a + b` or `a − b`, a and b in 10–99, a ≥ b for a subtraction;
 * - Medium: `a × b + c`, a and c in 10–99, b in 2–9;
 * - Hard: `a × b + c × d`, a and c in 10–99, b and d in 2–9.
 *
 * Every answer is between 0 and 9,999 (at most 1,782).
 */
internal object MathGenerator {
    private val TWO_DIGITS = 10..99
    private val MULTIPLIER = 2..9

    /** [count] problems at [difficulty], fully determined by [seed]. */
    fun problems(
        seed: Long,
        difficulty: Difficulty,
        count: Int,
    ): List<MathProblem> {
        val random = SeededRandom(seed)
        return List(count) { problem(random, difficulty) }
    }

    private fun problem(
        random: SeededRandom,
        difficulty: Difficulty,
    ): MathProblem =
        when (difficulty) {
            Difficulty.Easy -> {
                val a = random.nextInt(TWO_DIGITS)
                val b = random.nextInt(TWO_DIGITS)
                if (random.nextBoolean()) {
                    MathProblem(listOf(a, b), listOf(MathOperator.Plus))
                } else {
                    MathProblem(listOf(maxOf(a, b), minOf(a, b)), listOf(MathOperator.Minus))
                }
            }

            Difficulty.Medium -> {
                MathProblem(
                    listOf(random.nextInt(TWO_DIGITS), random.nextInt(MULTIPLIER), random.nextInt(TWO_DIGITS)),
                    listOf(MathOperator.Times, MathOperator.Plus),
                )
            }

            Difficulty.Hard -> {
                MathProblem(
                    listOf(random.nextInt(TWO_DIGITS), random.nextInt(MULTIPLIER), random.nextInt(TWO_DIGITS), random.nextInt(MULTIPLIER)),
                    listOf(MathOperator.Times, MathOperator.Plus, MathOperator.Times),
                )
            }
        }

    /**
     * Whether the typed [digits] are exactly [answer]: a non-empty run of ASCII digits whose value is [answer], leading
     * zeros ignored. Compared as text, so a long input can never overflow.
     */
    fun matches(
        digits: String,
        answer: Int,
    ): Boolean = digits.isNotEmpty() && digits.all { it in '0'..'9' } && digits.trimStart('0').ifEmpty { "0" } == answer.toString()
}
