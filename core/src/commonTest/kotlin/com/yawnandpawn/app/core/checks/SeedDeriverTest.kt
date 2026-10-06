package com.yawnandpawn.app.core.checks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SeedDeriverTest {
    @Test
    fun `the generator is SplitMix64, so a seed gives the same numbers in every app version`() {
        // The published SplitMix64 outputs for seed 0: 0xe220a8397b1dcdaf, 0x6e789e6aa1b965f4.
        val random = SeededRandom(0)
        assertEquals(-2152535657050944081L, random.nextLong())
        assertEquals(7960286522194355700L, random.nextLong())
    }

    @Test
    fun `a seed is the same for the same coordinates and pinned across versions`() {
        val seed = SeedDeriver.seed("session-1", 1, 0, 0)

        assertEquals(seed, SeedDeriver.seed("session-1", 1, 0, 0))
        // A stored session regenerates its puzzle from these keys: a changed derivation changes puzzles after an update.
        assertEquals(PINNED, seed)
    }

    @Test
    fun `every coordinate changes the seed`() {
        val seeds =
            listOf(
                SeedDeriver.seed("session-1", 1, 0, 0),
                SeedDeriver.seed("session-2", 1, 0, 0),
                SeedDeriver.seed("session-1", 2, 0, 0),
                SeedDeriver.seed("session-1", 1, 1, 0),
                SeedDeriver.seed("session-1", 1, 0, 1),
                SeedDeriver.seed("session-1", 1, SeedDeriver.PICK, 0),
                SeedDeriver.seed("session-1", 1, SeedDeriver.FALLBACK_BASE, 0),
            )

        assertEquals(seeds.size, seeds.toSet().size, "$seeds")
    }

    @Test
    fun `seeds do not collide over many sessions, rings, entries and attempts`() {
        val keys =
            buildList {
                repeat(50) { session ->
                    for (ring in 1..6) {
                        for (entry in listOf(SeedDeriver.PICK, 0, 1, 2, 3, SeedDeriver.FALLBACK_BASE - 1, SeedDeriver.FALLBACK_BASE)) {
                            for (attempt in 0..3) add(SeedDeriver.seed("uuid-$session", ring, entry, attempt))
                        }
                    }
                }
            }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `bounded draws stay in range and are spread evenly`() {
        val random = SeededRandom(SeedDeriver.seed("session-1", 1, 0, 0))
        val counts = IntArray(6)
        repeat(60_000) { counts[random.nextInt(0..5)]++ }

        assertTrue(counts.all { it in 9_000..11_000 }, counts.toList().toString())
        assertEquals(setOf(7), List(20) { random.nextInt(7..7) }.toSet())
        assertTrue(List(200) { random.nextInt(Int.MIN_VALUE..Int.MAX_VALUE) }.toSet().size > 190, "full Int range")
        assertEquals(setOf(true, false), List(64) { random.nextBoolean() }.toSet())
        assertFailsWith<IllegalArgumentException> { random.nextInt(IntRange.EMPTY) }
    }

    private companion object {
        const val PINNED = -4250427081643968959L
    }
}
