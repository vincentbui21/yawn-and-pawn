package com.yawnandpawn.app.ui.checks

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckResult
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.ui.checksetup.CheckPreviewUiState
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.MathOperator
import com.yawnandpawn.app.ui.wake.WakeIntent
import kotlin.time.Duration
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType
import com.yawnandpawn.app.core.checks.math.MathOperator as CoreMathOperator

/**
 * A "Try it" run of one check (FR-PWK-12, Story 3.6): what the preview shows and how it answers a tap. A pure value with
 * no session, sound, history or scheduler behind it; only the core plugin's `validate` decides whether an answer is
 * right.
 */
interface CheckTrial {
    /** What `CheckPreviewScreen` renders. */
    val state: CheckPreviewUiState

    /** This trial after [intent]; taps a check does not use leave it unchanged. */
    fun onIntent(intent: WakeIntent): CheckTrial

    /** How long until [tick] changes what it shows by itself (Memory's playback), or null when it waits for a tap. */
    val nextTick: Duration?
        get() = null

    /** This trial after [nextTick] has passed. */
    fun tick(): CheckTrial = this
}

/**
 * The checks' UI (AD-9): each check story registers how to try it (Story 3.6: Math). A check without an entry is never
 * offered by the pickers ([PickableCheckTypes]).
 */
object CheckRegistry {
    /**
     * A "Try it" of each registered check at its difficulty, with one item, from the seed; [accessible]: TalkBack on;
     * the registered code for QR/Barcode (Story 3.10), which has no trial without one.
     */
    private val trials: Map<CheckType, (difficulty: Difficulty, seed: Long, accessible: Boolean, code: RegisteredCode?) -> CheckTrial?> =
        mapOf(
            CheckType.Math to { difficulty, seed, _, _ -> MathTrial.start(difficulty, seed) },
            CheckType.WordUnscramble to { difficulty, seed, _, _ -> WordTrial.start(difficulty, seed) },
            CheckType.MemorySequence to { difficulty, seed, accessible, _ -> MemoryTrial.start(difficulty, seed, numbered = accessible) },
            CheckType.QrBarcode to { _, _, _, code -> code?.let(QrTrial::start) },
        )

    /** The checks with a registered trial. */
    val types: Set<CheckType>
        get() = trials.keys

    /**
     * "Try it" of [type] at [difficulty] with one item, from [seed]; with [accessible] (TalkBack on) in its accessible
     * variant where it has one (Memory Sequence's numbered tiles). Null for a check without a registered trial.
     */
    fun startTrial(
        type: CheckType,
        difficulty: Difficulty,
        seed: Long,
        accessible: Boolean = false,
        code: RegisteredCode? = null,
    ): CheckTrial? = trials[type]?.invoke(difficulty, seed, accessible, code)
}

/**
 * A Math "Try it": one problem from the core generator, typed on the same pad as the real check (at most 5 digits).
 * "Check" with no digits does nothing; a wrong answer clears the field and shows the same feedback as the real check
 * until the next digit; the right one shows "Nice. That's how it works.".
 */
class MathTrial private constructor(
    private val puzzle: Puzzle.Math,
    private val digits: String,
    private val wrong: Boolean,
    private val done: Boolean,
) : CheckTrial {
    override val state: CheckPreviewUiState
        get() {
            val problem = puzzle.problems.first()
            return CheckPreviewUiState(
                content =
                    CheckContent.Math(
                        problemNumber = 1,
                        problemCount = puzzle.problems.size,
                        operands = problem.operands,
                        operators = problem.operators.map { it.toUi() },
                        answer = digits,
                        wrong = wrong,
                    ),
                done = done,
            )
        }

    override fun onIntent(intent: WakeIntent): CheckTrial =
        when {
            done -> this
            intent is WakeIntent.DigitTapped && digits.length < MAX_DIGITS -> copy(digits = digits + intent.digit, wrong = false)
            intent == WakeIntent.DeleteDigit -> copy(digits = digits.dropLast(1))
            intent == WakeIntent.SubmitAnswer && digits.isNotEmpty() -> submitted()
            else -> this
        }

    private fun submitted(): MathTrial =
        when (CoreCheckType.Math.validate(puzzle, position = 0, answer = CheckAnswer.Number(digits))) {
            CheckResult.Correct, CheckResult.ItemCorrect -> copy(done = true)
            CheckResult.Wrong, CheckResult.WrongRestart -> copy(digits = "", wrong = true)
        }

    private fun copy(
        digits: String = this.digits,
        wrong: Boolean = this.wrong,
        done: Boolean = this.done,
    ) = MathTrial(puzzle, digits, wrong, done)

    companion object {
        /** The most digits the answer field takes, as on the real check. */
        const val MAX_DIGITS = 5

        /** A trial of one problem at [difficulty], generated by the core plugin from [seed]. */
        fun start(
            difficulty: Difficulty,
            seed: Long,
        ): MathTrial = MathTrial(CoreCheckType.Math.generate(seed, difficulty.toCore(), count = 1) as Puzzle.Math, "", false, false)
    }
}

private fun CoreMathOperator.toUi(): MathOperator =
    when (this) {
        CoreMathOperator.Plus -> MathOperator.Plus
        CoreMathOperator.Times -> MathOperator.Times
        CoreMathOperator.Minus -> MathOperator.Minus
    }
