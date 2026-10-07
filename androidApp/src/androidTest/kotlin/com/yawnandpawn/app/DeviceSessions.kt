package com.yawnandpawn.app

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import kotlinx.coroutines.runBlocking

/**
 * Story 3.12 review: the cleanup of a device test that failed mid-ring, so the managed device is not left ringing for
 * the next test. While the session still rings it sends what a user would ("I'm up", then each right answer, read from
 * the puzzle as the debug-only `DebugCheckAnswer` does) until the session ends; it returns whether it did.
 */
internal fun SessionEngine.endRingForCleanup(): Boolean {
    var events = 0
    var next = nextCleanupEvent()
    while (next != null && events < MAX_EVENTS) {
        val event = next
        runBlocking { dispatch(event) }
        events++
        next = nextCleanupEvent()
    }
    return state.value !is SessionState.Ring
}

/** What a user would send next while the session rings: "I'm up", then the right answer; null when nothing is left. */
private fun SessionEngine.nextCleanupEvent(): SessionEvent? {
    val ring = state.value as? SessionState.Ring ?: return null
    return if (ring is SessionState.Ringing) SessionEvent.ImUpTapped else rightAnswer(ring.session)?.let(SessionEvent::CheckAnswerSubmitted)
}

/** The right answer to the item the engine waits on, from its seed; null when there is none to give. */
private fun rightAnswer(session: SessionData): CheckAnswer? {
    val run = session.checkRun.usable(session.sessionId, session.ringIndex)
    val entry = run.currentEntry
    val seed = run.seeds.getOrNull(run.step.entry)
    if (entry == null || seed == null) return null
    return when (val puzzle = entry.puzzle(seed)) {
        is Puzzle.Math -> puzzle.problems.getOrNull(run.step.item)?.let { CheckAnswer.Number(it.answer.toString()) }
        is Puzzle.Memory -> puzzle.taps.getOrNull(run.step.item)?.let(CheckAnswer::Tile)
        is Puzzle.Word -> puzzle.words.getOrNull(run.step.item)?.let(CheckAnswer::Word)
        is Puzzle.Code -> puzzle.code?.let(CheckAnswer::Code)
        Puzzle.Placeholder -> CheckAnswer.Placeholder
    }
}

private const val MAX_EVENTS = 100
