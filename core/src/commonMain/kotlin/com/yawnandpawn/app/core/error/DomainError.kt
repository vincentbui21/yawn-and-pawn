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

    /**
     * A wake session is active, so nothing the user owns may change (FR-SES-3, Story 2.6); nothing was written. Returned
     * by `SessionLockGuard`. The app shows only "Alarm in progress" then, so no screen shows this error.
     */
    data object SessionActive : DomainError

    /** Two amounts in different currencies were added (AD-8); totals are grouped by currency instead. */
    data class CurrencyMismatch(
        val left: String,
        val right: String,
    ) : DomainError

    /** [currency] is not an ISO 4217 code (3 upper-case letters), so no `Money` was made. */
    data class InvalidCurrency(
        val currency: String,
    ) : DomainError

    /** The fee ladder was asked for a base fee tier outside 1–10 or a snooze number below 1 (Story 4.2). */
    data class InvalidFee(
        val baseFeeTier: Int,
        val snoozeNumber: Int,
    ) : DomainError
}
