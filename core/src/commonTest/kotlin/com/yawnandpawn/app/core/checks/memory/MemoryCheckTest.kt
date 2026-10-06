package com.yawnandpawn.app.core.checks.memory

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckResult
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.checks.SeedDeriver
import com.yawnandpawn.app.core.checks.accessibleEntries
import com.yawnandpawn.app.core.session.SessionJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Story 3.8: the Memory Sequence plugin (FR-PWK-4). */
class MemoryCheckTest {
    private val memory = CheckType.MemorySequence()
    private val numbered = CheckType.MemorySequence(numbered = true)

    private fun puzzle(
        type: CheckType = memory,
        seed: Long = 1L,
        difficulty: Difficulty = Difficulty.Medium,
        count: Int = 2,
    ): Puzzle.Memory = type.generate(seed, difficulty, count) as Puzzle.Memory

    @Test
    fun `grids and lengths by difficulty over 10,000 seeds, tiles on the grid, never twice in a row`() {
        val expected = mapOf(Difficulty.Easy to (3 to 4), Difficulty.Medium to (3 to 6), Difficulty.Hard to (4 to 8))
        expected.forEach { (difficulty, shape) ->
            val (grid, length) = shape
            repeat(10_000) { n ->
                val puzzle = puzzle(seed = SeedDeriver.seed("s-$n", 1, 0, 0), difficulty = difficulty, count = 3)
                assertEquals(grid, puzzle.gridSize, "$difficulty")
                assertEquals(3, puzzle.rounds.size)
                puzzle.rounds.forEach { round ->
                    assertEquals(length, round.size, "$difficulty")
                    assertTrue(round.all { it in 1..grid * grid }, "$difficulty $round")
                    assertTrue(round.zipWithNext().none { (a, b) -> a == b }, "no repeat in a row: $round")
                }
            }
        }
    }

    @Test
    fun `the numbered variant always uses 3x3 with the same lengths`() {
        Difficulty.entries.forEach { difficulty ->
            val puzzle = puzzle(numbered, difficulty = difficulty)
            assertEquals(3, puzzle.gridSize, "$difficulty")
            assertEquals(MemoryGenerator.roundLength(difficulty), puzzle.rounds.first().size)
            assertTrue(puzzle.taps.all { it in 1..9 })
        }
    }

    @Test
    fun `a seed always gives the same rounds (pinned), and the count is brought into 1 to 5`() {
        // Pinned: stored sessions depend on it, so a change to the generator must be a deliberate test change.
        assertEquals(PINNED, puzzle(seed = 42L, difficulty = Difficulty.Easy, count = 2).rounds)
        assertEquals(puzzle(seed = 7L), puzzle(seed = 7L))
        assertNotEquals(puzzle(seed = 7L), puzzle(seed = 8L))
        assertEquals(5, puzzle(count = 9).rounds.size)
        assertEquals(1, puzzle(count = 0).rounds.size)
        assertEquals(puzzle(count = 2).size, 12)
    }

    @Test
    fun `each right tap is ItemCorrect across the rounds, and the last one is Correct`() {
        val puzzle = puzzle(count = 2)
        val taps = puzzle.taps

        taps.dropLast(1).forEachIndexed { position, tile ->
            assertSame(CheckResult.ItemCorrect, memory.validate(puzzle, position, CheckAnswer.Tile(tile)), "tap $position")
        }
        assertSame(CheckResult.Correct, memory.validate(puzzle, taps.lastIndex, CheckAnswer.Tile(taps.last())))
    }

    @Test
    fun `a wrong tile is WrongRestart, anything that is not a tap at a position of the puzzle is Wrong`() {
        val puzzle = puzzle()
        val wrongTile = (1..9).first { it != puzzle.taps[3] }

        assertSame(CheckResult.WrongRestart, memory.validate(puzzle, 3, CheckAnswer.Tile(wrongTile)))
        assertSame(CheckResult.Wrong, memory.validate(puzzle, 3, CheckAnswer.Number("4")))
        assertSame(CheckResult.Wrong, memory.validate(puzzle, puzzle.size, CheckAnswer.Tile(1)))
        assertSame(CheckResult.Wrong, memory.validate(puzzle, -1, CheckAnswer.Tile(1)))
        assertSame(CheckResult.Wrong, memory.validate(Puzzle.Placeholder, 0, CheckAnswer.Tile(1)))
    }

    @Test
    fun `a restart goes back to the current round's first tap, other types to item 0`() {
        assertEquals(0, memory.restartFrom(3, Difficulty.Medium))
        assertEquals(6, memory.restartFrom(6, Difficulty.Medium))
        assertEquals(6, memory.restartFrom(11, Difficulty.Medium))
        assertEquals(8, memory.restartFrom(15, Difficulty.Hard))
        assertEquals(4, memory.restartFrom(7, Difficulty.Easy))
        assertEquals(0, memory.restartFrom(-2, Difficulty.Easy))
        assertEquals(0, CheckType.Math.restartFrom(2, Difficulty.Medium))
        assertEquals(0, CheckType.Placeholder.restartFrom(0, Difficulty.Medium))
    }

    @Test
    fun `with a screen reader on, Memory entries become the numbered variant and the others stay`() {
        val entries = listOf(CheckEntry(memory, Difficulty.Hard, 2), CheckEntry(CheckType.Math, Difficulty.Easy, 3))

        assertEquals(entries, accessibleEntries(entries, accessible = false))
        assertEquals(
            listOf(CheckEntry(numbered, Difficulty.Hard, 2), CheckEntry(CheckType.Math, Difficulty.Easy, 3)),
            accessibleEntries(entries, accessible = true),
        )
        assertEquals(memory.id, numbered.id)
    }

    @Test
    fun `both variants survive the session JSON`() {
        val plan = CheckPlan(CheckMode.All, listOf(CheckEntry(memory, Difficulty.Easy, 1), CheckEntry(numbered, Difficulty.Hard, 2)))

        assertEquals(plan, SessionJson.json.decodeFromString<CheckPlan>(SessionJson.json.encodeToString(plan)))
    }

    private companion object {
        val PINNED = listOf(listOf(1, 9, 4, 1), listOf(8, 1, 2, 9))
    }
}
