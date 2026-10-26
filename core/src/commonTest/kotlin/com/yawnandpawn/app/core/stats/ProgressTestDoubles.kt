package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.billing.PurchaseRecordsRead
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.TimeZone
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/*
 * Test-local doubles for the Story 6.2 flow tests: `:core` tests may not use `:testing` (CoreDependencyRules), so these
 * stand in for FakeSessionHistoryRepository, FakePurchaseRecordRepository and FakeClock.
 */

/** Session history in memory; [observeAll] emits the rows in scheduled order after every [upsert]. */
internal class MutableHistory : SessionHistoryRepository {
    private val rows = MutableStateFlow<List<SessionHistoryRow>>(emptyList())

    /** Set to make [observeAll] throw it, like a failing database. */
    var observeFailure: Throwable? = null

    /** The context [observeAll] was last collected in. */
    var collectedIn: CoroutineContext? = null

    override suspend fun upsert(row: SessionHistoryRow): Outcome<Unit, DomainError> {
        rows.value = rows.value.filterNot { it.sessionId == row.sessionId } + row
        return Outcome.Success(Unit)
    }

    override suspend fun find(sessionId: String): Outcome<SessionHistoryRow?, DomainError> =
        Outcome.Success(rows.value.firstOrNull { it.sessionId == sessionId })

    override fun observeAll(): Flow<List<SessionHistoryRow>> =
        rows
            .onStart { collectedIn = currentCoroutineContext() }
            .map { all ->
                observeFailure?.let { throw it }
                all.sortedWith(compareBy({ it.scheduledAt }, { it.sessionId }))
            }

    override fun observeLatestMissed(): Flow<SessionHistoryRow?> = emptyFlow()

    override suspend fun recordMerge(merge: SessionMergeRow): Outcome<Unit, DomainError> = Outcome.Success(Unit)

    override suspend fun merges(sessionId: String): Outcome<List<SessionMergeRow>, DomainError> = Outcome.Success(emptyList())

    override suspend fun all(): Outcome<List<SessionHistoryRow>, DomainError> = Outcome.Success(rows.value)
}

/** Purchase records in memory, keyed by token hash like the Room table; [observeAll] emits after every [putRecord]. */
internal class MutableRecords : PurchaseRecordRepository {
    private val read = MutableStateFlow(PurchaseRecordsRead(emptyList()))

    /** Set to make [observeAll] throw it, like a failing database. */
    var observeFailure: Throwable? = null

    /** Stored rows that could not be read. */
    var unreadable: Int
        get() = read.value.unreadable
        set(value) {
            read.value = read.value.copy(unreadable = value)
        }

    override suspend fun get(tokenHash: String): Outcome<PurchaseRecord?, DomainError> =
        Outcome.Success(read.value.records.firstOrNull { it.tokenHash == tokenHash })

    override suspend fun putRecord(record: PurchaseRecord): Outcome<Unit, DomainError> {
        val others = read.value.records.filterNot { it.tokenHash == record.tokenHash }
        read.value = read.value.copy(records = (others + record).sortedByDescending { it.purchasedAt })
        return Outcome.Success(Unit)
    }

    override suspend fun all(): Outcome<List<PurchaseRecord>, DomainError> = Outcome.Success(read.value.records)

    override suspend fun deleteNotOwed(keep: Set<String>): Outcome<Int, DomainError> = error("never deleted here")

    override fun observeAll(): Flow<PurchaseRecordsRead> =
        read.map {
            observeFailure?.let { throw it }
            it
        }
}

/** A wall clock that reads [now]. */
internal class TestClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

/** The phone's zone, [zone]. */
internal class TestZones(
    var zone: TimeZone,
) : TimeZoneProvider {
    override fun current(): TimeZone = zone
}

/** The time-change broadcasts: [tick] sends one. */
internal class TestTimeSignal : TimeChangeSignal {
    private val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun tick() {
        check(ticks.tryEmit(Unit)) { "a tick was dropped" }
    }

    override fun changes(): Flow<Unit> = ticks
}
