package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.session.SessionState

/**
 * The Memory Sequence round a check waits on (Story 3.8): [round] of [roundCount], its [sequence], the [tapsMade] in it
 * so far (the engine's item within the round) and the grid. The screen plays [sequence] from the start whenever a round
 * begins, also after a restore in the middle of one; input then goes on from the next tap the engine waits for.
 */
data class MemoryRound(
    val round: Int,
    val roundCount: Int,
    val sequence: List<Int>,
    val tapsMade: Int,
    val gridSize: Int,
    val numbered: Boolean,
)

/**
 * The round of [puzzle] (a Memory puzzle of [type]) that holds item [item], or null when [puzzle] is not one or [item]
 * is past its end. Pure.
 */
fun memoryRound(
    type: CheckType.MemorySequence,
    puzzle: Puzzle,
    item: Int,
): MemoryRound? {
    val memory = puzzle as? Puzzle.Memory
    val length = memory?.rounds?.firstOrNull()?.size ?: 0
    val sequence = if (length > 0 && item >= 0) memory?.rounds?.getOrNull(item / length) else null
    return if (memory == null || sequence == null) {
        null
    } else {
        MemoryRound(item / length + 1, memory.rounds.size, sequence, item % length, memory.gridSize, type.numbered)
    }
}

/**
 * The Memory round [state] waits on: Grace or Loud with a Memory Sequence entry current. Its puzzle comes from the
 * entry's seed through the core plugin, so a restored session shows the same round. It is read from the
 * [usable run][com.yawnandpawn.app.core.session.CheckRun.usable], the one the engine checks taps on.
 */
fun memoryRound(state: SessionState): MemoryRound? {
    val run = ((state as? SessionState.Grace)?.session ?: (state as? SessionState.Loud)?.session)?.usableRun()
    val entry = run?.currentEntry
    val type = entry?.type as? CheckType.MemorySequence
    val seed = run?.seeds?.getOrNull(run.step.entry)
    return if (entry == null || type == null || seed == null) {
        null
    } else {
        memoryRound(type, entry.puzzle(seed), run.step.item)
    }
}

/**
 * The Memory Sequence round on the wake screen (Story 3.8), UI only: the engine's [position] and [round], its
 * [playback], and [wrong] after a wrong tap until the new sequence has played. [following] the engine's next position
 * keeps the playback within a round (a right tap moves the item on), plays a round from the start when a new one begins
 * (also after a restore), and plays the new sequence with "Not quite. Try again." when the failed attempts went up (a
 * wrong tap restarts the round with a new seed). The UI never decides correctness.
 */
data class MemoryInput(
    val position: CheckPosition? = null,
    val round: MemoryRound? = null,
    val playback: MemoryPlayback? = null,
    val wrong: Boolean = false,
) {
    /** This input at the engine's [next] position, waiting on [nextRound]. */
    fun following(
        next: CheckPosition?,
        nextRound: MemoryRound?,
    ): MemoryInput {
        val current = position
        val sameEntry = next.isSameEntryAs(current)
        return when {
            next == null || nextRound == null -> {
                MemoryInput(next)
            }

            sameEntry && next.failedAttempts > (current?.failedAttempts ?: 0) -> {
                MemoryInput(next, nextRound, MemoryPlayback(nextRound.sequence), wrong = true)
            }

            sameEntry && next.seed == current?.seed && nextRound.round == round?.round -> {
                copy(position = next, round = nextRound)
            }

            else -> {
                MemoryInput(next, nextRound, MemoryPlayback(nextRound.sequence))
            }
        }
    }

    /** The playback's next step; "Not quite. Try again." goes once the new sequence has played. */
    fun ticked(): MemoryInput {
        val next = playback?.tick() ?: return this
        return copy(playback = next, wrong = wrong && next.playing)
    }

    /** [tile] tapped: it lights briefly, or null when taps are ignored (the sequence plays, or no round). */
    fun tapped(tile: Int): MemoryInput? = playback?.takeUnless { it.playing }?.let { copy(playback = it.tapped(tile)) }
}

/**
 * What the Memory check shows for [round] as [playback] plays it, with the wrong-tap message while [wrong] and the
 * entry's [wrongAttempts] so far. Pure.
 */
fun memoryCheckContent(
    round: MemoryRound,
    playback: MemoryPlayback,
    wrong: Boolean,
    wrongAttempts: Int = 0,
): CheckContent.MemorySequence =
    CheckContent.MemorySequence(
        round = round.round,
        roundCount = round.roundCount,
        phase = playback.phase,
        litTile = playback.litTile,
        numbered = round.numbered,
        wrong = wrong,
        gridSize = round.gridSize,
        announced = round.sequence.takeIf { round.numbered },
        wrongAttempts = wrongAttempts,
    )
