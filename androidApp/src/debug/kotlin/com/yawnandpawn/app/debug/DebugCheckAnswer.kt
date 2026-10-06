package com.yawnandpawn.app.debug

import com.yawnandpawn.app.core.checks.Puzzle
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionState
import org.koin.core.context.GlobalContext

/**
 * Debug builds only (Story 3.2): the answer to the Math problem the wake screen shows, read from `SessionEngine.state`,
 * so the device test can solve the check like a user. Lives in the debug source set under `com.yawnandpawn.app.debug`,
 * so `checkReleaseContent` keeps it out of release builds; the app never knows an answer outside the validator.
 */
object DebugCheckAnswer {
    /** The digits of the current Math item in Grace or Loud; null otherwise or without a Koin graph. */
    fun current(): String? {
        val state =
            GlobalContext
                .getOrNull()
                ?.getOrNull<SessionEngine>()
                ?.state
                ?.value
        val session = (state as? SessionState.Grace)?.session ?: (state as? SessionState.Loud)?.session
        val run = session?.checkRun?.usable(session.sessionId, session.ringIndex)
        val entry = run?.currentEntry
        val seed = run?.seeds?.getOrNull(run.step.entry)
        val puzzle = if (entry == null || seed == null) null else entry.type.generate(seed, entry.difficulty, entry.count) as? Puzzle.Math
        return run?.let {
            puzzle
                ?.problems
                ?.getOrNull(it.step.item)
                ?.answer
                ?.toString()
        }
    }
}
