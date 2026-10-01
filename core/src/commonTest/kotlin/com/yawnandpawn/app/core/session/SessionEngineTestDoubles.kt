package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.BootCounter
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

// Local doubles for SessionEngineTest (core cannot depend on :testing, AD-1).

/** The time ports over one moment that starts at [T0] and moves only when told; [stepPerRead] advances each elapsed read. */
internal class EngineTime(
    start: TimeSnapshot = T0,
) {
    var now: TimeSnapshot = start
        private set

    /** Milliseconds added to wall and elapsed time after every elapsed read, so every snapshot differs. */
    var stepPerRead: Long = 0

    val clock: Clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(now.wallMillis)
        }

    val monotonicClock = MonotonicClock { now.elapsedMillis.also { now = now.plus(stepPerRead.milliseconds) } }

    val bootCounter = BootCounter { now.bootCount }

    fun advanceBy(duration: Duration) {
        now = now.plus(duration)
    }
}

/** An [ActiveSessionStore] holding one JSON row in memory, encoded with [SessionJson] like the Room store. */
internal class InMemorySessionStore : ActiveSessionStore {
    /** The stored row; null when nothing is stored. */
    var row: String? = null

    var commitFailure: DomainError? = null
    var loadFailure: DomainError? = null
    var clearFailure: DomainError? = null

    /** Runs inside each successful commit, after the row is written. */
    var onCommit: suspend (SessionState) -> Unit = {}

    val commits = mutableListOf<SessionState>()
    var clears = 0

    val stored: SessionState?
        get() = row?.let { (SessionJson.decode(it) as StoredSession.Found).state }

    override suspend fun load(): Outcome<StoredSession, DomainError> {
        loadFailure?.let { return Outcome.Failure(it) }
        return Outcome.Success(row?.let { SessionJson.decode(it) } ?: StoredSession.Empty)
    }

    override suspend fun commit(state: SessionState): Outcome<Unit, DomainError> {
        commitFailure?.let { return Outcome.Failure(it) }
        row = if (state == SessionState.Idle) null else SessionJson.encode(state)
        commits += state
        onCommit(state)
        return Outcome.Success(Unit)
    }

    override suspend fun clear(): Outcome<Unit, DomainError> {
        clears++
        clearFailure?.let { return Outcome.Failure(it) }
        row = null
        return Outcome.Success(Unit)
    }
}

/** An [EffectRunner] that records every effect (one-shot and entry, in order) and can fail or hang on one. */
internal class RecordingRunner : EffectRunner {
    val ran = mutableListOf<Any>()

    /** Throws for the effects it matches, after recording them. */
    var throwOn: (Any) -> Boolean = { false }

    /** What a matched effect throws. */
    var failure: () -> Throwable = { IllegalStateException("effect failed") }

    /** Suspends before recording the effects it matches until [release] completes (never, for a process killed mid-effect). */
    var hangOn: (Any) -> Boolean = { false }

    val release = CompletableDeferred<Unit>()

    val oneShot: List<SessionEffect>
        get() = ran.filterIsInstance<SessionEffect>()

    val entry: List<EntryEffect>
        get() = ran.filterIsInstance<EntryEffect>()

    override suspend fun run(effect: SessionEffect) = record(effect)

    override suspend fun apply(effect: EntryEffect) = record(effect)

    private suspend fun record(effect: Any) {
        if (hangOn(effect)) release.await()
        ran += effect
        if (throwOn(effect)) throw failure()
    }
}

internal class EngineLogger : Logger {
    val events = mutableListOf<LogEvent>()

    override fun log(event: LogEvent) {
        events += event
    }
}
