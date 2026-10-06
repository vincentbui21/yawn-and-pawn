package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.core.checks.math.MathOperator as CoreMathOperator

/**
 * Which problem a check screen shows: session, ring, entry, item and the entry's seed, with the failed attempts on the
 * entry so far. A change of anything but [failedAttempts] is another problem; more [failedAttempts] on the same entry
 * is a wrong answer (`CheckResult.Wrong`, or `WrongRestart` with a new seed).
 */
data class CheckPosition(
    val sessionId: String,
    val ringIndex: Int,
    val entry: Int,
    val item: Int,
    val seed: Long?,
    val failedAttempts: Int,
)

/**
 * The position [state] waits on: Grace or Loud with a check entry left; null otherwise. It is read from the
 * [usable run][CheckRun.usable], the one the engine checks answers on.
 */
fun checkPosition(state: SessionState): CheckPosition? {
    val session = (state as? SessionState.Grace)?.session ?: (state as? SessionState.Loud)?.session ?: return null
    val run = session.usableRun()
    return run.currentEntry?.let {
        CheckPosition(
            session.sessionId,
            session.ringIndex,
            run.step.entry,
            run.step.item,
            run.seeds.getOrNull(run.step.entry),
            run.failedAttempts,
        )
    }
}

/**
 * What the user typed on a check screen (UI only, AD-9): never stored, so digits typed but not submitted are lost on a
 * restore. [following] the engine's position clears the digits on a new problem and on a wrong answer, which also sets
 * [wrong] until the user types again. The UI never decides correctness: it only sees the failed attempts go up.
 */
data class CheckInput(
    val position: CheckPosition? = null,
    val digits: String = "",
    val wrong: Boolean = false,
) {
    /** [digit] appended, at most [MAX_DIGITS] digits; typing clears the wrong-answer message. */
    fun typed(digit: Int): CheckInput = if (digits.length >= MAX_DIGITS) this else copy(digits = digits + digit, wrong = false)

    /** The last digit removed. */
    fun deleted(): CheckInput = copy(digits = digits.dropLast(1))

    /** This input at the engine's [next] position. */
    fun following(next: CheckPosition?): CheckInput {
        val current = position
        return when {
            next == current -> this
            next == null || current == null || !next.sameEntryAs(current) -> CheckInput(next)
            next.failedAttempts > current.failedAttempts -> CheckInput(next, wrong = true)
            next.item != current.item || next.seed != current.seed -> CheckInput(next)
            else -> copy(position = next)
        }
    }

    private fun CheckPosition.sameEntryAs(other: CheckPosition): Boolean =
        sessionId == other.sessionId && ringIndex == other.ringIndex && entry == other.entry

    companion object {
        /** "The answer accepts at most 5 digits" (every Math answer is below 10,000). */
        const val MAX_DIGITS = 5
    }
}

/**
 * The Check screen for [state] when its current entry is a Math check (Story 3.2), else null (the Ringing screen stays,
 * for example on the placeholder entry of a session stored by Epics 1–2). Pure:
 * - the problem comes from the entry's seed through the core plugin, so a restored session shows the same problem. A
 *   damaged row (a missing seed, an item past the end) shows the problem of its [usable run][CheckRun.usable], the one
 *   the engine checks, so the alarm can still be stopped;
 * - in Grace the countdown is read from the grace `Deadline` at [now] (frozen at the pause during a call); in Loud after
 *   a grace window "Time's up. Alarm's back on until you finish."; a ring without grace shows neither;
 * - the footer's snooze is the same [snoozeOffer] the Ringing screen shows, and the phone-call note as there.
 */
fun mathCheckUiState(
    state: SessionState,
    availability: SnoozeAvailability,
    now: TimeSnapshot,
    input: CheckInput,
    priceOf: PriceLookup = NoPrices,
): CheckUiState? {
    val session = (state as? SessionState.Grace)?.session ?: (state as? SessionState.Loud)?.session
    val run = session?.usableRun()
    val puzzle = run?.let(::mathPuzzle)
    val item = run?.step?.item ?: 0
    val problem = puzzle?.problems?.getOrNull(item)
    if (session == null || puzzle == null || problem == null) return null
    return CheckUiState(
        grace = graceState(state, session, now),
        content =
            CheckContent.Math(
                problemNumber = item + 1,
                problemCount = puzzle.problems.size,
                operands = problem.operands,
                operators = problem.operators.map { it.toUi() },
                answer = input.digits,
                wrong = input.wrong,
            ),
        snooze = snoozeOffer(availability, session, priceOf),
        note = if (session.paused) WakeNote.PhoneCall else null,
    )
}

/**
 * The Check screen for [state] when its current entry is a Memory Sequence (Story 3.8), else null: the round and
 * playback [input] holds for that position (see [MemoryInput.following]), with the same grace header, snooze and
 * phone-call note as Math. Pure.
 */
fun memoryCheckUiState(
    state: SessionState,
    availability: SnoozeAvailability,
    now: TimeSnapshot,
    input: MemoryInput,
    priceOf: PriceLookup = NoPrices,
): CheckUiState? {
    val session = (state as? SessionState.Grace)?.session ?: (state as? SessionState.Loud)?.session
    val round = input.round?.takeIf { input.position == checkPosition(state) }
    val playback = input.playback
    if (session == null || round == null || playback == null) return null
    return CheckUiState(
        grace = graceState(state, session, now),
        content = memoryCheckContent(round, playback, input.wrong),
        snooze = snoozeOffer(availability, session, priceOf),
        note = if (session.paused) WakeNote.PhoneCall else null,
    )
}

/** Both positions are on the same entry of the same ring of the same session (null never is). */
internal fun CheckPosition?.isSameEntryAs(other: CheckPosition?): Boolean =
    this != null && other != null && sessionId == other.sessionId && ringIndex == other.ringIndex && entry == other.entry

/** This session's run as the engine checks answers on it ([CheckRun.usable]). */
internal fun SessionData.usableRun(): CheckRun = checkRun.usable(sessionId, ringIndex)

/** The puzzle of [run]'s current entry from the entry's seed, when it is a Math entry. */
private fun mathPuzzle(run: CheckRun): Puzzle.Math? {
    val entry = run.currentEntry?.takeIf { it.type == CheckType.Math }
    val seed = run.seeds.getOrNull(run.step.entry)
    return if (entry == null || seed == null) null else entry.type.generate(seed, entry.difficulty, entry.count) as? Puzzle.Math
}

private fun graceState(
    state: SessionState,
    session: SessionData,
    now: TimeSnapshot,
): GraceState? {
    val graceEnd = session.graceEnd
    return when {
        state is SessionState.Grace && graceEnd != null -> {
            val left = graceEnd.remaining(session.pausedAt ?: now).inWholeMilliseconds
            val seconds = ((left + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
            // A deadline that passed (for example while the process was dead, Story 3.4) shows the expired state at once;
            // GraceElapsed then makes the alarm loud.
            val config = session.config
            if (seconds > 0) GraceState.Running(seconds, config.graceSeconds, vibrate = config.vibrateInGrace) else GraceState.Expired
        }

        session.noGraceThisRing -> {
            null
        }

        else -> {
            GraceState.Expired
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L

private fun CoreMathOperator.toUi(): MathOperator =
    when (this) {
        CoreMathOperator.Plus -> MathOperator.Plus
        CoreMathOperator.Times -> MathOperator.Times
        CoreMathOperator.Minus -> MathOperator.Minus
    }
