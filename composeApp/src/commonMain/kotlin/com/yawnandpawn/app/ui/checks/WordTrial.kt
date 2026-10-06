package com.yawnandpawn.app.ui.checks

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckResult
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.ui.checksetup.CheckPreviewUiState
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.ui.wake.WordInput
import com.yawnandpawn.app.ui.wake.WordRound
import com.yawnandpawn.app.ui.wake.wordRound
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType

/**
 * A Word Unscramble "Try it" (Story 3.7): one word from the core generator and the installed list, spelled on the same
 * tiles as the real check. When every slot is filled the word is checked by the core plugin: a listed word with the same
 * letters is right too; a wrong one clears the slots and shows the real check's feedback until the next tap.
 */
class WordTrial private constructor(
    private val puzzle: Puzzle,
    private val round: WordRound,
    private val input: WordInput,
    private val wrong: Boolean,
    private val done: Boolean,
) : CheckTrial {
    override val state: CheckPreviewUiState
        get() = CheckPreviewUiState(content = input.content(round, wrong), done = done)

    override fun onIntent(intent: WakeIntent): CheckTrial {
        val next =
            when (intent) {
                is WakeIntent.LetterTapped -> input.tappedLetter(intent.index)
                is WakeIntent.SlotTapped -> input.tappedSlot(intent.index)
                WakeIntent.ShuffleLetters -> input.shuffled()
                WakeIntent.ClearLetters -> input.cleared()
                else -> null
            }
        return when {
            done || next == null -> this
            next.answer != null -> submitted(next)
            else -> WordTrial(puzzle, round, next, wrong = false, done = false)
        }
    }

    /** Every slot filled: the core plugin decides. */
    private fun submitted(filled: WordInput): WordTrial {
        val result = CoreCheckType.WordUnscramble.validate(puzzle, round.wordNumber - 1, CheckAnswer.Word(filled.answer.orEmpty()))
        return if (result == CheckResult.Correct || result == CheckResult.ItemCorrect) {
            WordTrial(puzzle, round, filled, wrong = false, done = true)
        } else {
            WordTrial(puzzle, round, filled.cleared(), wrong = true, done = false)
        }
    }

    companion object {
        /** A trial of one word at [difficulty] from [seed]; null when no word list is installed. */
        fun start(
            difficulty: Difficulty,
            seed: Long,
        ): WordTrial? {
            val puzzle = CoreCheckType.WordUnscramble.generate(seed, difficulty.toCore(), count = 1)
            return wordRound(puzzle, 0)?.let { WordTrial(puzzle, it, WordInput(it.scramble), wrong = false, done = false) }
        }
    }
}
