package com.yawnandpawn.app.core.checks.math

import kotlinx.serialization.Serializable

/** An arithmetic operator, with the symbol shown on screen and the word TalkBack reads. */
@Serializable
enum class MathOperator(
    val symbol: String,
    val word: String,
) {
    Plus("+", "plus"),

    /** The minus sign U+2212, not a hyphen. */
    Minus("−", "minus"),

    /** The multiplication sign U+00D7. */
    Times("×", "times"),
}

/**
 * One problem of a Math check: [operands] joined by [operators] (one fewer), read left to right with × before + and −.
 * For example `23 × 4 + 17` is `operands = [23, 4, 17]`, `operators = [Times, Plus]`.
 */
@Serializable
data class MathProblem(
    val operands: List<Int>,
    val operators: List<MathOperator>,
) {
    init {
        require(operands.isNotEmpty() && operators.size == operands.size - 1) {
            "a problem needs one operator between each two operands: $operands $operators"
        }
    }

    /** The exact answer, × before + and −. */
    val answer: Int
        get() {
            var sum = 0
            var sign = 1
            var term = operands.first()
            operators.forEachIndexed { index, operator ->
                val next = operands[index + 1]
                when (operator) {
                    MathOperator.Times -> {
                        term *= next
                    }

                    MathOperator.Plus, MathOperator.Minus -> {
                        sum += sign * term
                        sign = if (operator == MathOperator.Plus) 1 else -1
                        term = next
                    }
                }
            }
            return sum + sign * term
        }

    /** What the screen shows, for example "23 × 4 + 17". */
    val display: String
        get() = joined { it.symbol }

    /** What TalkBack reads, for example "23 times 4 plus 17". */
    val spoken: String
        get() = joined { it.word }

    private fun joined(name: (MathOperator) -> String): String =
        buildString {
            append(operands.first())
            operators.forEachIndexed { index, operator -> append(' ').append(name(operator)).append(' ').append(operands[index + 1]) }
        }
}
