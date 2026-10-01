package com.yawnandpawn.app.core.log

import com.yawnandpawn.app.core.error.DomainError
import kotlin.time.Instant

/**
 * Port for the app's log (Architecture Conventions: Logging). `:core` never prints; the adapter decides where an event
 * goes (`AndroidLogger` in the app, `FakeLogger` in tests). Never log purchase tokens, photos or recordings.
 */
fun interface Logger {
    fun log(event: LogEvent)
}

/** Everything the app logs. Events are past tense. */
sealed interface LogEvent {
    /** The user deleted the alarm [alarmId] at [at] (the delete dialog says "This is logged."). */
    data class AlarmDeleted(
        val alarmId: String,
        val at: Instant,
    ) : LogEvent

    /**
     * An expected failure the user can only retry: [operation] says what failed ("load alarms"), [cause] is diagnostic
     * text for the log only and is never shown to users.
     */
    data class OperationFailed(
        val operation: String,
        val cause: String,
    ) : LogEvent {
        companion object {
            /** [operation] failed with [error]. */
            fun of(
                operation: String,
                error: DomainError,
            ): OperationFailed = OperationFailed(operation, error.diagnostic())
        }
    }
}

/** Log text for [this] error: the storage cause, the missing id or the rejected field. */
fun DomainError.diagnostic(): String =
    when (this) {
        is DomainError.InvalidAlarm -> "invalid alarm field $field"
        is DomainError.NotFound -> "not found: $id"
        is DomainError.StorageFailure -> "storage failure: $cause"
    }
