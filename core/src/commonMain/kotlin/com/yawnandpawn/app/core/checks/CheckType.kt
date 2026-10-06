package com.yawnandpawn.app.core.checks

import com.yawnandpawn.app.core.checks.math.MathGenerator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How hard a check is (FR-PWK-3). A type without difficulty ([CheckType.hasDifficulty] false) ignores it. */
@Serializable
enum class Difficulty {
    Easy,
    Medium,
    Hard,
}

/**
 * A wake-up check type: the AD-9 plugin contract. Every type follows the same rules for difficulty, progress and
 * correctness. A puzzle is fully determined by its seed, and only [validate] decides whether an answer is right; the UI
 * never does. Each check story adds its own type here (only [Math] in Story 3.1; House Hunt in Epic 7).
 */
@Serializable
sealed interface CheckType {
    /**
     * The stable id of the type: session history stores it in `check_types` (AD-18), so it never changes, even when a
     * class is renamed. It never contains a comma.
     */
    val id: String

    /** The check needs the camera, so the fallback check (FR-PWK-11) may replace it. */
    val usesCamera: Boolean

    /** The check works before the first unlock: it needs no credential-protected storage or media (AD-15). */
    val directBootSafe: Boolean

    /** The user picks a [Difficulty] for this type. */
    val hasDifficulty: Boolean

    /** The counts the user may choose (problems, words, rounds). */
    val countRange: IntRange

    /** The count of a new entry of this type. */
    val defaultCount: Int

    /**
     * The puzzle for [seed]: the same seed, difficulty and count always give the same puzzle. A [count] outside
     * [countRange] is brought into it, so a stored plan can never make the wake flow throw.
     */
    fun generate(
        seed: Long,
        difficulty: Difficulty,
        count: Int,
    ): Puzzle

    /**
     * Checks [answer] for item [position] (0-based) of [puzzle]. Never throws: an answer or puzzle of another type, or a
     * position outside the puzzle, is [CheckResult.Wrong].
     */
    fun validate(
        puzzle: Puzzle,
        position: Int,
        answer: CheckAnswer,
    ): CheckResult

    /**
     * Mental arithmetic (FR-PWK-5): `count` problems, each answered with a non-negative integer. Easy is `a + b` or
     * `a − b`, Medium `a × b + c`, Hard `a × b + c × d` (owner-approved default 2026-09-26).
     */
    @Serializable
    @SerialName("Math")
    data object Math : CheckType {
        override val id: String = "Math"
        override val usesCamera: Boolean = false
        override val directBootSafe: Boolean = true
        override val hasDifficulty: Boolean = true
        override val countRange: IntRange = 1..10
        override val defaultCount: Int = 3

        override fun generate(
            seed: Long,
            difficulty: Difficulty,
            count: Int,
        ): Puzzle = Puzzle.Math(MathGenerator.problems(seed, difficulty, count.coerceIn(countRange)))

        override fun validate(
            puzzle: Puzzle,
            position: Int,
            answer: CheckAnswer,
        ): CheckResult {
            val problems = (puzzle as? Puzzle.Math)?.problems.orEmpty()
            val problem = problems.getOrNull(position)
            val digits = (answer as? CheckAnswer.Number)?.digits
            return when {
                problem == null || digits == null || !MathGenerator.matches(digits, problem.answer) -> CheckResult.Wrong
                position == problems.lastIndex -> CheckResult.Correct
                else -> CheckResult.ItemCorrect
            }
        }
    }

    /**
     * The Epic 1 stand-in, where "I'm up" alone passes the check. No production plan holds it since Story 3.2, but sessions
     * stored by Epics 1–2 hold it (the wake screen still answers it), so it stays in the sealed hierarchy; it is not a
     * check a user can choose. Tests that are not about the check use it too.
     */
    @Serializable
    @SerialName("Placeholder")
    data object Placeholder : CheckType {
        override val id: String = "Placeholder"
        override val usesCamera: Boolean = false
        override val directBootSafe: Boolean = true
        override val hasDifficulty: Boolean = false
        override val countRange: IntRange = 1..1
        override val defaultCount: Int = 1

        override fun generate(
            seed: Long,
            difficulty: Difficulty,
            count: Int,
        ): Puzzle = Puzzle.Placeholder

        override fun validate(
            puzzle: Puzzle,
            position: Int,
            answer: CheckAnswer,
        ): CheckResult =
            if (puzzle == Puzzle.Placeholder && position == 0 &&
                answer == CheckAnswer.Placeholder
            ) {
                CheckResult.Correct
            } else {
                CheckResult.Wrong
            }
    }

    companion object {
        /** Every type; a test keeps it in step with the sealed hierarchy. */
        val all: List<CheckType> = listOf(Math, Placeholder)
    }
}
