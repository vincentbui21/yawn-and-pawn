package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.billing.GrantLedgerEntry
import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.BootCounter
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

    /** The user (or the network) sets the wall clock by [duration], forward or back; monotonic time does not move. */
    fun jumpWall(duration: Duration) {
        now = now.copy(wallMillis = now.wallMillis + duration.inWholeMilliseconds)
    }

    /**
     * The phone was off for [off] and booted again: the wall clock moved on, the elapsed clock restarted at [elapsedMillis]
     * and the boot count is [bootCount] (the same one again models a device without `BOOT_COUNT`).
     */
    fun reboot(
        off: Duration,
        elapsedMillis: Long = 20_000,
        bootCount: Int = now.bootCount + 1,
    ) {
        now = TimeSnapshot(now.wallMillis + off.inWholeMilliseconds, elapsedMillis, bootCount)
    }
}

/**
 * An [ActiveSessionStore] holding one JSON row in memory, encoded with [SessionJson] like the Room store, and the intents
 * (Story 4.8) and grant ledger rows (Story 4.10) its commits wrote: a commit is all or nothing, and an intent id or a
 * token already stored fails it, as in Room.
 */
internal class InMemorySessionStore(
    /** The grant ledger rows written by successful commits, by token (Story 4.10); a test may share it with a ledger. */
    val grants: MutableMap<PurchaseToken, GrantLedgerEntry> = linkedMapOf(),
) : ActiveSessionStore {
    /** The stored row; null when nothing is stored. */
    var row: String? = null

    var commitFailure: DomainError? = null
    var loadFailure: DomainError? = null
    var clearFailure: DomainError? = null

    /** Runs inside each successful commit, after the row is written. */
    var onCommit: suspend (SessionState) -> Unit = {}

    val commits = mutableListOf<SessionState>()
    var clears = 0

    /** The intents written by successful commits, by id. */
    val intents = linkedMapOf<PurchaseIntentId, PurchaseIntent>()

    /** The writes of each successful commit, in order (empty for a commit without any). */
    val writeLog = mutableListOf<List<RuntimeWrite>>()

    val stored: SessionState?
        get() = row?.let { (SessionJson.decode(it) as StoredSession.Found).state }

    override suspend fun load(): Outcome<StoredSession, DomainError> {
        loadFailure?.let { return Outcome.Failure(it) }
        return Outcome.Success(row?.let { SessionJson.decode(it) } ?: StoredSession.Empty)
    }

    override suspend fun commit(
        state: SessionState,
        writes: List<RuntimeWrite>,
    ): Outcome<Unit, DomainError> {
        val added = writes.filterIsInstance<RuntimeWrite.PutPurchaseIntent>().map { it.intent }
        val granted = writes.filterIsInstance<RuntimeWrite.PutGrant>().map { it.grant }
        val duplicate = added.any { it.intentId in intents } || added.map { it.intentId }.toSet().size != added.size
        val duplicateGrant = granted.any { it.token in grants } || granted.map { it.token }.toSet().size != granted.size
        (
            commitFailure
                ?: DomainError.StorageFailure("duplicate intent").takeIf { duplicate }
                ?: DomainError.StorageFailure("duplicate grant").takeIf { duplicateGrant }
        )?.let { return Outcome.Failure(it) }
        added.forEach { intents[it.intentId] = it }
        granted.forEach { grants[it.token] = it }
        writeLog += writes
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

/** A [SessionHistoryRepository] in memory: one row per session id. Every successful upsert is kept in [upserts]. */
internal class InMemoryHistory : SessionHistoryRepository {
    val rows = mutableMapOf<String, SessionHistoryRow>()
    val upserts = mutableListOf<SessionHistoryRow>()

    var upsertFailure: DomainError? = null
    var findFailure: DomainError? = null

    override suspend fun upsert(row: SessionHistoryRow): Outcome<Unit, DomainError> {
        upsertFailure?.let { return Outcome.Failure(it) }
        rows[row.sessionId] = row
        upserts += row
        return Outcome.Success(Unit)
    }

    override suspend fun find(sessionId: String): Outcome<SessionHistoryRow?, DomainError> {
        findFailure?.let { return Outcome.Failure(it) }
        return Outcome.Success(rows[sessionId])
    }

    // The engine never reads it.
    override fun observeLatestMissed(): Flow<SessionHistoryRow?> = emptyFlow()

    /** Merge rows by (session, alarm, scheduled time), insert or ignore like the Room table. */
    val mergeRows = linkedMapOf<Triple<String, String, Instant>, SessionMergeRow>()
    var mergeFailure: DomainError? = null
    var mergeCalls = 0

    override suspend fun recordMerge(merge: SessionMergeRow): Outcome<Unit, DomainError> {
        mergeCalls++
        mergeFailure?.let { return Outcome.Failure(it) }
        mergeRows.getOrPut(Triple(merge.sessionId, merge.alarmId, merge.scheduledAt)) { merge }
        return Outcome.Success(Unit)
    }

    override suspend fun merges(sessionId: String): Outcome<List<SessionMergeRow>, DomainError> =
        Outcome.Success(mergeRows.values.filter { it.sessionId == sessionId })
}
