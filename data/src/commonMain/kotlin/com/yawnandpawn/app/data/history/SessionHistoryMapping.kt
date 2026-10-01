package com.yawnandpawn.app.data.history

import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import kotlin.time.Instant

// The stored forms are fixed strings, never enum or class names, so renaming a Kotlin type cannot change history.

private const val CHECK_TYPE_SEPARATOR = ","

/**
 * [this] as the stored row. A check type name that is empty or holds the separator could not be read back, so it throws;
 * the repository maps that to a `StorageFailure`.
 */
internal fun SessionHistoryRow.toEntity(): SessionHistoryEntity {
    require(checkTypes.none { it.isEmpty() || CHECK_TYPE_SEPARATOR in it }) { "check type names must be non-empty and comma-free" }
    return SessionHistoryEntity(
        sessionId = sessionId,
        alarmId = alarmId,
        scheduledAt = scheduledAt.toEpochMilliseconds(),
        firstRingAt = firstRingAt.toEpochMilliseconds(),
        endedAt = endedAt?.toEpochMilliseconds(),
        snoozeCount = snoozeCount,
        checkTypes = checkTypes.joinToString(CHECK_TYPE_SEPARATOR),
        timeToCompleteMs = timeToCompleteMs,
        fallbackUsed = fallbackUsed,
        directBoot = directBoot,
        outcome = outcome?.storedName(),
    )
}

/** The row [this] stores. An unknown outcome name throws; the repository maps it to a `StorageFailure`. */
internal fun SessionHistoryEntity.toRow(): SessionHistoryRow =
    SessionHistoryRow(
        sessionId = sessionId,
        alarmId = alarmId,
        scheduledAt = Instant.fromEpochMilliseconds(scheduledAt),
        firstRingAt = Instant.fromEpochMilliseconds(firstRingAt),
        endedAt = endedAt?.let { Instant.fromEpochMilliseconds(it) },
        snoozeCount = snoozeCount,
        checkTypes = if (checkTypes.isEmpty()) emptyList() else checkTypes.split(CHECK_TYPE_SEPARATOR),
        timeToCompleteMs = timeToCompleteMs,
        fallbackUsed = fallbackUsed,
        directBoot = directBoot,
        outcome = outcome?.let { sessionOutcomeOf(it) },
    )

/** The stable stored name of [this] outcome. */
internal fun SessionOutcome.storedName(): String =
    when (this) {
        SessionOutcome.OnTime -> "OnTime"
        SessionOutcome.Snoozed -> "Snoozed"
        SessionOutcome.Missed -> "Missed"
        SessionOutcome.Skipped -> "Skipped"
        SessionOutcome.Test -> "Test"
    }

/** The outcome stored as [name]. */
internal fun sessionOutcomeOf(name: String): SessionOutcome =
    SessionOutcome.entries.firstOrNull { it.storedName() == name } ?: throw IllegalArgumentException("unknown session outcome $name")
