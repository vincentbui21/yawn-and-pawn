package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.session.CheckRun

/**
 * The right answer to the current item of [run], as a user would give it: the number of a Math problem, the next tile of
 * a Memory round, the word of a Word item, the registered code of a QR/Barcode entry, the placeholder answer of a
 * placeholder entry; null once every entry is passed (or without a seed or a code). Tests only: the app never knows the
 * answer outside the validator.
 */
fun rightAnswer(run: CheckRun): CheckAnswer? {
    val entry = run.currentEntry
    val seed = run.seeds.getOrNull(run.step.entry)
    return if (entry == null || seed == null) {
        null
    } else {
        when (val puzzle = entry.puzzle(seed)) {
            is Puzzle.Math -> puzzle.problems.getOrNull(run.step.item)?.let { CheckAnswer.Number(it.answer.toString()) }
            is Puzzle.Memory -> puzzle.taps.getOrNull(run.step.item)?.let { CheckAnswer.Tile(it) }
            is Puzzle.Word -> puzzle.words.getOrNull(run.step.item)?.let { CheckAnswer.Word(it) }
            is Puzzle.Code -> puzzle.code?.let(CheckAnswer::Code)
            Puzzle.Placeholder -> CheckAnswer.Placeholder.takeIf { entry.type == CheckType.Placeholder }
        }
    }
}

/**
 * A wrong answer to the current item of [run]: for Math the right number plus one, for QR/Barcode another code of the same
 * format; null otherwise.
 */
fun wrongAnswer(run: CheckRun): CheckAnswer? =
    when (val right = rightAnswer(run)) {
        is CheckAnswer.Number -> CheckAnswer.Number((right.digits.toInt() + 1).toString())
        is CheckAnswer.Code -> RegisteredCode.of(right.code.format, OTHER_CODE_VALUE)?.let(CheckAnswer::Code)
        else -> null
    }

/** The raw value of [aRegisteredCode]: "Toothpaste 4006381333931" as an EAN-13 barcode would read it. */
const val A_CODE_VALUE = "4006381333931"

/** A raw value that is not [A_CODE_VALUE]. */
const val OTHER_CODE_VALUE = "5901234123457"

/** A registered EAN-13 code with the raw value [value] (by default [A_CODE_VALUE]). */
fun aRegisteredCode(
    value: String = A_CODE_VALUE,
    format: CodeFormat = CodeFormat.Ean13,
): RegisteredCode = requireNotNull(RegisteredCode.of(format, value)) { "a blank code" }
