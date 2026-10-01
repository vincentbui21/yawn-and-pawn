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

/** Which system alarm fired (AD-4): a stored alarm, the session slot or the test alarm. */
enum class FireKind {
    Alarm,
    SessionSlot,
    TestAlarm,
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

    /**
     * A system alarm of [kind] fired and nothing acted on it, because of [reason] (for example the alarm [alarmId] was
     * deleted or disabled, or the slot is not bound yet).
     */
    data class FireIgnored(
        val kind: FireKind,
        val alarmId: String?,
        val reason: String,
    ) : LogEvent

    /**
     * `rescheduleAll()` ran: [scheduled] enabled alarms armed, [disabled] disabled alarms whose cancel call returned
     * (not necessarily a system alarm removed), [failed] scheduler calls that failed.
     */
    data class AlarmsRescheduled(
        val scheduled: Int,
        val disabled: Int,
        val failed: Int,
    ) : LogEvent

    /**
     * The enabled one-time alarm [alarmId] was meant to ring at [missedAt], which passed while the phone was off or the
     * clock jumped; `rescheduleAll()` switched it off instead of moving it to the next day.
     */
    data class OneTimeAlarmPassed(
        val alarmId: String,
        val missedAt: Instant,
    ) : LogEvent
}

/** Log text for [this] error: the storage cause, the missing id or the rejected field. */
fun DomainError.diagnostic(): String =
    when (this) {
        is DomainError.InvalidAlarm -> "invalid alarm field $field"
        is DomainError.NotFound -> "not found: $id"
        is DomainError.StorageFailure -> "storage failure: $cause"
        DomainError.ExactAlarmNotPermitted -> "exact alarms not permitted"
        is DomainError.SchedulerFailure -> "scheduler failure: $cause"
    }
