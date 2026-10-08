package com.yawnandpawn.app.data.config

import com.yawnandpawn.app.core.config.CommitmentAction
import com.yawnandpawn.app.core.config.CommitmentEvent
import com.yawnandpawn.app.core.config.CommitmentEventRepository
import com.yawnandpawn.app.core.config.LockedField
import com.yawnandpawn.app.core.config.Occurrence
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.PendingChangeJson
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * The alarms' pending changes in `pending_change` (Story 4.4). A row this build cannot read (an unknown field, a value
 * that does not decode or belongs to another field) is skipped: the alarm then rings its live settings, the stronger
 * ones. Storage exceptions become `StorageFailure`.
 */
class RoomPendingChangeRepository(
    private val dao: PendingChangeDao,
) {
    fun observe(): Flow<List<PendingChange>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toChange() } }

    suspend fun all(): Outcome<List<PendingChange>, DomainError> = storage { dao.all().mapNotNull { it.toChange() } }

    suspend fun forAlarm(alarmId: String): Outcome<List<PendingChange>, DomainError> =
        storage { dao.forAlarm(alarmId).mapNotNull { it.toChange() } }

    /** Stores an alarm's [change] ([PendingChange.alarmId] not null). */
    suspend fun put(change: PendingChange): Outcome<Unit, DomainError> = storage { dao.put(change.toEntity()) }

    suspend fun remove(
        alarmId: String,
        field: LockedField,
    ): Outcome<Unit, DomainError> = storage { dao.remove(alarmId, field.name) }
}

/** The commitment events in `commitment_event` (Story 4.4). An action this build does not know is skipped. */
class RoomCommitmentEventRepository(
    private val dao: CommitmentEventDao,
) : CommitmentEventRepository {
    override suspend fun insert(event: CommitmentEvent): Outcome<Unit, DomainError> =
        storage {
            dao.insert(
                CommitmentEventEntity(
                    id = event.id,
                    alarmId = event.alarmId,
                    occurrenceAt = event.occurrenceAt.toEpochMilliseconds(),
                    action = event.action.name,
                    at = event.at.toEpochMilliseconds(),
                ),
            )
        }

    override suspend fun all(): Outcome<List<CommitmentEvent>, DomainError> =
        storage {
            dao.all().mapNotNull { row ->
                CommitmentAction.entries.firstOrNull { it.name == row.action }?.let { action ->
                    CommitmentEvent(
                        id = row.id,
                        alarmId = row.alarmId,
                        occurrenceAt = Instant.fromEpochMilliseconds(row.occurrenceAt),
                        action = action,
                        at = Instant.fromEpochMilliseconds(row.at),
                    )
                }
            }
        }
}

internal fun PendingChange.toEntity(): PendingChangeEntity =
    PendingChangeEntity(
        alarmId = requireNotNull(alarmId) { "a global pending change is kept in the settings DataStore" },
        field = field.name,
        valueJson = PendingChangeJson.encode(value),
        effectiveAfterAlarmId = effectiveAfter.alarmId,
        effectiveAfterScheduledAt = effectiveAfter.scheduledAt.toEpochMilliseconds(),
    )

/** The row as a [PendingChange], or null when this build cannot read it. */
internal fun PendingChangeEntity.toChange(): PendingChange? =
    PendingChangeJson
        .decode(valueJson)
        ?.takeIf { it.field.name == field && !it.field.global }
        ?.let { PendingChange(alarmId, it, Occurrence(effectiveAfterAlarmId, Instant.fromEpochMilliseconds(effectiveAfterScheduledAt))) }

// As in RoomAlarmRepository: platform exceptions become StorageFailure at this boundary; cancellation propagates.
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> storage(block: suspend () -> T): Outcome<T, DomainError> =
    try {
        Outcome.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
    }
