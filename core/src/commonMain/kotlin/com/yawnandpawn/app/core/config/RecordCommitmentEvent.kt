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

/**
 * Records that the user turned off or deleted [Alarm] inside its lock window (PRD §6.2: allowed, confirmed and logged;
 * Story 4.6 calls it). Pass the alarm as it was before the change (enabled, still stored): the event is written only when
 * its next occurrence is inside the window ([LockWindow.of]), with that occurrence, and returned; outside the window
 * nothing is written and the result is null. Runs inside the session lock (Story 2.6).
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
            val now = clock.nowMillis()
            val occurrence = LockWindow.of(alarm, now, timeZoneProvider.current())
            if (occurrence == null) {
                Outcome.Success(null)
            } else {
                val event = CommitmentEvent(ids.newId(), alarm.id, occurrence.scheduledAt, action, now)
                eventRepository.insert(event).map { event }
            }
        }
}
