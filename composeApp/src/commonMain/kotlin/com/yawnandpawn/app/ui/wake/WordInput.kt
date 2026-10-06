package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.session.SessionState
import kotlin.random.Random

/**
 * The Word Unscramble item a check waits on (Story 3.7): word [wordNumber] of [wordCount], shown as [scramble]
 * (lowercase letters of the target).
 */
data class WordRound(
    val wordNumber: Int,
    val wordCount: Int,
    val scramble: String,
)

/** The item [item] of a Word puzzle, or null when [puzzle] is not one or [item] is past its end. Pure. */
fun wordRound(
    puzzle: Puzzle,
    item: Int,
): WordRound? {
    val words = puzzle as? Puzzle.Word
    val scramble = words?.scrambles?.getOrNull(item)
    return if (words == null || scramble == null) null else WordRound(item + 1, words.size, scramble)
}

/**
 * The Word item [state] waits on: Grace or Loud with a Word Unscramble entry current, from the entry's seed through the
 * core plugin (and the installed word list). For 3.2's wake renderer.
 */
fun wordRound(state: SessionState): WordRound? {
    val run = ((state as? SessionState.Grace)?.session ?: (state as? SessionState.Loud)?.session)?.checkRun
    val entry = run?.currentEntry?.takeIf { it.type == CheckType.WordUnscramble }
    val seed = run?.seeds?.getOrNull(run.step.entry)
    return if (entry == null || seed == null) null else wordRound(entry.type.generate(seed, entry.difficulty, entry.count), run.step.item)
}

/**
 * The letters of one Word Unscramble item on screen (Story 3.7), UI only: the scrambled [letters] in [order] (the pool
 * as shown; "Shuffle" changes only this), and the answer [slots], each holding the index of a letter or null. A letter
 * in a slot leaves its pool place empty, and goes back there when its slot is tapped. Pure.
 */
data class WordInput(
    val letters: String,
    val order: List<Int> = letters.indices.toList(),
    val slots: List<Int?> = List(letters.length) { null },
    val shuffles: Int = 0,
) {
    /** The pool as shown: each letter, or null where it moved into a slot. */
    val pool: List<Char?>
        get() = order.map { index -> letters[index].takeUnless { index in slots } }

    /** The answer slots as shown. */
    val answerLetters: List<Char?>
        get() = slots.map { index -> index?.let { letters[it] } }

    /** Every slot is filled: the word to submit, else null. */
    val answer: String?
        get() = slots.filterNotNull().takeIf { it.size == slots.size }?.joinToString("") { letters[it].toString() }

    /** The pool letter at display place [place] moves into the first empty slot (nothing when it already moved). */
    fun tappedLetter(place: Int): WordInput {
        val index = order.getOrNull(place)
        val slot = slots.indexOfFirst { it == null }
        return if (index == null || index in slots || slot < 0) this else copy(slots = slots.toMutableList().also { it[slot] = index })
    }

    /** The letter in slot [slot] goes back to its pool place. */
    fun tappedSlot(slot: Int): WordInput =
        if (slots.getOrNull(slot) == null) this else copy(slots = slots.toMutableList().also { it[slot] = null })

    /** "Clear": every letter back in the pool. */
    fun cleared(): WordInput = copy(slots = List(letters.length) { null })

    /** "Shuffle": the pool in a new order (display only; the puzzle and the slots stay). Deterministic per shuffle. */
    fun shuffled(): WordInput = copy(order = order.shuffled(Random(SHUFFLE_SEED + shuffles)), shuffles = shuffles + 1)

    /** The content the approved composable shows, uppercase, as word [round] of its puzzle. */
    fun content(
        round: WordRound,
        wrong: Boolean,
    ): CheckContent.WordUnscramble =
        CheckContent.WordUnscramble(
            wordNumber = round.wordNumber,
            wordCount = round.wordCount,
            pool = pool.map { it?.uppercaseChar() },
            slots = answerLetters.map { it?.uppercaseChar() },
            wrong = wrong,
        )

    private companion object {
        const val SHUFFLE_SEED = 7_331
    }
}
