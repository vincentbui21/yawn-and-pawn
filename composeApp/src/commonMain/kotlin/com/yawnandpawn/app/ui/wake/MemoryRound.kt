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
 * entry's seed through the core plugin, so a restored session shows the same round. For 3.2's wake renderer.
 */
fun memoryRound(state: SessionState): MemoryRound? {
    val run = ((state as? SessionState.Grace)?.session ?: (state as? SessionState.Loud)?.session)?.checkRun
    val entry = run?.currentEntry
    val type = entry?.type as? CheckType.MemorySequence
    val seed = run?.seeds?.getOrNull(run.step.entry)
    return if (entry == null || type == null ||
        seed == null
    ) {
        null
    } else {
        memoryRound(type, type.generate(seed, entry.difficulty, entry.count), run.step.item)
    }
}

/** What the Memory check shows for [round] as [playback] plays it, with the wrong-tap message while [wrong]. Pure. */
fun memoryCheckContent(
    round: MemoryRound,
    playback: MemoryPlayback,
    wrong: Boolean,
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
    )
