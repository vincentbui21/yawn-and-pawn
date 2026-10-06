package com.yawnandpawn.app.core.checks.word

import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.SeededRandom

/**
 * The Word Unscramble words and their scrambles (FR-PWK-8): `count` distinct words of [difficulty]'s length group, picked
 * by seed, each shown as a seeded permutation of its letters that is neither the word nor any listed word. A word whose
 * every tried permutation is listed is skipped for the next pick (in practice a list word always has one).
 */
internal object WordGenerator {
    /** Each word with its scramble, fully determined by [seed] and [list]. */
    fun words(
        seed: Long,
        difficulty: Difficulty,
        count: Int,
        list: WordList,
    ): List<Pair<String, String>> {
        val random = SeededRandom(seed)
        val remaining = list.bucket(difficulty).toMutableList()
        val picked = mutableListOf<Pair<String, String>>()
        while (picked.size < count && remaining.isNotEmpty()) {
            val word = remaining.removeAt(random.nextInt(remaining.indices))
            scramble(word, random, list)?.let { picked += word to it }
        }
        return picked
    }

    /** A permutation of [word] that is neither [word] nor a listed word, or null after [MAX_TRIES] draws. */
    private fun scramble(
        word: String,
        random: SeededRandom,
        list: WordList,
    ): String? {
        repeat(MAX_TRIES) {
            val letters = word.toCharArray()
            // Fisher–Yates from the seeded stream.
            for (i in letters.lastIndex downTo 1) {
                val j = random.nextInt(0..i)
                letters[i] = letters[j].also { letters[j] = letters[i] }
            }
            val candidate = letters.concatToString()
            if (candidate != word && !list.contains(candidate)) return candidate
        }
        return null
    }

    private const val MAX_TRIES = 64
}
