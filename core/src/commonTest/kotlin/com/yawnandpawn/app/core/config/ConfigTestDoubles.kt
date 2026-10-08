package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.GlobalSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// :core tests cannot use :testing (it depends on :core), so these mirror its config fakes.

internal class InMemoryPendingChanges(
    initial: List<PendingChange> = emptyList(),
) : PendingChangeRepository {
    val changes = MutableStateFlow(initial)
    var failure: DomainError? = null
    var writeFailure: DomainError? = null

    override fun observe(): Flow<List<PendingChange>> = changes

    override suspend fun all(): Outcome<List<PendingChange>, DomainError> =
        failure?.let { Outcome.Failure(it) } ?: Outcome.Success(changes.value)

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

internal class InMemoryGlobalSettings(
    initial: GlobalSettings = GlobalSettings(),
) : GlobalSettingsRepository {
    val settings = MutableStateFlow(initial)
    var failure: DomainError? = null
    var writes = 0

    override fun observe(): Flow<GlobalSettings> = settings

    override suspend fun get(): Outcome<GlobalSettings, DomainError> =
        failure?.let { Outcome.Failure(it) } ?: Outcome.Success(settings.value)

    override suspend fun setBaseFeeTier(tier: Int): Outcome<Unit, DomainError> = write { it.copy(baseFeeTier = tier) }

    override suspend fun setMaxSnoozes(count: Int): Outcome<Unit, DomainError> = write { it.copy(maxSnoozes = count) }

    private fun write(change: (GlobalSettings) -> GlobalSettings): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        writes++
        settings.value = change(settings.value)
        return Outcome.Success(Unit)
    }
}

internal class InMemoryCommitmentEvents : CommitmentEventRepository {
    val events = mutableListOf<CommitmentEvent>()
    var failure: DomainError? = null

    override suspend fun insert(event: CommitmentEvent): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        events += event
        return Outcome.Success(Unit)
    }

    override suspend fun all(): Outcome<List<CommitmentEvent>, DomainError> =
        failure?.let { Outcome.Failure(it) } ?: Outcome.Success(events.toList())
}
