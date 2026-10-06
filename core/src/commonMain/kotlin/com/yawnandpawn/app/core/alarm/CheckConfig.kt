package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * One check of an alarm (FR-PWK-1..3, Story 3.5): the `check_config` row at [position] (0-based, the order All mode
 * runs them in) holding [entry]. Each type appears at most once per alarm.
 *
 * @property id [idFor] the alarm and type: stable while the type stays on the alarm, and unique since a type appears
 * once per alarm.
 */
data class CheckConfig(
    val id: String,
    val alarmId: String,
    val position: Int,
    val entry: CheckEntry,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /** The row id of [type] on [alarmId] (`<alarm id>:<type id>`; the v6 migration writes the same form). */
        fun idFor(
            alarmId: String,
            type: CheckType,
        ): String = "$alarmId:${type.id}"

        /** The checks of a new alarm and of every alarm stored before Story 3.5: Math · Medium · 3 (epic default). */
        val DEFAULT_ENTRIES: List<CheckEntry> =
            listOf(CheckEntry(CheckType.Math, Difficulty.Medium, CheckType.Math.defaultCount))

        /**
         * The types a user may pick: every core plugin but the Epic 1 `Placeholder`. The pickers show the subset that also
         * has a wake screen (`CheckRegistry`, Story 3.2).
         */
        val PICKABLE_TYPES: List<CheckType> = CheckType.all.filter { it != CheckType.Placeholder }
    }
}

/** A stored alarm and its [checks], sorted by position (none: the alarm rings the default checks). */
data class AlarmWithChecks(
    val alarm: Alarm,
    val checks: List<CheckConfig>,
)

/** The entries of these rows, sorted by position. */
fun List<CheckConfig>.orderedEntries(): List<CheckEntry> = sortedBy { it.position }.map { it.entry }

/**
 * Whether [entries] are valid checks for an alarm, or [AlarmField.Checks]: at least one, only [CheckConfig.PICKABLE_TYPES],
 * no type twice, and each count within its type's `countRange`.
 */
fun validateChecks(entries: List<CheckEntry>): AlarmField? =
    when {
        entries.isEmpty() -> AlarmField.Checks
        entries.any { it.type !in CheckConfig.PICKABLE_TYPES } -> AlarmField.Checks
        entries.map { it.type }.toSet().size != entries.size -> AlarmField.Checks
        entries.any { it.count !in it.type.countRange } -> AlarmField.Checks
        else -> null
    }

/**
 * Port for the alarms' checks (`check_config` in `app.db`, `FakeCheckConfigRepository` in tests). Only the guarded alarm
 * use cases write through it (Story 2.6). Storage errors are `StorageFailure`, never thrown.
 */
interface CheckConfigRepository {
    /**
     * Every alarm with its checks (each list sorted by position), read from both tables at once so an alarm never shows
     * without its checks or with old ones; emits again after each change of either. A storage failure fails the flow:
     * the alarms and their checks are one read (Story 3.5 review).
     */
    fun observeAlarmsWithChecks(): Flow<List<AlarmWithChecks>>

    /** The checks of [alarmId] sorted by position; none (an empty list) for an alarm without rows. */
    suspend fun forAlarm(alarmId: String): Outcome<List<CheckConfig>, DomainError>

    /**
     * Inserts or replaces [alarm] (as `AlarmRepository.upsert` does) and replaces its checks with [configs], in one
     * transaction: either both are stored or neither is.
     */
    suspend fun saveWithAlarm(
        alarm: Alarm,
        configs: List<CheckConfig>,
    ): Outcome<Unit, DomainError>

    /**
     * Removes the alarm [alarmId] (as `AlarmRepository.delete` does) and its checks in one transaction: either both go or
     * neither does. `NotFound(alarmId)` when there is no such alarm.
     */
    suspend fun deleteWithAlarm(alarmId: String): Outcome<Unit, DomainError>
}
