package com.yawnandpawn.app.ui.checks

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckResult
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.ui.checksetup.CheckPreviewUiState
import com.yawnandpawn.app.ui.wake.MemoryPlayback
import com.yawnandpawn.app.ui.wake.MemoryRound
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.ui.wake.memoryCheckContent
import com.yawnandpawn.app.ui.wake.memoryRound
import kotlin.time.Duration
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType

/**
 * A Memory Sequence "Try it" (Story 3.8): one round from the core generator, played as the real check plays it
 * ([MemoryPlayback]: taps ignored while it plays). Each tap is checked by the core plugin; a wrong one shows the same
 * feedback as the real check and plays a new sequence (the next seed, derived here from the ViewModel's seed); the last
 * right tap shows "Nice. That's how it works.".
 */
class MemoryTrial private constructor(
    private val type: CoreCheckType.MemorySequence,
    private val difficulty: Difficulty,
    private val seed: Long,
    private val item: Int,
    private val playback: MemoryPlayback,
    private val wrong: Boolean,
    private val done: Boolean,
) : CheckTrial {
    private val puzzle: Puzzle = type.generate(seed, difficulty.toCore(), count = 1)

    /** The round on screen; [item] is always inside the puzzle (the solved trial stays on its last tap). */
    private val round: MemoryRound
        get() = checkNotNull(memoryRound(type, puzzle, item.coerceIn(0, puzzle.size - 1))) { "a Memory puzzle has a round" }

    override val state: CheckPreviewUiState
        get() = CheckPreviewUiState(content = memoryCheckContent(round, playback, wrong), done = done)

    override val nextTick: Duration?
        get() = if (done) null else playback.nextTick

    override fun tick(): CheckTrial {
        val next = playback.tick()
        // The new sequence has played: "Not quite. Try again." goes once it is the user's turn again.
        return copy(playback = next, wrong = wrong && next.playing)
    }

    override fun onIntent(intent: WakeIntent): CheckTrial {
        val tile = (intent as? WakeIntent.TileTapped)?.tile
        return if (tile == null || done || playback.playing) this else tapped(tile)
    }

    private fun tapped(tile: Int): MemoryTrial =
        when (type.validate(puzzle, item, CheckAnswer.Tile(tile))) {
            CheckResult.ItemCorrect -> copy(item = item + 1, playback = playback.tapped(tile), wrong = false)
            CheckResult.Correct -> copy(done = true, playback = playback.tapped(tile), wrong = false)
            CheckResult.WrongRestart -> restarted()
            CheckResult.Wrong -> this
        }

    /** A wrong tap: the same round again with a new sequence, from the start. */
    private fun restarted(): MemoryTrial {
        val nextSeed = seed * SEED_MULTIPLIER + SEED_INCREMENT
        val restartAt = type.restartFrom(item, difficulty.toCore())
        val sequence = memoryRound(type, type.generate(nextSeed, difficulty.toCore(), count = 1), restartAt)?.sequence.orEmpty()
        return MemoryTrial(type, difficulty, nextSeed, restartAt, MemoryPlayback(sequence), wrong = true, done = false)
    }

    private fun copy(
        item: Int = this.item,
        playback: MemoryPlayback = this.playback,
        wrong: Boolean = this.wrong,
        done: Boolean = this.done,
    ) = MemoryTrial(type, difficulty, seed, item, playback, wrong, done)

    companion object {
        /** A trial of one round at [difficulty] from [seed]; [numbered]: the TalkBack variant (always 3×3). */
        fun start(
            difficulty: Difficulty,
            seed: Long,
            numbered: Boolean = false,
        ): MemoryTrial {
            val type = CoreCheckType.MemorySequence(numbered)
            val sequence = memoryRound(type, type.generate(seed, difficulty.toCore(), count = 1), 0)?.sequence.orEmpty()
            return MemoryTrial(type, difficulty, seed, 0, MemoryPlayback(sequence), wrong = false, done = false)
        }

        /** A 64-bit LCG step (Knuth's MMIX constants): the next seed after a wrong tap, deterministic per first seed. */
        private const val SEED_MULTIPLIER = 6364136223846793005L
        private const val SEED_INCREMENT = 1442695040888963407L
    }
}
