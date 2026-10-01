package com.yawnandpawn.app.core.history

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Port for the sessions whose Home missed note the user dismissed (Story 1.16), kept per session id in device-protected
 * DataStore by `:data`. A read failure emits an empty set (the note shows again rather than never).
 */
interface MissedNoteDismissals {
    /** The dismissed session ids; emits again after every change. */
    fun dismissed(): Flow<Set<String>>

    /** Remembers that the note of [sessionId] was dismissed. */
    suspend fun dismiss(sessionId: String): Outcome<Unit, DomainError>
}

/**
 * Home's missed note (FR-ALM-9, UX-DR80): "Your {time} alarm stopped after 30 minutes. Logged as missed." for the
 * Missed session that ended last, until the user dismisses it. A newer Missed session shows again even when an older
 * one was dismissed. Reads only: `SessionRecorder` stays the only history writer.
 */
class MissedNotes(
    private val history: SessionHistoryRepository,
    private val dismissals: MissedNoteDismissals,
) {
    /** The Missed session to tell the user about, or null. A failing read throws into the flow. */
    fun current(): Flow<SessionHistoryRow?> =
        combine(history.observeLatestMissed(), dismissals.dismissed()) { latest, dismissed ->
            latest?.takeIf { it.sessionId !in dismissed }
        }

    /** The user dismissed the note of [sessionId]. */
    suspend fun dismiss(sessionId: String): Outcome<Unit, DomainError> = dismissals.dismiss(sessionId)
}
