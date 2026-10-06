package com.yawnandpawn.app.core.checks

/**
 * The only source of check seeds (AD-9, FR-PWK-1). A seed is a pure function of where the puzzle sits in the session,
 * so a restored session derives the same puzzles and nothing in `:core` draws random numbers.
 *
 * Keys of [seed]'s `entryIndex`: entry `i` of a ring's plan is `i`, and the Random pick of the plan is [PICK]. A fallback
 * plan adds [FALLBACK_BASE] to both, so its seeds never repeat the plan's. `attempt` is 0 for the first puzzle of an
 * entry in a ring, and the failed-attempt count for a puzzle started over (`CheckResult.WrongRestart`).
 */
object SeedDeriver {
    /** The `entryIndex` key of the seed that picks the entry of a Random plan. */
    const val PICK = -1

    /** Added to every key of a fallback plan (FR-PWK-11). */
    const val FALLBACK_BASE = 1_000

    /** The seed of [entryIndex] in ring [ringIndex] of session [sessionId], for [attempt]. FNV-1a, then SplitMix64's mix. */
    fun seed(
        sessionId: String,
        ringIndex: Int,
        entryIndex: Int,
        attempt: Int,
    ): Long {
        var hash = FNV_OFFSET
        for (char in sessionId) {
            hash = (hash xor (char.code and BYTE_MASK).toLong()) * FNV_PRIME
            hash = (hash xor (char.code ushr Byte.SIZE_BITS).toLong()) * FNV_PRIME
        }
        for (value in intArrayOf(ringIndex, entryIndex, attempt)) {
            for (shift in 0 until Int.SIZE_BITS step Byte.SIZE_BITS) {
                hash = (hash xor ((value ushr shift) and BYTE_MASK).toLong()) * FNV_PRIME
            }
        }
        return mix64(hash)
    }

    private const val BYTE_MASK = 0xFF
    private const val FNV_PRIME = 0x100000001b3L

    /** The 64-bit FNV-1a offset basis 0xcbf29ce484222325 as a signed Long. */
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
}

/**
 * A small deterministic generator (SplitMix64) for the check generators. It is our own algorithm, not
 * `kotlin.random.Random`, whose seeded sequence may change between Kotlin versions: a session stored before an app
 * update must still show the same puzzle after it.
 */
internal class SeededRandom(
    seed: Long,
) {
    private var state = seed

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        return mix64(state)
    }

    /** A uniform value in [range] (not empty), without modulo bias. */
    fun nextInt(range: IntRange): Int {
        require(!range.isEmpty()) { "empty range $range" }
        val bound = range.last.toLong() - range.first + 1
        // Values above the largest multiple of bound that fits are drawn again (almost never happens).
        val limit = Long.MAX_VALUE - ((Long.MAX_VALUE % bound) + 1) % bound
        var value: Long
        do {
            value = nextLong() ushr 1
        } while (value > limit)
        return (range.first + value % bound).toInt()
    }

    /** True or false with even odds. */
    fun nextBoolean(): Boolean = nextLong() < 0

    private companion object {
        /** 0x9e3779b97f4a7c15 as a signed Long. */
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL
    }
}

/** 0xbf58476d1ce4e5b9 as a signed Long. */
private const val MIX_1 = -0x40a7b892e31b1a47L

/** 0x94d049bb133111eb as a signed Long. */
private const val MIX_2 = -0x6b2fb644ecceee15L
private const val SHIFT_1 = 30
private const val SHIFT_2 = 27
private const val SHIFT_3 = 31

/** The SplitMix64 finalizer: spreads every input bit over the whole result. */
internal fun mix64(input: Long): Long {
    var z = input
    z = (z xor (z ushr SHIFT_1)) * MIX_1
    z = (z xor (z ushr SHIFT_2)) * MIX_2
    return z xor (z ushr SHIFT_3)
}
