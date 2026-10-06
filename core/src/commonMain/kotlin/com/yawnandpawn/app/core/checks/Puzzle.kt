package com.yawnandpawn.app.core.checks

import com.yawnandpawn.app.core.checks.math.MathProblem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A generated puzzle. [CheckType.generate] makes it from a seed, so the session stores only the seed (AD-9). */
@Serializable
sealed interface Puzzle {
    /** How many items the user must answer (problems, words, rounds). */
    val size: Int

    /** The problems of a [CheckType.Math] puzzle, in order. */
    @Serializable
    @SerialName("Math")
    data class Math(
        val problems: List<MathProblem>,
    ) : Puzzle {
        override val size: Int
            get() = problems.size
    }

    /** The puzzle of the [CheckType.Placeholder] stand-in: one item, answered by "I'm up". */
    @Serializable
    @SerialName("Placeholder")
    data object Placeholder : Puzzle {
        override val size: Int = 1
    }
}
