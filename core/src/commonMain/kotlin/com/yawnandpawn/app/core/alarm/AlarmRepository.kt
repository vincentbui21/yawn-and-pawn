package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow

/** Port for stored alarms (`app.db` in production, `FakeAlarmRepository` in tests). */
interface AlarmRepository {
    /** Every alarm, ordered by [AlarmListOrder]; emits again after each change. */
    fun observeAll(): Flow<List<Alarm>>

    /** Every alarm right now, ordered by [AlarmListOrder]; a storage error is a `StorageFailure`, never thrown. */
    suspend fun listAll(): Outcome<List<Alarm>, DomainError>

    /** The alarm with [id], or `NotFound(id)`. */
    suspend fun get(id: String): Outcome<Alarm, DomainError>

    /** Inserts or replaces the alarm with `alarm.id`; a request code used by another alarm is a `StorageFailure`. */
    suspend fun upsert(alarm: Alarm): Outcome<Unit, DomainError>

    /** Removes the alarm with [id], or returns `NotFound(id)` and changes nothing. */
    suspend fun delete(id: String): Outcome<Unit, DomainError>
}
