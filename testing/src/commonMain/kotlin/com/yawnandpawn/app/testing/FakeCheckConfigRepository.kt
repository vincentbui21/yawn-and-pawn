package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * In-memory [CheckConfigRepository] next to [alarms], with the same rules as `RoomCheckConfigRepository`:
 * [saveWithAlarm] stores the alarm through [alarms] and then its rows, and stores no row when the alarm fails (one
 * transaction). Set [failure] to make every call fail with it.
 */
class FakeCheckConfigRepository(
    private val alarms: AlarmRepository = FakeAlarmRepository(),
    initial: List<CheckConfig> = emptyList(),
) : CheckConfigRepository {
    private val rows = MutableStateFlow(initial.groupBy { it.alarmId }.mapValues { (_, list) -> list.sortedBy { it.position } })

    var failure: DomainError.StorageFailure? = null

    /** Every stored row by alarm id, each sorted by position. */
    val current: Map<String, List<CheckConfig>>
        get() = rows.value

    override fun observeAll(): Flow<Map<String, List<CheckConfig>>> =
        rows.map { byAlarm ->
            failure?.let { error("storage failure: ${it.cause}") }
            byAlarm
        }

    override suspend fun forAlarm(alarmId: String): Outcome<List<CheckConfig>, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        return Outcome.Success(rows.value[alarmId].orEmpty())
    }

    override suspend fun saveWithAlarm(
        alarm: Alarm,
        configs: List<CheckConfig>,
    ): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        return when (val stored = alarms.upsert(alarm)) {
            is Outcome.Failure -> {
                stored
            }

            is Outcome.Success -> {
                rows.value += alarm.id to configs.sortedBy { it.position }
                stored
            }
        }
    }

    override suspend fun deleteForAlarm(alarmId: String): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        rows.value -= alarmId
        return Outcome.Success(Unit)
    }
}

/** The rows of [entries] for [alarmId], in order, as the alarm use cases store them. */
fun checkConfigsOf(
    alarmId: String,
    entries: List<CheckEntry>,
    at: Instant = DEFAULT_FAKE_INSTANT,
): List<CheckConfig> =
    entries.mapIndexed { position, entry ->
        CheckConfig(
            id = CheckConfig.idFor(alarmId, entry.type),
            alarmId = alarmId,
            position = position,
            entry = entry,
            createdAt = at,
            updatedAt = at,
        )
    }
