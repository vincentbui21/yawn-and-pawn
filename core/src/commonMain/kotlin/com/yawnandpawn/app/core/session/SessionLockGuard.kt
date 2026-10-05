package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A ring (Ringing, Grace, Loud) or a snooze: the session the user is in the middle of (Story 2.6). Completed and Missed
 * only wait for their history row, so they are not in progress for the app.
 */
val SessionState.inProgress: Boolean
    get() = this is SessionState.Ring || this is SessionState.Snoozed

/**
 * The session lock behind the UI (FR-SES-3, AD-11, Story 2.6): while a wake session is in progress, nothing the user
 * owns may change, so a stray call can never edit, disable or delete an alarm mid-session. The app shows only "Alarm in
 * progress" then ([locked]); this is the defence in depth behind that screen.
 *
 * Locked ([isLocked]) means any of:
 * - the stored session is not restored yet, or its load failed ([restored] false): [state] is Idle only because nothing
 *   is loaded, and the store may hold a ring;
 * - a ring or snooze is in progress ([inProgress]);
 * - the emergency ring plays ([emergency]; the session could not start, so [state] may be Idle).
 *
 * Completed and Missed are not locked: the morning is over and only the history row is pending, which may keep failing.
 *
 * [state] and [restored] are `SessionEngine`'s, [emergency] the wake runtime's; the app wires them.
 *
 * **Every mutating use case must run its writes inside [whenIdle]:** the alarm use cases today, and the later ones too
 * (settings, the base fee, "Delete all data"). `SessionLockGuardScanTest` fails for a use case in the scanned packages
 * that writes without it. The alarm-fire path (`RearmOnFire`) is the one writer that keeps working during a session: it
 * re-arms repeating alarms and switches fired one-time alarms off.
 */
class SessionLockGuard(
    val state: StateFlow<SessionState>,
    val restored: StateFlow<Boolean>,
    val emergency: StateFlow<Boolean>,
) {
    /** Serializes [whenIdle] with [startingSession], so a session never starts in the middle of a guarded write. */
    private val mutex = Mutex()

    /** True while the app is session-locked (see the class). [restored] is read before [state], which it follows. */
    val isLocked: Boolean
        get() = !restored.value || emergency.value || state.value.inProgress

    /** [isLocked], emitted on every change (the app's navigation follows it). */
    val locked: Flow<Boolean>
        get() = merge(restored, state, emergency).map { isLocked }.distinctUntilChanged()

    /**
     * Runs [block] when the app is not locked, else returns [DomainError.SessionActive] without running it. Call it
     * inside the use case's write lock, before any read or write. No session starts while [block] runs
     * ([startingSession] waits for it), so the check holds for the whole write.
     */
    suspend fun <T> whenIdle(block: suspend () -> Outcome<T, DomainError>): Outcome<T, DomainError> =
        mutex.withLock { if (isLocked) Outcome.Failure(DomainError.SessionActive) else block() }

    /**
     * Runs [block], which may start a session (the wake service's `AlarmFired` and `TestAlarmFired` dispatch), after any
     * guarded write in flight, and holds the next one until it is done. A guarded write takes milliseconds, so the ring
     * is never noticeably late. [block] must not call a guarded use case.
     */
    suspend fun <T> startingSession(block: suspend () -> T): T = mutex.withLock { block() }
}
