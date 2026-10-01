package com.yawnandpawn.app.core.session

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
     * Stores [state] in one transaction, replacing whatever was stored. [SessionState.Idle] deletes the row. When this
     * fails nothing changed.
     */
    suspend fun commit(state: SessionState): Outcome<Unit, DomainError>

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
 * The `PurchaseIntent` persisted before billing launches (AD-7). Epic 4 adds the price (micros and currency); Epic 1
 * has only what the reducer knows.
 */
data class PurchaseIntent(
    val intentId: PurchaseIntentId,
    val sessionId: String,
    val productId: String,
    val snoozeNumber: Int,
)

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
}

/** Port for the persisted purchase intents (AD-7, `runtime.db` in Epic 4). */
interface PurchaseIntentStore {
    /** Stores [intent], replacing one with the same id. */
    suspend fun save(intent: PurchaseIntent): Outcome<Unit, DomainError>

    /** The intent [intentId], or `NotFound`. */
    suspend fun get(intentId: PurchaseIntentId): Outcome<PurchaseIntent, DomainError>
}
