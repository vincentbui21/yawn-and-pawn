package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.config.CommitmentEvent
import com.yawnandpawn.app.core.config.CommitmentEventRepository
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.LockedField
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.config.SettingsSnapshot
import com.yawnandpawn.app.core.config.SettingsSnapshotCache
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.GlobalSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration

/**
 * In-memory [PendingChangeRepository]: one change per (alarm or global, field), as the Room and DataStore adapters keep.
 * Set [failure] to make every call fail with it, or [writeFailure] to fail only [put] and [remove]. Deleting an alarm
 * does not cascade here (the Room table's foreign key does).
 */
class FakePendingChangeRepository(
    initial: List<PendingChange> = emptyList(),
) : PendingChangeRepository {
    private val changes = MutableStateFlow(initial)

    var failure: DomainError? = null
    var writeFailure: DomainError? = null

    /** Every stored change, in the order stored. */
    val current: List<PendingChange>
        get() = changes.value

    override fun observe(): Flow<List<PendingChange>> = changes

    override suspend fun all(): Outcome<List<PendingChange>, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        return Outcome.Success(changes.value)
    }

    override suspend fun forAlarm(alarmId: String): Outcome<List<PendingChange>, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        return Outcome.Success(changes.value.filter { it.alarmId == alarmId })
    }

    override suspend fun put(change: PendingChange): Outcome<Unit, DomainError> {
        (failure ?: writeFailure)?.let { return Outcome.Failure(it) }
        changes.value = changes.value.filterNot { it.alarmId == change.alarmId && it.field == change.field } + change
        return Outcome.Success(Unit)
    }

    override suspend fun remove(
        alarmId: String?,
        field: LockedField,
    ): Outcome<Unit, DomainError> {
        (failure ?: writeFailure)?.let { return Outcome.Failure(it) }
        changes.value = changes.value.filterNot { it.alarmId == alarmId && it.field == field }
        return Outcome.Success(Unit)
    }
}

/**
 * In-memory [GlobalSettingsRepository] starting at [initial]; its [snapshot] holds the global changes of [pending] (in
 * the app both live in the settings DataStore). Set [failure] to make every call fail with it, or [snapshotDelay] to
 * make [snapshot] slow. Each successful snapshot or write becomes [lastKnown], which a test may also set.
 */
class FakeGlobalSettingsRepository(
    initial: GlobalSettings = GlobalSettings(),
    private val pending: PendingChangeRepository? = null,
) : GlobalSettingsRepository {
    private val settings = MutableStateFlow(initial)

    var failure: DomainError? = null
    var snapshotDelay: Duration = Duration.ZERO
    var lastKnownSnapshot: SettingsSnapshot? = null

    val current: GlobalSettings
        get() = settings.value

    override fun observe(): Flow<GlobalSettings> = settings

    override suspend fun get(): Outcome<GlobalSettings, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        return Outcome.Success(settings.value)
    }

    override suspend fun setBaseFeeTier(tier: Int): Outcome<Unit, DomainError> = write { it.copy(baseFeeTier = tier) }

    override suspend fun setMaxSnoozes(count: Int): Outcome<Unit, DomainError> = write { it.copy(maxSnoozes = count) }

    override suspend fun snapshot(): Outcome<SettingsSnapshot, DomainError> {
        delay(snapshotDelay)
        failure?.let { return Outcome.Failure(it) }
        return Outcome.Success(currentSnapshot().also { lastKnownSnapshot = it })
    }

    override fun lastKnown(): SettingsSnapshot? = lastKnownSnapshot

    private suspend fun currentSnapshot(): SettingsSnapshot {
        val global = (pending?.all() as? Outcome.Success)?.value.orEmpty().filter { it.alarmId == null }
        return SettingsSnapshot(settings.value, global)
    }

    private suspend fun write(change: (GlobalSettings) -> GlobalSettings): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        settings.value = change(settings.value)
        lastKnownSnapshot = currentSnapshot()
        return Outcome.Success(Unit)
    }
}

/** In-memory [SettingsSnapshotCache] holding [snapshot]. */
class FakeSettingsSnapshotCache(
    var snapshot: SettingsSnapshot? = null,
) : SettingsSnapshotCache {
    override fun load(): SettingsSnapshot? = snapshot

    override fun save(snapshot: SettingsSnapshot) {
        this.snapshot = snapshot
    }
}

/** In-memory [CommitmentEventRepository]. Set [failure] to make every call fail with it. */
class FakeCommitmentEventRepository : CommitmentEventRepository {
    private val events = mutableListOf<CommitmentEvent>()

    var failure: DomainError? = null

    val current: List<CommitmentEvent>
        get() = events.toList()

    override suspend fun insert(event: CommitmentEvent): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        events += event
        return Outcome.Success(Unit)
    }

    override suspend fun all(): Outcome<List<CommitmentEvent>, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        return Outcome.Success(events.sortedBy { it.at })
    }
}
