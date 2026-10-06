package com.yawnandpawn.app.core.checks

import com.yawnandpawn.app.core.checks.math.MathGenerator
import com.yawnandpawn.app.core.checks.memory.MemoryGenerator
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.checks.word.WordBank
import com.yawnandpawn.app.core.checks.word.WordGenerator
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
     * Where a [CheckResult.WrongRestart] at item [position] starts again, at [difficulty]: item 0 (the whole puzzle) unless
     * the type restarts a part of it (Memory Sequence: the current round, FR-PWK-4). The reducer moves the pointer there
     * with a new seed.
     */
    fun restartFrom(
        position: Int,
        difficulty: Difficulty,
    ): Int = 0

    /**
     * The puzzle of [entry] for [seed]: [generate] from the entry's difficulty and count. A type with its own per-entry
     * setting (QR/Barcode's registered code, Story 3.10) overrides it. Call it as `entry.puzzle(seed)`.
     */
    fun puzzle(
        entry: CheckEntry,
        seed: Long,
    ): Puzzle = generate(seed, entry.difficulty, entry.count)

    /** Whether [entry] has everything its puzzle needs; only QR/Barcode needs more than its type (a registered code). */
    fun isReady(entry: CheckEntry): Boolean = true

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
     * Word Unscramble (FR-PWK-8): `count` distinct words of 4–5, 6–7 or 8–10 letters by difficulty from the installed
     * [WordBank] list, each shown scrambled (never as itself or as another listed word). The target word, or any listed
     * word with exactly the same letters, is right, in any case (owner decision 2026-10-06). The list is a bundled
     * asset, readable before the first unlock, so it is Direct Boot safe.
     */
    @Serializable
    @SerialName("WordUnscramble")
    data object WordUnscramble : CheckType {
        override val id: String = "WordUnscramble"
        override val usesCamera: Boolean = false
        override val directBootSafe: Boolean = true
        override val hasDifficulty: Boolean = true
        override val countRange: IntRange = 1..5
        override val defaultCount: Int = 2

        override fun generate(
            seed: Long,
            difficulty: Difficulty,
            count: Int,
        ): Puzzle {
            val words = WordGenerator.words(seed, difficulty, count.coerceIn(countRange), WordBank.current)
            return Puzzle.Word(words.map { it.first }, words.map { it.second })
        }

        override fun validate(
            puzzle: Puzzle,
            position: Int,
            answer: CheckAnswer,
        ): CheckResult {
            val words = (puzzle as? Puzzle.Word)?.words.orEmpty()
            val target = words.getOrNull(position)
            val text = (answer as? CheckAnswer.Word)?.text?.lowercase()
            val right = target != null && text != null && (text == target || text in WordBank.current.anagramsOf(target))
            return when {
                !right -> CheckResult.Wrong
                position == words.lastIndex -> CheckResult.Correct
                else -> CheckResult.ItemCorrect
            }
        }
    }

    /**
     * Memory Sequence (FR-PWK-4): `count` rounds of 4, 6 or 8 lit tiles by difficulty, repeated tap by tap. Hard uses a
     * 4×4 grid. The [numbered] variant (TalkBack on when the ring's plan is frozen) always uses 3×3 with the same lengths
     * and shows every tile's number. Every tap is one item; a wrong tap restarts the current round with a new sequence.
     * Both variants have the id `MemorySequence`; only a ring's frozen plan ever holds the numbered one.
     */
    @Serializable
    @SerialName("MemorySequence")
    data class MemorySequence(
        val numbered: Boolean = false,
    ) : CheckType {
        override val id: String get() = ID
        override val usesCamera: Boolean get() = false
        override val directBootSafe: Boolean get() = true
        override val hasDifficulty: Boolean get() = true
        override val countRange: IntRange get() = COUNT_RANGE
        override val defaultCount: Int get() = DEFAULT_COUNT

        override fun generate(
            seed: Long,
            difficulty: Difficulty,
            count: Int,
        ): Puzzle {
            val grid = MemoryGenerator.gridSize(difficulty, numbered)
            return Puzzle.Memory(grid, MemoryGenerator.rounds(seed, difficulty, grid, count.coerceIn(countRange)))
        }

        override fun validate(
            puzzle: Puzzle,
            position: Int,
            answer: CheckAnswer,
        ): CheckResult {
            val taps = (puzzle as? Puzzle.Memory)?.taps.orEmpty()
            val expected = taps.getOrNull(position)
            val tapped = (answer as? CheckAnswer.Tile)?.number
            return when {
                expected == null || tapped == null -> CheckResult.Wrong
                tapped != expected -> CheckResult.WrongRestart
                position == taps.lastIndex -> CheckResult.Correct
                else -> CheckResult.ItemCorrect
            }
        }

        override fun restartFrom(
            position: Int,
            difficulty: Difficulty,
        ): Int {
            val length = MemoryGenerator.roundLength(difficulty)
            return position.coerceAtLeast(0) / length * length
        }

        companion object {
            const val ID = "MemorySequence"
            private const val DEFAULT_COUNT = 2
            private val COUNT_RANGE = 1..5
        }
    }

    /**
     * Scan a registered barcode or QR code (FR-PWK-7, Story 3.10): the puzzle is the entry's [CheckEntry.code], one item,
     * no difficulty (owner-approved default 2026-09-26: count fixed at 1). Only a [CheckAnswer.Code] of the same format and
     * the same trimmed value (the same [RegisteredCode]) passes; any other code is [CheckResult.Wrong], a failed attempt.
     * It needs the camera, so the fallback check may replace it, and the code lives in normal storage, so before the
     * first unlock the Direct Boot check replaces it.
     */
    @Serializable
    @SerialName("QrBarcode")
    data object QrBarcode : CheckType {
        override val id: String = "QrBarcode"
        override val usesCamera: Boolean = true
        override val directBootSafe: Boolean = false
        override val hasDifficulty: Boolean = false
        override val countRange: IntRange = 1..1
        override val defaultCount: Int = 1

        /** Without an entry there is no code: a puzzle that can never be passed. Rings use [puzzle]. */
        override fun generate(
            seed: Long,
            difficulty: Difficulty,
            count: Int,
        ): Puzzle = Puzzle.Code(code = null)

        override fun puzzle(
            entry: CheckEntry,
            seed: Long,
        ): Puzzle = Puzzle.Code(entry.code)

        override fun isReady(entry: CheckEntry): Boolean = entry.code != null

        override fun validate(
            puzzle: Puzzle,
            position: Int,
            answer: CheckAnswer,
        ): CheckResult {
            val code = (puzzle as? Puzzle.Code)?.code
            val scanned = (answer as? CheckAnswer.Code)?.code
            return if (code != null && position == 0 && scanned == code) CheckResult.Correct else CheckResult.Wrong
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
        /**
         * Every type; a test keeps it in step with the sealed hierarchy. Lazy: [restartFrom] is a default method, so on
         * the JVM initializing a type (say [Math]) first initializes this interface, and an eager list would then hold
         * the half-initialized type as null.
         */
        val all: List<CheckType> by lazy { listOf(Math, WordUnscramble, MemorySequence(), QrBarcode, Placeholder) }

        /**
         * The checks the Fallback check picker offers, in its order (FR-PWK-11, Story 3.9): every check a user may pick
         * (`CheckConfig.PICKABLE_TYPES`) that needs no camera, Math first. Lazy for the same reason as [all].
         */
        val fallbackChoices: List<CheckType> by lazy { all.filter { it != Placeholder && !it.usesCamera } }
    }
}
