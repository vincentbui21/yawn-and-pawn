package com.yawnandpawn.app.core.error

import com.yawnandpawn.app.core.alarm.AlarmField

/** Expected failures of the domain (AD-12). Adapters map platform exceptions to these; user-facing copy is keyed by them. */
sealed interface DomainError {
    /** An alarm value is outside its allowed range; nothing was stored. */
    data class InvalidAlarm(
        val field: AlarmField,
    ) : DomainError

    /** No stored item has this [id]. */
    data class NotFound(
        val id: String,
    ) : DomainError

    /**
     * The storage layer failed (for example a constraint violation or an I/O error). [cause] is diagnostic text for
     * logs only and must never be shown to users; user-facing copy is keyed by the error type.
     */
    data class StorageFailure(
        val cause: String,
    ) : DomainError

    /**
     * The system does not allow exact alarms (API 31-32 with "Alarms & reminders" off), so nothing was armed. There
     * is never an inexact fallback; the alarm stays stored and Story 1.19 asks for the permission.
     */
    data object ExactAlarmNotPermitted : DomainError

    /**
     * The system refused to arm an alarm for another reason (for example the per-app alarm limit). [cause] is
     * diagnostic text for logs only.
     */
    data class SchedulerFailure(
        val cause: String,
    ) : DomainError
}
