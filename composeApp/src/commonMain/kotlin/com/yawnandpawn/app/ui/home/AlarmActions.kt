package com.yawnandpawn.app.ui.home

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.DeleteAlarm
import com.yawnandpawn.app.core.alarm.SetAlarmEnabled
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.Clock

/**
 * What Home's cards and the editor's overflow menu do to a stored alarm, with the logging both need: a deletion is
 * logged as `AlarmDeleted(id, now)` (the dialog says "This is logged."), and every failure through [Logger] (the
 * screens decide what the user sees). A [DomainError.SessionActive] refusal (a session started meanwhile, Story 2.6) is
 * not logged: the app is locked to "Alarm in progress" then, and nothing failed.
 */
class AlarmActions(
    private val setAlarmEnabled: SetAlarmEnabled,
    private val deleteAlarm: DeleteAlarm,
    private val clock: Clock,
    private val logger: Logger,
) {
    /** Turns the alarm [id] on or off at once. */
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Outcome<Alarm, DomainError> = setAlarmEnabled(id, enabled).alsoLogFailure("turn alarm ${if (enabled) "on" else "off"}")

    /** Deletes the alarm [id] and logs the deletion once it is done. */
    suspend fun delete(id: String): Outcome<Unit, DomainError> {
        val result = deleteAlarm(id).alsoLogFailure("delete alarm")
        if (result is Outcome.Success) logger.log(LogEvent.AlarmDeleted(id, clock.now()))
        return result
    }

    /** Logs that [operation] failed with [error]. */
    fun logFailure(
        operation: String,
        error: DomainError,
    ) = logger.log(LogEvent.OperationFailed.of(operation, error))

    /** Logs that [operation] failed with an unexpected [cause] (a storage exception from a flow). */
    fun logFailure(
        operation: String,
        cause: Throwable,
    ) = logger.log(LogEvent.OperationFailed(operation, cause.message ?: cause::class.simpleName.orEmpty()))

    // A session that started meanwhile (Story 2.6) refused the write on purpose: not a failure, so not logged as one.
    private fun <T> Outcome<T, DomainError>.alsoLogFailure(operation: String): Outcome<T, DomainError> =
        also { if (it is Outcome.Failure && it.error != DomainError.SessionActive) logFailure(operation, it.error) }
}
