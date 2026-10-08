package com.yawnandpawn.app

import com.yawnandpawn.app.android.wake.WakeRuntime
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import kotlinx.coroutines.runBlocking

/**
 * Waits (bounded) until the app is idle and unlocked, so a device test can save an alarm whatever ran before it (Story
 * 3.12, CI on PR #40: the first device test of the run saved before anything had restored the engine, and the session
 * lock refused with `SessionActive`). It restores the engine (only an activity or the wake service does at app start,
 * and a test may run before either), ends a session left ringing ([endRingForCleanup]), stops an emergency ring, and
 * waits for Idle (a completed session first writes its history row). Fails with the state it found otherwise.
 */
internal fun awaitAppIdle(
    engine: SessionEngine,
    guard: SessionLockGuard,
    runtime: WakeRuntime,
    what: String,
) {
    runBlocking { engine.restore() }
    repeat((IDLE_TIMEOUT_MILLIS / IDLE_POLL_MILLIS).toInt()) {
        if (engine.state.value is SessionState.Ring) engine.endRingForCleanup()
        if (runtime.emergency.value != null) runtime.stopEmergency()
        if (engine.state.value == SessionState.Idle && !guard.isLocked) return
        Thread.sleep(IDLE_POLL_MILLIS)
    }
    throw AssertionError(
        "$what: the app is not idle after ${IDLE_TIMEOUT_MILLIS / MILLIS_PER_SECOND} s " +
            "(state ${engine.state.value}, restored ${engine.restored.value}, emergency ${runtime.emergency.value != null})",
    )
}

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
private const val IDLE_TIMEOUT_MILLIS = 60_000L
private const val IDLE_POLL_MILLIS = 100L
private const val MILLIS_PER_SECOND = 1_000L
