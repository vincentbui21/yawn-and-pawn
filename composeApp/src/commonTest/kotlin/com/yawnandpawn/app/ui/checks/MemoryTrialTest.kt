package com.yawnandpawn.app.ui.checks

import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.MemoryPhase
import com.yawnandpawn.app.ui.wake.WakeIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType

/** Story 3.8: the Memory Sequence "Try it" plays one round, then checks each tap through the core plugin. */
class MemoryTrialTest {
    private val seed = 11L

    private fun sequence(
        difficulty: Difficulty,
        numbered: Boolean = false,
    ): List<Int> = (CoreCheckType.MemorySequence(numbered).generate(seed, difficulty.toCore(), 1) as Puzzle.Memory).rounds.single()

    private fun CheckTrial.memory(): CheckContent.MemorySequence = state.content as CheckContent.MemorySequence

    /** Ticks until the user's turn. */
    private fun CheckTrial.played(): CheckTrial {
        var trial = this
        while (trial.memory().phase == MemoryPhase.Watch) trial = trial.tick()
        return trial
    }

    @Test
    fun `it plays the round of the difficulty set, one round, and ignores taps while it plays`() {
        val trial = CheckRegistry.startTrial(CheckType.MemorySequence, Difficulty.Hard, seed)!!

        assertEquals(1 to 1, trial.memory().round to trial.memory().roundCount)
        assertEquals(4, trial.memory().gridSize, "Hard: 4x4")
        assertEquals(MemoryPhase.Watch, trial.memory().phase)
        assertEquals(sequence(Difficulty.Hard).first(), trial.memory().litTile)
        assertSame(trial, trial.onIntent(WakeIntent.TileTapped(sequence(Difficulty.Hard).first())), "input is disabled")
        assertEquals(MemoryPhase.YourTurn, trial.played().memory().phase)
    }

    @Test
    fun `right taps light briefly and the last one solves it`() {
        val tiles = sequence(Difficulty.Easy)
        var trial = MemoryTrial.start(Difficulty.Easy, seed).played()

        tiles.dropLast(1).forEach { tile ->
            trial = trial.onIntent(WakeIntent.TileTapped(tile))
            assertEquals(tile, trial.memory().litTile)
            assertFalse(trial.state.done)
        }
        trial = trial.onIntent(WakeIntent.TileTapped(tiles.last()))

        assertTrue(trial.state.done)
        assertNull(trial.nextTick, "nothing moves once solved")
        assertSame(trial, trial.onIntent(WakeIntent.TileTapped(1)))
    }

    @Test
    fun `a wrong tap shows the wrong state while a new sequence plays, then it is the user's turn again`() {
        val tiles = sequence(Difficulty.Medium)
        val wrongTile = (1..9).first { it != tiles[1] }
        val trial = MemoryTrial.start(Difficulty.Medium, seed).played().onIntent(WakeIntent.TileTapped(tiles[0]))

        val wrong = trial.onIntent(WakeIntent.TileTapped(wrongTile))

        assertTrue(wrong.memory().wrong)
        assertEquals(MemoryPhase.Watch, wrong.memory().phase, "a new sequence plays")
        val again = wrong.played()
        assertFalse(again.memory().wrong, "the message goes once it is the user's turn")
        assertEquals(MemoryPhase.YourTurn, again.memory().phase)
        assertSame(again, again.onIntent(WakeIntent.DigitTapped(1)), "taps Memory does not use")
    }

    @Test
    fun `with TalkBack on it is the numbered 3x3 that announces its round`() {
        val trial = CheckRegistry.startTrial(CheckType.MemorySequence, Difficulty.Hard, seed, accessible = true)!!

        assertTrue(trial.memory().numbered)
        assertEquals(3, trial.memory().gridSize)
        assertEquals(sequence(Difficulty.Hard, numbered = true), trial.memory().announced)
        assertNull(CheckRegistry.startTrial(CheckType.Math, Difficulty.Hard, seed, accessible = true)!!.nextTick, "Math waits for taps")
    }
}
