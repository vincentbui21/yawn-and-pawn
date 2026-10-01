package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import kotlin.time.Instant

/**
 * The only writer of session history (AD-18): one row per session id, upserted, so a repeated write is safe.
 * `SessionEngine` drives it: [recordStart] for the one-shot `RecordSessionStart`, [recordEnd] for the entry effect
 * `HistoryWriteRequested` of Completed and Missed. Nothing else calls [SessionHistoryRepository.upsert] (a writer scan
 * test enforces it). Failures come back as values; the engine logs them and retries the end write later.
 */
class SessionRecorder(
    private val repository: SessionHistoryRepository,
) {
    /**
     * The start row of [session], with no outcome yet: alarm, scheduled time, first ring time (the scheduled time if the
     * session has none), whether it started before the first unlock, and the check it runs. Merged with a row already
     * stored: an end already written (outcome, end time, the counts) is kept, so a replayed start never erases it. A
     * failed read writes nothing.
     */
    suspend fun recordStart(session: SessionData): Outcome<Unit, DomainError> =
        repository.find(session.sessionId).flatMap { stored ->
            val start =
                rowOf(
                    session = session,
                    firstRingAt = session.firstRingAt ?: session.config.scheduledAt,
                    endedAt = null,
                    outcome = null,
                )
            val ended = stored?.takeIf { it.outcome != null || it.endedAt != null }
            repository.upsert(
                ended?.copy(
                    alarmId = start.alarmId,
                    scheduledAt = start.scheduledAt,
                    firstRingAt = start.firstRingAt,
                    directBoot = start.directBoot,
                ) ?: start,
            )
        }

    /**
     * The full row of [session], which ended as [end]. The end time is the session's own [SessionData.ended] (set by the
     * reducer), so a write retried or restored hours later records the real end; [now] (wall time) only stands in for a
     * session stored before Story 1.13, after an end time already stored. The time to complete is monotonic when the
     * first ring and the end share a boot (a wall clock change cannot distort it), wall time otherwise, never negative.
     * The first ring time comes from the session, else the stored row, else the scheduled time. The same write
     * repeated leaves one row with the same values. A failed read writes nothing.
     */
    suspend fun recordEnd(
        session: SessionData,
        end: SessionEnd,
        now: Instant,
    ): Outcome<Unit, DomainError> =
        repository.find(session.sessionId).flatMap { stored ->
            val firstRingAt = session.firstRingAt ?: stored?.firstRingAt ?: session.config.scheduledAt
            val endedAt = session.ended?.let { Instant.fromEpochMilliseconds(it.wallMillis) } ?: stored?.endedAt ?: now
            val firstRing = session.firstRing
            val ended = session.ended
            val timeToComplete =
                if (firstRing != null && ended != null) {
                    durationBetween(firstRing, ended).inWholeMilliseconds
                } else {
                    (endedAt - firstRingAt).inWholeMilliseconds.coerceAtLeast(0)
                }
            repository.upsert(
                rowOf(
                    session = session,
                    firstRingAt = firstRingAt,
                    endedAt = endedAt,
                    outcome = outcomeOf(session, end),
                ).copy(
                    timeToCompleteMs = timeToComplete.takeIf { end == SessionEnd.Completed },
                    // A session stored before Story 1.13 has no startedBeforeUnlock; its start row may still know.
                    directBoot = session.startedBeforeUnlock || stored?.directBoot == true,
                ),
            )
        }

    private fun rowOf(
        session: SessionData,
        firstRingAt: Instant,
        endedAt: Instant?,
        outcome: SessionOutcome?,
    ): SessionHistoryRow =
        SessionHistoryRow(
            sessionId = session.sessionId,
            alarmId = session.config.alarmId,
            scheduledAt = session.config.scheduledAt,
            firstRingAt = firstRingAt,
            endedAt = endedAt,
            snoozeCount = session.snoozesGranted,
            checkTypes =
                session.checkRun.plan.steps
                    .map { it.typeName },
            timeToCompleteMs = null,
            fallbackUsed = session.checkRun.fallbackUsed,
            directBoot = session.startedBeforeUnlock,
            outcome = outcome,
        )

    companion object {
        /** Test for a test alarm whatever the ending; else OnTime with no snooze, Snoozed after one or more, or Missed. */
        fun outcomeOf(
            session: SessionData,
            end: SessionEnd,
        ): SessionOutcome =
            when {
                session.config.testMode -> SessionOutcome.Test
                end == SessionEnd.Missed -> SessionOutcome.Missed
                session.snoozesGranted == 0 -> SessionOutcome.OnTime
                else -> SessionOutcome.Snoozed
            }
    }
}
