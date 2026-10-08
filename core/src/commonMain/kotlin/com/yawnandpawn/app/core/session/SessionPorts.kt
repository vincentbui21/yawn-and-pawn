package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome

/** What the store holds for the active session (AD-2), as read by [ActiveSessionStore.load]. */
sealed interface StoredSession {
    /** Nothing is stored: no session was active. */
    data object Empty : StoredSession

    /** The last committed [state]. */
    data class Found(
        val state: SessionState,
    ) : StoredSession

    /**
     * A row exists but [SessionJson] cannot decode it. [cause] is diagnostic text for the log only (an exception type,
     * never the stored JSON, which holds the alarm label).
     */
    data class Unreadable(
        val cause: String,
    ) : StoredSession
}

/**
 * Port for the write-ahead copy of the session (AD-2 rule 2, `runtime.db` in `:data`). It holds at most one session.
 * Only [SessionEngine] uses it, and it encodes states only with [SessionJson].
 */
interface ActiveSessionStore {
    /** The stored session, [StoredSession.Empty] when there is none. Failure only for a storage error. */
    suspend fun load(): Outcome<StoredSession, DomainError>

    /**
     * Stores [state] and applies [writes] in one transaction, replacing whatever session was stored.
     * [SessionState.Idle] deletes the row. When this fails nothing changed: no state and none of the writes.
     */
    suspend fun commit(
        state: SessionState,
        writes: List<RuntimeWrite> = emptyList(),
    ): Outcome<Unit, DomainError>

    /** Deletes the stored session, readable or not. */
    suspend fun clear(): Outcome<Unit, DomainError>
}

/**
 * Port that carries out what the session wants (AD-2 rule 4): the wake runtime in Story 1.14, a logging stand-in in
 * Epic 1. It never receives the session history effects (`RecordSessionStart`, `HistoryWriteRequested`): the engine
 * writes history through its `SessionRecorder` itself. [SessionEngine] calls it only after the transition is
 * committed, inside its Mutex, so calls never interleave. The Mutex is not reentrant: a runner must never call
 * `SessionEngine.dispatch` from inside these functions and wait for it; results go back as events dispatched from
 * outside (for example launched on a scope).
 *
 * On restore the runner gets the entry effects of the restored state, never the one-shot effects of the restore
 * transition or of anything committed before the crash. Timer events found due right after a restore (a grace window
 * that ended while the process was dead) are new transitions, and their one-shot effects do reach [run].
 */
interface EffectRunner {
    /** Runs the one-shot [effect] of a transition, once. */
    suspend fun run(effect: SessionEffect)

    /** Applies the idempotent entry [effect] of the current state. */
    suspend fun apply(effect: EntryEffect)
}

/**
 * A row `SessionEngine` writes in the same `runtime.db` transaction as a transition (AD-2 rule 2, AD-7). Story 4.10
 * adds the grant ledger.
 */
sealed interface RuntimeWrite {
    /** Insert the [intent] of a `PayConfirmed` (Story 4.8). An intent id is written once: a second write fails the commit. */
    data class PutPurchaseIntent(
        val intent: PurchaseIntent,
    ) : RuntimeWrite
}

/**
 * Port for Play Billing (AD-7). The real adapter arrives in Epic 4; Epic 1 binds one that always fails.
 *
 * [launch] waits for the user and Play, so it must never be awaited inside an [EffectRunner] call: that would hold the
 * engine's Mutex for the whole purchase and block every other event. The runner starts it outside the effect (for
 * example launched on a scope) and the result comes back as an event dispatched from outside.
 */
fun interface Billing {
    /** Launches the purchase of [intent] and returns its result as the event to dispatch. */
    suspend fun launch(intent: PurchaseIntent): SessionEvent.PurchaseEvent

    /**
     * Connects to Play Billing after the user's first unlock (AD-15, Story 2.4); never before it. Idempotent. A no-op
     * until Epic 4 brings the real adapter.
     */
    fun init() = Unit
}

/** How a keyguard dismiss request ended (Spike S1). */
enum class UnlockResult {
    /** `onDismissSucceeded`: the phone is unlocked. */
    Succeeded,

    /** `onDismissCancelled` or `onDismissError`: still locked. */
    Failed,
    ;

    /** The event to dispatch for this result. */
    fun event(): SessionEvent.UnlockEvent =
        when (this) {
            Succeeded -> SessionEvent.UnlockSucceeded
            Failed -> SessionEvent.UnlockFailed
        }
}

/**
 * Port for the unlock step before Play opens (Spike S1 option B, owner decision 2026-10-08): the Play sheet never shows
 * over a keyguard, so "Pay" while locked asks for the PIN first. The real adapter (`requestDismissKeyguard` from the
 * resumed `WakeActivity`) arrives in Story 4.12.
 *
 * [requestUnlock] waits for the user, so like `Billing.launch` it must never be awaited inside an [EffectRunner] call;
 * the runner starts it outside and dispatches [UnlockResult.event]. A wrong PIN gives no result, so it may never return.
 */
interface UnlockPort {
    /** Whether the keyguard is showing now; `SessionEngine` reads it for `PayConfirmed` only. */
    fun isKeyguardLocked(): Boolean

    /** Asks the user to unlock (PIN, pattern or swipe) and returns how it ended. */
    suspend fun requestUnlock(): UnlockResult

    /** A phone that is never locked: the default until Story 4.12 binds the real adapter. */
    object Unlocked : UnlockPort {
        override fun isKeyguardLocked(): Boolean = false

        override suspend fun requestUnlock(): UnlockResult = UnlockResult.Succeeded
    }
}
