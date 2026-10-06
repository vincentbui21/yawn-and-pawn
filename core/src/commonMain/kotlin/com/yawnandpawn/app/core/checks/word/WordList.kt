package com.yawnandpawn.app.core.checks.word

import com.yawnandpawn.app.core.checks.Difficulty
import kotlin.concurrent.Volatile

/**
 * The Word Unscramble word list (FR-PWK-8, Story 3.7): the bundled `words_en.txt` as the app loaded it. `:core` never
 * reads files; the app hands the words to [WordBank]. Words are kept lowercase a–z; anything else is dropped (the
 * `checkWordList` task keeps the asset clean, so nothing is dropped in practice).
 */
class WordList(
    words: Collection<String>,
) {
    /** Every word, lowercase, sorted (so a seed picks the same word from the same list). */
    val words: List<String> =
        words
            .map { it.trim().lowercase() }
            .filter {
                it.isNotEmpty() &&
                    it.all { c ->
                        c in 'a'..'z'
                    }
            }.distinct()
            .sorted()

    // The cleaned list, not the constructor argument of the same name (review fix: " Notes " must count as "notes").
    private val set: Set<String> = this.words.toSet()

    /** Words by their letters sorted: the anagram index. */
    private val byLetters: Map<String, Set<String>> = this.words.groupBy { it.sortedLetters() }.mapValues { (_, list) -> list.toSet() }

    private val buckets: Map<Difficulty, List<String>> =
        Difficulty.entries.associateWith { difficulty -> this.words.filter { it.length in lengths(difficulty) } }

    /** True when [word] is in the list (any case). */
    fun contains(word: String): Boolean = word.lowercase() in set

    /** The listed words with exactly the letters of [word] (its anagrams, [word] included when listed). */
    fun anagramsOf(word: String): Set<String> = byLetters[word.lowercase().sortedLetters()].orEmpty()

    /** The words of [difficulty]'s length group, sorted. */
    fun bucket(difficulty: Difficulty): List<String> = buckets.getValue(difficulty)

    companion object {
        /** The word lengths of each difficulty (owner-approved default 2026-09-26): 4–5, 6–7 and 8–10 letters. */
        fun lengths(difficulty: Difficulty): IntRange =
            when (difficulty) {
                Difficulty.Easy -> EASY
                Difficulty.Medium -> MEDIUM
                Difficulty.Hard -> HARD
            }

        private val EASY = 4..5
        private val MEDIUM = 6..7
        private val HARD = 8..10
    }
}

internal fun String.sortedLetters(): String = toCharArray().sorted().joinToString("")

/**
 * The word list the process uses (Story 3.7). The app installs the bundled list once at start, before any ring or
 * preview (a bundled asset is readable before the first unlock); tests install their own. Until then it is empty and no
 * Word Unscramble puzzle can be made, so the app always installs it first.
 */
object WordBank {
    /** The installed list; empty until [install]. Volatile: installed on the main thread, read on the engine's. */
    @Volatile
    var current: WordList = WordList(emptyList())
        private set

    /** Installs [list] for every later puzzle. */
    fun install(list: WordList) {
        current = list
    }
}
