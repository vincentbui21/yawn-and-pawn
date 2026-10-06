package com.yawnandpawn.app.core.checks.memory

import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.SeededRandom

/**
 * The Memory Sequence check's rounds (FR-PWK-4, owner-approved default 2026-09-26): sequences of 4, 6 or 8 tiles by
 * difficulty, on a 3×3 grid (tiles 1–9), or 4×4 on Hard (tiles 1–16) unless [numbered] (the accessible variant always
 * uses 3×3). A tile never comes twice in a row.
 */
internal object MemoryGenerator {
    /** The grid side at [difficulty]: 4 on Hard, else 3; always 3 for the [numbered] variant. */
    fun gridSize(
        difficulty: Difficulty,
        numbered: Boolean,
    ): Int = if (difficulty == Difficulty.Hard && !numbered) LARGE_GRID else SMALL_GRID

    /** How many tiles one round lights at [difficulty]. */
    fun roundLength(difficulty: Difficulty): Int =
        when (difficulty) {
            Difficulty.Easy -> EASY_LENGTH
            Difficulty.Medium -> MEDIUM_LENGTH
            Difficulty.Hard -> HARD_LENGTH
        }

    /** [count] rounds at [difficulty] on a grid of [gridSize], fully determined by [seed]. */
    fun rounds(
        seed: Long,
        difficulty: Difficulty,
        gridSize: Int,
        count: Int,
    ): List<List<Int>> {
        val random = SeededRandom(seed)
        val tiles = 1..gridSize * gridSize
        return List(count) {
            val sequence = mutableListOf<Int>()
            repeat(roundLength(difficulty)) {
                var tile = random.nextInt(tiles)
                // Drawn again (from the same stream) while it repeats the tile before it.
                while (tile == sequence.lastOrNull()) tile = random.nextInt(tiles)
                sequence += tile
            }
            sequence
        }
    }

    private const val SMALL_GRID = 3
    private const val LARGE_GRID = 4
    private const val EASY_LENGTH = 4
    private const val MEDIUM_LENGTH = 6
    private const val HARD_LENGTH = 8
}
