package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.session.CheckRun

/**
 * The right answer to the current item of [run], as a user would give it: the number of a Math problem, the next tile of
 * a Memory round, the word of a Word item, the placeholder answer of a placeholder entry; null once every entry is passed
 * (or without a seed). Tests only: the app never knows the answer outside the validator.
 */
fun rightAnswer(run: CheckRun): CheckAnswer? {
    val entry = run.currentEntry
    val seed = run.seeds.getOrNull(run.step.entry)
    return if (entry == null || seed == null) {
        null
    } else {
        when (val puzzle = entry.type.generate(seed, entry.difficulty, entry.count)) {
            is Puzzle.Math -> puzzle.problems.getOrNull(run.step.item)?.let { CheckAnswer.Number(it.answer.toString()) }
            is Puzzle.Memory -> puzzle.taps.getOrNull(run.step.item)?.let { CheckAnswer.Tile(it) }
            is Puzzle.Word -> puzzle.words.getOrNull(run.step.item)?.let { CheckAnswer.Word(it) }
            Puzzle.Placeholder -> CheckAnswer.Placeholder.takeIf { entry.type == CheckType.Placeholder }
        }
    }
}

/** A wrong answer to the current Math item of [run] (the right one plus one), or null when the item is not Math. */
fun wrongAnswer(run: CheckRun): CheckAnswer? =
    (rightAnswer(run) as? CheckAnswer.Number)?.let { CheckAnswer.Number((it.digits.toInt() + 1).toString()) }
