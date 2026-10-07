package com.yawnandpawn.app.core.checks

import com.yawnandpawn.app.core.checks.math.MathProblem
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
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

    /**
     * The rounds of a [CheckType.MemorySequence] puzzle on a [gridSize] × [gridSize] grid (tiles numbered from 1, row by
     * row). Every tap is one item, so the puzzle has as many items as tiles in all its rounds.
     */
    @Serializable
    @SerialName("Memory")
    data class Memory(
        val gridSize: Int,
        val rounds: List<List<Int>>,
    ) : Puzzle {
        override val size: Int
            get() = rounds.sumOf { it.size }

        /** Every tile in the order it must be tapped, across the rounds. */
        val taps: List<Int>
            get() = rounds.flatten()
    }

    /**
     * The words of a [CheckType.WordUnscramble] puzzle (lowercase) and the scrambled letters shown for each, in order.
     * Each word is one item.
     */
    @Serializable
    @SerialName("Word")
    data class Word(
        val words: List<String>,
        val scrambles: List<String>,
    ) : Puzzle {
        override val size: Int
            get() = words.size
    }

    /**
     * The puzzle of a [CheckType.QrBarcode] entry: scan [code], one item. A null [code] (an entry saved without one,
     * which `SaveAlarm` rejects and `PlanResolver` never lets ring) can never be passed.
     */
    @Serializable
    @SerialName("Code")
    data class Code(
        val code: RegisteredCode?,
    ) : Puzzle {
        override val size: Int = 1
    }

    /** The puzzle of the [CheckType.Placeholder] stand-in: one item, answered by "I'm up". */
    @Serializable
    @SerialName("Placeholder")
    data object Placeholder : Puzzle {
        override val size: Int = 1
    }
}
