package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.nowMillis
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Records that the user turned off or deleted [Alarm] inside its lock window (PRD §6.2: allowed, confirmed and logged).
 * Pass the alarm as it was before the change (enabled, still stored): the event is written only when its next occurrence
 * is inside the window ([commitmentEventOf]), with that occurrence, and returned; outside the window nothing is written
 * and the result is null. Runs inside the session lock (Story 2.6). `SetAlarmEnabled` and `DeleteAlarm` write the same
 * event themselves, inside their own guarded write ([CommitmentLog], Story 4.6), so no path can skip it.
 */
class RecordCommitmentEvent(
    private val eventRepository: CommitmentEventRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val sessionLock: SessionLockGuard,
) {
    suspend operator fun invoke(
        alarm: Alarm,
        action: CommitmentAction,
    ): Outcome<CommitmentEvent?, DomainError> =
        sessionLock.whenIdle {
            val event = commitmentEventOf(alarm, action, clock.nowMillis(), timeZoneProvider.current(), ids::newId)
            if (event == null) Outcome.Success(null) else eventRepository.insert(event).map { event }
        }
}

/**
 * What the alarm use cases need to log a turn-off or a delete inside the lock window (Story 4.6). They write
 * [repository] themselves, inside their own `whenIdle`, right after the alarm change succeeded.
 */
class CommitmentLog(
    val repository: CommitmentEventRepository,
    private val ids: IdGenerator,
    private val timeZoneProvider: TimeZoneProvider,
) {
    /** The event for [alarm] (as it was before the change) at [now], or null outside its window. */
    fun eventFor(
        alarm: Alarm,
        action: CommitmentAction,
        now: Instant,
    ): CommitmentEvent? = commitmentEventOf(alarm, action, now, timeZoneProvider.current(), ids::newId)
}

/**
 * The event for [action] on [alarm] (as it was before the change) at [now]: its next occurrence when that is inside the
 * lock window ([LockWindow.of]), with an id from [newId]; null outside the window or for a disabled alarm.
 */
fun commitmentEventOf(
    alarm: Alarm,
    action: CommitmentAction,
    now: Instant,
    zone: TimeZone,
    newId: () -> String,
): CommitmentEvent? = LockWindow.of(alarm, now, zone)?.let { CommitmentEvent(newId(), alarm.id, it.scheduledAt, action, now) }
