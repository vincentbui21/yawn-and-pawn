package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StepPointer
import com.yawnandpawn.app.testing.aSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Story 3.8: the Memory round on screen, its playback timing and the content the approved composable shows. */
class MemoryRoundTest {
    private val memory = CheckType.MemorySequence()

    @Test
    fun `playback lights each tile 350 ms with 150 ms gaps, then it is the user's turn`() {
        var playback = MemoryPlayback(listOf(3, 7))
        val frames = mutableListOf<Pair<Int?, Duration?>>()
        while (playback.playing) {
            assertEquals(MemoryPhase.Watch, playback.phase)
            frames += playback.litTile to playback.nextTick
            playback = playback.tick()
        }

        val expected: List<Pair<Int?, Duration?>> =
            listOf(
                3 to 350.milliseconds,
                null to 150.milliseconds,
                7 to 350.milliseconds,
                null to 150.milliseconds,
            )
        assertEquals(expected, frames)
        assertEquals(MemoryPhase.YourTurn, playback.phase)
        assertNull(playback.nextTick, "waits for a tap")
        val tapped = playback.tapped(5)
        assertEquals(5 to 150.milliseconds, tapped.litTile to tapped.nextTick)
        assertNull(tapped.tick().litTile, "the tap light goes off")
    }

    @Test
    fun `the round of an item, with the taps already made in it`() {
        val puzzle = memory.generate(9L, Difficulty.Medium, 3) as Puzzle.Memory

        assertEquals(MemoryRound(1, 3, puzzle.rounds[0], 0, 3, false), memoryRound(memory, puzzle, 0))
        assertEquals(MemoryRound(2, 3, puzzle.rounds[1], 2, 3, false), memoryRound(memory, puzzle, 8))
        assertNull(memoryRound(memory, puzzle, 18), "past the end")
        assertNull(memoryRound(memory, puzzle, -1))
        assertNull(memoryRound(memory, Puzzle.Placeholder, 0))
    }

    @Test
    fun `a session in Grace or Loud on a Memory entry gives its round from the entry's seed, any other state none`() {
        val plan = CheckPlan(CheckMode.All, listOf(CheckEntry(memory, Difficulty.Hard, 2)))
        val session = aSession().copy(checkRun = CheckRun(plan, listOf(77L), step = StepPointer(0, 9)))
        val puzzle = memory.generate(77L, Difficulty.Hard, 2) as Puzzle.Memory

        val round = memoryRound(SessionState.Loud(session))
        assertEquals(MemoryRound(2, 2, puzzle.rounds[1], 1, 4, false), round)
        assertEquals(round, memoryRound(SessionState.Grace(session)))
        assertNull(memoryRound(SessionState.Ringing(session)))
        assertNull(memoryRound(SessionState.Loud(aSession())), "a Math entry")
        assertNull(memoryRound(SessionState.Loud(session.copy(checkRun = CheckRun(plan, emptyList())))), "no seed")
    }

    @Test
    fun `the content follows the playback, and the numbered variant announces its round`() {
        val round = MemoryRound(1, 2, listOf(3, 7, 1, 9), 0, 3, numbered = true)

        val watching = memoryCheckContent(round, MemoryPlayback(round.sequence), wrong = true)
        assertEquals(
            CheckContent.MemorySequence(
                1,
                2,
                MemoryPhase.Watch,
                litTile = 3,
                numbered = true,
                wrong = true,
                gridSize = 3,
                announced = listOf(3, 7, 1, 9),
            ),
            watching,
        )
        val plain = memoryCheckContent(round.copy(numbered = false, gridSize = 4), MemoryPlayback(round.sequence, frame = 8), wrong = false)
        assertEquals(MemoryPhase.YourTurn, plain.phase)
        assertNull(plain.announced)
        assertEquals(4, plain.gridSize)
    }
}
