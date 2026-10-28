package com.yawnandpawn.app.ui.home

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.DeleteAlarm
import com.yawnandpawn.app.core.alarm.SetAlarmEnabled
import com.yawnandpawn.app.core.alarm.SkipNextAlarm
import com.yawnandpawn.app.core.alarm.SkipResult
import com.yawnandpawn.app.core.alarm.UndoSkip
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger

/**
 * What Home's cards and the editor's overflow menu do to a stored alarm, with the logging both need: every failure
 * through [Logger] (the screens decide what the user sees). `DeleteAlarm` logs the deletion itself, and
 * `SetAlarmEnabled` and `DeleteAlarm` write the commitment event inside the lock window (Story 4.6), so no caller can
 * skip them. A [DomainError.SessionActive] refusal (a session started meanwhile, Story 2.6) is not logged: the app is
 * locked to "Alarm in progress" then, and nothing failed. Story 7.11: "Skip this one" confirmed and "Don't skip".
 */
class AlarmActions(
    private val setAlarmEnabled: SetAlarmEnabled,
    private val deleteAlarm: DeleteAlarm,
    private val logger: Logger,
    /** "Skip this one" confirmed (Story 7.11); null where nothing is skipped (tests, the editor). */
    private val skipNext: SkipNextAlarm? = null,
    /** "Don't skip" on Home's card (Story 7.11); null where nothing is skipped. */
    private val undoSkip: UndoSkip? = null,
) {
    /** Turns the alarm [id] on or off at once. */
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Outcome<Alarm, DomainError> = setAlarmEnabled(id, enabled).alsoLogFailure("turn alarm ${if (enabled) "on" else "off"}")

    /** Deletes the alarm [id] (`DeleteAlarm` logs the deletion). */
    suspend fun delete(id: String): Outcome<Unit, DomainError> = deleteAlarm(id).alsoLogFailure("delete alarm")

    /** Skips the next occurrence of the alarm [id] (Story 7.11); nothing to skip without the use case. */
    suspend fun skipNext(id: String): Outcome<SkipResult, DomainError> =
        (skipNext?.invoke(id) ?: Outcome.Success(SkipResult.NothingToSkip)).alsoLogFailure("skip alarm")

    /** Undoes the skip of the alarm [id] (Story 7.11); true when one was undone. */
    suspend fun undoSkip(id: String): Outcome<Boolean, DomainError> =
        (undoSkip?.invoke(id) ?: Outcome.Success(false)).alsoLogFailure("undo skip")

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
