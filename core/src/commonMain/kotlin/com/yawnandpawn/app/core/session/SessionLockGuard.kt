package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.StateFlow

/**
 * The session lock behind the UI (FR-SES-3, AD-11, Story 2.6): while a wake session is active ([state] is not
 * [SessionState.Idle]), nothing the user owns may change, so a stray call can never edit, disable or delete an alarm
 * mid-session. The app shows only "Alarm in progress" then; this is the defence in depth behind that screen.
 *
 * [state] is `SessionEngine.state`, wired by the app.
 *
 * **Every mutating use case must run its writes inside [whenIdle]:** the alarm use cases today, and the later ones too
 * (settings, the base fee, "Delete all data"). `SessionLockGuardScanTest` fails for a `core.alarm` or `core.config` use
 * case that writes a repository without it. The alarm-fire path (`RearmOnFire`) is the one writer that keeps working
 * during a session: it re-arms repeating alarms and switches fired one-time alarms off.
 */
class SessionLockGuard(
    val state: StateFlow<SessionState>,
) {
    /** True while a session is active (any state but Idle). */
    val isLocked: Boolean
        get() = state.value != SessionState.Idle

    /**
     * Runs [block] when no session is active, else returns [DomainError.SessionActive] without running it. Call it
     * inside the use case's write lock, before any read or write.
     */
    suspend fun <T> whenIdle(block: suspend () -> Outcome<T, DomainError>): Outcome<T, DomainError> =
        if (isLocked) Outcome.Failure(DomainError.SessionActive) else block()
}
