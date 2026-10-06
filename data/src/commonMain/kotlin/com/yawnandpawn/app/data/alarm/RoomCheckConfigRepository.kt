package com.yawnandpawn.app.data.alarm

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmWithChecks
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.data.checks.CheckCodeColumns
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * [CheckConfigRepository] over the `check_config` table of `app.db`. Storage exceptions become `StorageFailure`. A row
 * whose type or difficulty this build does not know (written by a newer version) is skipped, so the alarm still rings
 * its other checks, or the default plan with none left.
 */
class RoomCheckConfigRepository(
    private val dao: CheckConfigDao,
) : CheckConfigRepository {
    override fun observeAlarmsWithChecks(): Flow<List<AlarmWithChecks>> =
        dao.observeAlarmsWithChecks().map { rows ->
            rows.map { row -> AlarmWithChecks(row.alarm.toAlarm(), row.checks.mapNotNull { it.toConfig() }.sortedBy { it.position }) }
        }

    override suspend fun forAlarm(alarmId: String): Outcome<List<CheckConfig>, DomainError> =
        storage { dao.forAlarm(alarmId).mapNotNull { it.toConfig() } }

    override suspend fun saveWithAlarm(
        alarm: Alarm,
        configs: List<CheckConfig>,
    ): Outcome<Unit, DomainError> = storage { dao.saveAlarmWithChecks(alarm.toEntity(), configs.map { it.toEntity() }) }

    override suspend fun deleteWithAlarm(alarmId: String): Outcome<Unit, DomainError> =
        storage { dao.deleteAlarmWithChecks(alarmId) }.flatMap { deleted ->
            if (deleted == 0) Outcome.Failure(DomainError.NotFound(alarmId)) else Outcome.Success(Unit)
        }

    // As in RoomAlarmRepository: platform exceptions become StorageFailure at this boundary; cancellation propagates.
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

internal fun CheckConfig.toEntity(): CheckConfigEntity {
    val (codeFormat, codeValue) = CheckCodeColumns.columnsOf(entry.code)
    return CheckConfigEntity(
        id = id,
        alarmId = alarmId,
        position = position,
        type = entry.type.id,
        difficulty = entry.difficulty.name,
        count = entry.count,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds(),
        codeFormat = codeFormat,
        codeValue = codeValue,
        codeRegisteredAt = codeRegisteredAt?.takeIf { entry.code != null }?.toEpochMilliseconds(),
    )
}

/** The row as a [CheckConfig], or null for a type or difficulty this build does not know. */
internal fun CheckConfigEntity.toConfig(): CheckConfig? {
    val checkType = CheckType.all.firstOrNull { it.id == type }
    val level = Difficulty.entries.firstOrNull { it.name == difficulty }
    // A code is read only for the type that has one, so a damaged row never gives another check a code.
    val code = CheckCodeColumns.registeredCodeOf(codeFormat, codeValue)?.takeIf { checkType == CheckType.QrBarcode }
    return if (checkType == null || level == null) {
        null
    } else {
        CheckConfig(
            id = id,
            alarmId = alarmId,
            position = position,
            entry = CheckEntry(checkType, level, count, code = code),
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt),
            codeRegisteredAt = codeRegisteredAt?.takeIf { code != null }?.let(Instant::fromEpochMilliseconds),
        )
    }
}
