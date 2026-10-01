package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlin.time.Instant

/**
 * [SessionEngine]'s side of session history (AD-18): it hands the two history effects to the [SessionRecorder] and
 * remembers which session's end row is written, so the engine can reduce `Recorded` for it. Only the engine uses it,
 * inside its Mutex. Failures are logged and never thrown; a failed end write stays [pending] and is tried again.
 */
internal class EngineHistory(
    private val recorder: SessionRecorder,
    private val logger: Logger,
) {
    /** The session whose end row is written; it is then reduced with `Recorded`, never written again. */
    private var written: String? = null

    /**
     * `RecordSessionStart` [effect]: the start row of the session the starting transition committed in [state]. Dropped
     * (and logged) when [state] does not hold that session.
     */
    suspend fun recordStart(
        effect: SessionEffect.RecordSessionStart,
        state: SessionState,
    ) {
        val session = (state as? SessionState.Active)?.session?.takeIf { it.sessionId == effect.sessionId }
        if (session == null) {
            logger.log(LogEvent.OperationFailed(RECORD_START, "session ${effect.sessionId} is not the committed session"))
            return
        }
        val result = recorder.recordStart(session)
        if (result is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(RECORD_START, result.error))
    }

    /** `HistoryWriteRequested`: the end row of a Completed or Missed [state] at [now], unless it is already written. */
    suspend fun recordEnd(
        state: SessionState,
        now: TimeSnapshot,
    ) {
        if (!pending(state)) return
        val session = state.endedSession() ?: return
        val end = if (state is SessionState.Completed) SessionEnd.Completed else SessionEnd.Missed
        val result = recorder.recordEnd(session, end, Instant.fromEpochMilliseconds(now.wallMillis))
        if (result is Outcome.Success) written = session.sessionId
        if (result is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(RECORD_END, result.error))
    }

    /**
     * A new alarm fired while the ended [state]'s end row still cannot be written: give up on it (logged), so `Recorded`
     * follows and the new alarm can ring (NFR-2). Does nothing when the row is written or [state] did not end.
     */
    fun abandon(state: SessionState) {
        val session = state.endedSession()?.takeIf { pending(state) } ?: return
        logger.log(LogEvent.OperationFailed(RECORD_END, "abandoned for a new alarm"))
        written = session.sessionId
    }

    /** True when [state] ended and its end row is not written yet. */
    fun pending(state: SessionState): Boolean = state.endedSession()?.let { it.sessionId != written } ?: false

    /** True when [state] is Completed or Missed. */
    fun ended(state: SessionState): Boolean = state.endedSession() != null

    /** `Recorded` for an ended [state] whose end row is written; otherwise nothing. */
    fun followUp(state: SessionState): List<SessionEvent> =
        state
            .endedSession()
            ?.takeIf { it.sessionId == written }
            ?.let { listOf(SessionEvent.Recorded(it.sessionId)) }
            .orEmpty()

    private companion object {
        const val RECORD_START = "record session start"
        const val RECORD_END = "record session end"
    }
}

/** The session of a Completed or Missed state, which waits for its history row; null for every other state. */
private fun SessionState.endedSession(): SessionData? =
    when (this) {
        is SessionState.Completed -> session
        is SessionState.Missed -> session
        else -> null
    }
