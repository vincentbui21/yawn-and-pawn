package com.yawnandpawn.app.core.reminder

import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.SkipNextAlarm
import com.yawnandpawn.app.core.alarm.SkipResult
import com.yawnandpawn.app.core.alarm.nextOccurrence
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

/**
 * The Settings "Skip reminder" switch (0.1.8): stores [on] and aligns the reminder at once (off cancels the job and
 * removes a posted reminder), no Save. Not a locked or weakening change (skipping is free and allowed inside the lock
 * window, FR-ALM-10), so it may be turned on or off at any time. Like every user-facing write, the store write runs inside
 * [SessionLockGuard.whenIdle]: during a session it changes nothing and returns [DomainError.SessionActive]. The reminder
 * is aligned after the guard is released, like `SetWeeklySummary`, so a ring never waits for it.
 */
class SetSkipReminder(
    private val store: SkipReminderStore,
    private val scheduler: SkipReminderScheduler,
    private val sessionLock: SessionLockGuard,
) {
    suspend operator fun invoke(on: Boolean): Outcome<Unit, DomainError> =
        sessionLock.whenIdle { store.setEnabled(on) }.flatMap { scheduler.sync() }
}

/**
 * "Skip today's alarms" on the Skip reminder (0.1.8): skips, through [SkipNextAlarm] (free, a Skipped history row, the
 * alarm re-armed for its following ring), every enabled alarm whose next occurrence (after now, in the current zone) is
 * on [date]; returns how many were skipped now (an occurrence skipped already counts as done, nothing new).
 *
 * Each skip runs inside [SessionLockGuard.whenIdle] (in [SkipNextAlarm]): during a session nothing is skipped. When any
 * due alarm could not be skipped (a session, a failed write) the first failure is returned, after trying the others, so
 * the reminder stays and a retry skips the rest; the ones skipped stay skipped (each is complete with its row). A failed
 * read of the alarms is returned.
 */
class SkipTodaysAlarms(
    private val repository: AlarmRepository,
    private val skip: SkipNextAlarm,
    private val clock: Clock,
    private val zones: TimeZoneProvider,
) {
    suspend operator fun invoke(date: LocalDate): Outcome<Int, DomainError> =
        repository.listAll().flatMap { all ->
            val now = clock.now()
            val zone = zones.current()
            val results =
                all
                    .filter { it.enabled }
                    .map { it.id to nextOccurrence(it.toRule(), now, zone) }
                    .filter { (_, at) -> at.toLocalDateTime(zone).date == date }
                    .map { (id, at) -> skip(id, at) }
            val failed = results.firstOrNull { it is Outcome.Failure } as Outcome.Failure?
            failed ?: Outcome.Success(results.count { (it as Outcome.Success).value == SkipResult.Skipped })
        }
}
