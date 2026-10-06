package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.history.SessionOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * In-memory [SessionHistoryRepository] with the rules of `RoomSessionHistoryRepository`: one row per session id, an
 * upsert replaces it. Set [upsertFailure] or [findFailure] to make those calls fail (nothing changes). [upserts] lists
 * every successful upsert in order, so a test can tell a replayed write from a single one.
 */
class FakeSessionHistoryRepository : SessionHistoryRepository {
    private val stored = linkedMapOf<String, SessionHistoryRow>()
    private val written = mutableListOf<SessionHistoryRow>()
    private val changes = MutableStateFlow(0)

    var upsertFailure: DomainError? = null
    var findFailure: DomainError? = null

    /** Set to make [observeLatestMissed] throw it, like a failing database. */
    var observeFailure: Throwable? = null

    /** The stored rows, in the order their sessions were first written. */
    val rows: List<SessionHistoryRow>
        get() = stored.values.toList()

    val upserts: List<SessionHistoryRow>
        get() = written.toList()

    override suspend fun upsert(row: SessionHistoryRow): Outcome<Unit, DomainError> {
        upsertFailure?.let { return Outcome.Failure(it) }
        stored[row.sessionId] = row
        written += row
        changes.update { it + 1 }
        return Outcome.Success(Unit)
    }

    override suspend fun find(sessionId: String): Outcome<SessionHistoryRow?, DomainError> {
        findFailure?.let { return Outcome.Failure(it) }
        return Outcome.Success(stored[sessionId])
    }

    private val mergeRows = linkedMapOf<Triple<String, String, Instant>, SessionMergeRow>()

    /** Set to make [recordMerge] fail (nothing changes). */
    var mergeFailure: DomainError? = null

    /** The stored merge rows, oldest first. */
    val merges: List<SessionMergeRow>
        get() = mergeRows.values.toList()

    /** Like the Room table: insert or ignore on (session id, alarm id, scheduled time). */
    override suspend fun recordMerge(merge: SessionMergeRow): Outcome<Unit, DomainError> {
        mergeFailure?.let { return Outcome.Failure(it) }
        mergeRows.getOrPut(Triple(merge.sessionId, merge.alarmId, merge.scheduledAt)) { merge }
        return Outcome.Success(Unit)
    }

    override suspend fun merges(sessionId: String): Outcome<List<SessionMergeRow>, DomainError> =
        Outcome.Success(mergeRows.values.filter { it.sessionId == sessionId })

    /** Like the Room query: the Missed row with the latest end time, again after every upsert. */
    override fun observeLatestMissed(): Flow<SessionHistoryRow?> =
        changes.map {
            observeFailure?.let { throw it }
            stored.values.filter { it.outcome == SessionOutcome.Missed }.maxByOrNull { it.endedAt ?: Instant.DISTANT_PAST }
        }
}

/** Builds a finished [SessionHistoryRow] at [DEFAULT_FAKE_INSTANT]: on time, one placeholder step, 3 minutes to complete. */
fun aSessionHistoryRow(
    sessionId: String = FakeIdGenerator.fakeUuid(n = 100),
    alarmId: String = FakeIdGenerator.fakeUuid(1),
): SessionHistoryRow =
    SessionHistoryRow(
        sessionId = sessionId,
        alarmId = alarmId,
        scheduledAt = DEFAULT_FAKE_INSTANT,
        firstRingAt = DEFAULT_FAKE_INSTANT,
        endedAt = DEFAULT_FAKE_INSTANT + 3.minutes,
        snoozeCount = 0,
        checkTypes = listOf("Placeholder"),
        timeToCompleteMs = 180_000,
        fallbackUsed = false,
        directBoot = false,
        outcome = SessionOutcome.OnTime,
    )
