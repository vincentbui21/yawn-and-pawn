package com.yawnandpawn.app.data.alarm

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/** [AlarmRepository] over the `alarm` table of `app.db`. Storage exceptions become `DomainError.StorageFailure`. */
class RoomAlarmRepository(
    private val dao: AlarmDao,
) : AlarmRepository {
    override fun observeAll(): Flow<List<Alarm>> = dao.observeAll().map { rows -> rows.map { it.toAlarm() } }

    override suspend fun listAll(): Outcome<List<Alarm>, DomainError> = storage { dao.listAll().map { it.toAlarm() } }

    // Row mapping runs inside storage { } too: a corrupt row (for example an out-of-range time) is a StorageFailure.
    override suspend fun get(id: String): Outcome<Alarm, DomainError> =
        storage { dao.get(id)?.toAlarm() }.flatMap { alarm ->
            alarm?.let { Outcome.Success(it) } ?: Outcome.Failure(DomainError.NotFound(id))
        }

    override suspend fun upsert(alarm: Alarm): Outcome<Unit, DomainError> = storage { dao.upsert(alarm.toEntity()) }

    override suspend fun delete(id: String): Outcome<Unit, DomainError> =
        storage { dao.delete(id) }.flatMap { deleted ->
            if (deleted == 0) Outcome.Failure(DomainError.NotFound(id)) else Outcome.Success(Unit)
        }

    // Room and the SQLite driver throw platform exceptions (constraint, I/O, corruption); map them all at this
    // boundary so core never sees an exception for a storage failure. Cancellation must still propagate.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> storage(block: suspend () -> T): Outcome<T, DomainError> =
        try {
            Outcome.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
        }
}
