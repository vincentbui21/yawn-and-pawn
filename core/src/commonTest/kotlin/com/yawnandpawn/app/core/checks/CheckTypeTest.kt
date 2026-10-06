package com.yawnandpawn.app.core.checks

import com.yawnandpawn.app.core.checks.math.MathOperator
import com.yawnandpawn.app.core.checks.math.MathProblem
import com.yawnandpawn.app.core.session.SessionJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The AD-9 plugin contract, for every [CheckType]. */
class CheckTypeTest {
    @Test
    fun `the list of every type matches the sealed hierarchy`() {
        // Exhaustive when: a new type fails to compile here until it is added, and then to the expected list.
        val listed =
            CheckType.all.map { type ->
                when (type) {
                    CheckType.Math -> "Math"
                    CheckType.Placeholder -> "Placeholder"
                }
            }

        assertEquals(listOf("Math", "Placeholder"), listed)
        assertEquals(listed, CheckType.all.map { it.id })
    }

    @Test
    fun `each type declares the contract values`() {
        val declared =
            CheckType.all.associate {
                it.id to
                    listOf(it.usesCamera, it.directBootSafe, it.hasDifficulty, it.countRange, it.defaultCount)
            }

        assertEquals(
            mapOf(
                "Math" to listOf(false, true, true, 1..10, 3),
                "Placeholder" to listOf(false, true, false, 1..1, 1),
            ),
            declared,
        )
        CheckType.all.forEach { assertTrue(it.defaultCount in it.countRange, it.id) }
    }

    @Test
    fun `generate is deterministic per seed for every type and difficulty`() {
        CheckType.all.forEach { type ->
            Difficulty.entries.forEach { difficulty ->
                val seeds = List(10_000) { SeedDeriver.seed("session-$it", 1, 0, 0) }
                val first = seeds.map { type.generate(it, difficulty, type.defaultCount) }
                val second = seeds.map { type.generate(it, difficulty, type.defaultCount) }

                assertEquals(first, second, "${type.id} $difficulty")
            }
        }
    }

    @Test
    fun `different seeds give different Math puzzles`() {
        Difficulty.entries.forEach { difficulty ->
            val puzzles = List(10_000) { CheckType.Math.generate(SeedDeriver.seed("session-$it", 1, 0, 0), difficulty, 3) }.toSet()
            assertTrue(puzzles.size > 9_900, "$difficulty: ${puzzles.size} distinct")
        }
        assertNotEquals(CheckType.Math.generate(1, Difficulty.Easy, 3), CheckType.Math.generate(2, Difficulty.Easy, 3))
    }

    @Test
    fun `a seed is pinned to its puzzle, so a session stored before an update shows the same puzzle after it`() {
        val puzzle = CheckType.Math.generate(SeedDeriver.seed("session-1", 1, 0, 0), Difficulty.Medium, 3)

        assertEquals(PINNED_PUZZLE, puzzle)
    }

    @Test
    fun `the placeholder stand-in is passed by the placeholder answer only`() {
        val puzzle = CheckType.Placeholder.generate(1, Difficulty.Hard, 5)

        assertEquals(Puzzle.Placeholder, puzzle)
        assertEquals(1, puzzle.size)
        assertEquals(CheckResult.Correct, CheckType.Placeholder.validate(puzzle, 0, CheckAnswer.Placeholder))
        assertEquals(CheckResult.Wrong, CheckType.Placeholder.validate(puzzle, 1, CheckAnswer.Placeholder))
        assertEquals(CheckResult.Wrong, CheckType.Placeholder.validate(puzzle, 0, CheckAnswer.Number("1")))
        assertEquals(CheckResult.Wrong, CheckType.Placeholder.validate(Puzzle.Math(emptyList()), 0, CheckAnswer.Placeholder))
    }

    @Test
    fun `types, puzzles, answers and plans serialize with stable names`() {
        val json = SessionJson.json
        val plan = CheckPlan(CheckMode.Random, listOf(CheckEntry(CheckType.Math, Difficulty.Easy, 4), CheckPlan.PLACEHOLDER_ENTRY))
        val puzzle: Puzzle = Puzzle.Math(listOf(MathProblem(listOf(23, 4, 17), listOf(MathOperator.Times, MathOperator.Plus))))
        val answers = listOf(CheckAnswer.Number("0109"), CheckAnswer.Placeholder, CheckAnswer.ImageMatched)

        assertEquals(
            """{"mode":"Random","entries":[{"type":{"type":"Math"},"difficulty":"Easy","count":4},""" +
                """{"type":{"type":"Placeholder"},"difficulty":"Medium","count":1}]}""",
            json.encodeToString(CheckPlan.serializer(), plan),
        )
        assertEquals(plan, json.decodeFromString(CheckPlan.serializer(), json.encodeToString(CheckPlan.serializer(), plan)))
        val encodedPuzzle = json.encodeToString(Puzzle.serializer(), puzzle)
        assertEquals("""{"type":"Math","problems":[{"operands":[23,4,17],"operators":["Times","Plus"]}]}""", encodedPuzzle)
        assertEquals(puzzle, json.decodeFromString(Puzzle.serializer(), encodedPuzzle))
        assertEquals(
            Puzzle.Placeholder,
            json.decodeFromString(Puzzle.serializer(), json.encodeToString(Puzzle.serializer(), Puzzle.Placeholder)),
        )
        answers.forEach { answer ->
            assertEquals(answer, json.decodeFromString(CheckAnswer.serializer(), json.encodeToString(CheckAnswer.serializer(), answer)))
        }
        assertEquals("""{"type":"Number","digits":"0109"}""", json.encodeToString(CheckAnswer.serializer(), answers.first()))
    }

    private companion object {
        /** Seed ("session-1", ring 1, entry 0, attempt 0), Medium, 3 problems. */
        val PINNED_PUZZLE: Puzzle =
            Puzzle.Math(
                listOf(
                    MathProblem(listOf(31, 3, 95), listOf(MathOperator.Times, MathOperator.Plus)),
                    MathProblem(listOf(15, 7, 74), listOf(MathOperator.Times, MathOperator.Plus)),
                    MathProblem(listOf(46, 7, 31), listOf(MathOperator.Times, MathOperator.Plus)),
                ),
            )
    }
}
