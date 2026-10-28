package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionRecorder
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What [SkipNextAlarm] did. */
enum class SkipResult {
    /** The next occurrence is skipped now, with its Skipped row. */
    Skipped,

    /** That occurrence was skipped already: nothing changed and no second row was written. */
    AlreadySkipped,

    /** The alarm is off (a skipped one-time alarm is off too): nothing to skip. */
    NothingToSkip,
}

/**
 * "Skip this one" confirmed (Story 7.11, FR-ALM-10): the alarm's next occurrence ([nextOccurrence] after now) does not
 * ring. Free, never charged, and allowed inside the commitment lock window: the confirmation dialog and the Skipped row
 * are the log.
 *
 * - A repeating alarm stores that occurrence's local date ([Alarm.skippedDate], in the current zone), so it is armed for
 *   the following one ([nextRing]); a one-time alarm is switched off instead (its row stays as the log).
 * - [SessionRecorder.recordSkip] writes one Skipped row (a new session id, `scheduled_at` the occurrence, no first ring)
 *   before the alarm is stored: a failed row write changes nothing; a failed alarm write removes the row again (a
 *   failure of that is logged), so nothing is half-applied. Then the system alarm and the pre-alarm job follow
 *   ([AlarmScheduling.sync]).
 * - Idempotent: an occurrence already skipped is [SkipResult.AlreadySkipped], with no second row.
 * - Inside [SessionLockGuard.whenIdle] (`SessionActive` during a session: nothing is skipped) and the alarm write lock;
 *   it runs to its end once started, like the other alarm writes.
 */
class SkipNextAlarm(
    private val repository: AlarmRepository,
    private val recorder: SessionRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zones: TimeZoneProvider,
    private val lock: AlarmWriteLock,
    private val scheduling: AlarmScheduling,
    private val sessionLock: SessionLockGuard,
    private val logger: Logger,
) {
    suspend operator fun invoke(alarmId: String): Outcome<SkipResult, DomainError> =
        lock.withLock {
            sessionLock.whenIdle {
                withContext(NonCancellable) {
                    repository.get(alarmId).flatMap { stored ->
                        val now = clock.nowMillis()
                        val zone = zones.current()
                        val occurrence = nextOccurrence(stored.toRule(), now, zone)
                        val date = occurrence.toLocalDateTime(zone).date
                        when {
                            !stored.enabled -> {
                                Outcome.Success(SkipResult.NothingToSkip)
                            }

                            stored.skippedDate == date -> {
                                Outcome.Success(SkipResult.AlreadySkipped)
                            }

                            else -> {
                                val skipped =
                                    if (stored.toRule().isOneTime) {
                                        stored.copy(enabled = false, updatedAt = now)
                                    } else {
                                        stored.copy(skippedDate = date, updatedAt = now)
                                    }
                                recorder.recordSkip(ids.newId(), stored.id, occurrence, now).flatMap {
                                    when (val written = repository.upsert(skipped)) {
                                        is Outcome.Success -> {
                                            scheduling.sync(skipped)
                                            Outcome.Success(SkipResult.Skipped)
                                        }

                                        is Outcome.Failure -> {
                                            val undone = recorder.discardSkip(stored.id, occurrence)
                                            if (undone is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(UNDO_ROW, undone.error))
                                            written
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    private companion object {
        const val UNDO_ROW = "remove skipped row after a failed skip"
    }
}

/**
 * "Don't skip" on Home's card (Story 7.11): undoes [SkipNextAlarm] while the skipped occurrence is still the alarm's next
 * one. It strengthens, so it is allowed whenever no session runs ([SessionLockGuard.whenIdle]).
 *
 * The skip's Skipped row is removed first ([SessionRecorder.discardSkip]; the rows of this alarm still to come, or on
 * the skipped date in the current zone, so an edited time or a zone change still finds it), then the skipped date is
 * cleared and the alarm and its pre-alarm job are armed for that occurrence again ([AlarmScheduling.sync]). A failed
 * row removal changes nothing; a failed alarm write puts the rows back (a failure of that is logged). True when a skip
 * was undone; false when there was none to undo (no skipped date, the alarm off, or the skipped day already passed).
 */
class UndoSkip(
    private val repository: AlarmRepository,
    private val history: SessionHistoryRepository,
    private val recorder: SessionRecorder,
    private val clock: Clock,
    private val zones: TimeZoneProvider,
    private val lock: AlarmWriteLock,
    private val scheduling: AlarmScheduling,
    private val sessionLock: SessionLockGuard,
    private val logger: Logger,
) {
    suspend operator fun invoke(alarmId: String): Outcome<Boolean, DomainError> =
        lock.withLock {
            sessionLock.whenIdle {
                withContext(NonCancellable) {
                    repository.get(alarmId).flatMap { stored ->
                        val now = clock.nowMillis()
                        val zone = zones.current()
                        if (!stored.isNextOccurrenceSkipped(now, zone)) {
                            Outcome.Success(false)
                        } else {
                            history.all().flatMap { all ->
                                val rows = all.filter { it.isSkipOf(stored, now) }
                                discardAll(rows).flatMap {
                                    val restored = stored.copy(skippedDate = null, updatedAt = now)
                                    when (val written = repository.upsert(restored)) {
                                        is Outcome.Success -> {
                                            scheduling.sync(restored)
                                            Outcome.Success(true)
                                        }

                                        is Outcome.Failure -> {
                                            restoreAll(rows)
                                            written
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    /** A Skipped row of [alarm] for its skipped occurrence: still to come at [now], or on its skipped date in this zone. */
    private fun SessionHistoryRow.isSkipOf(
        alarm: Alarm,
        now: Instant,
    ): Boolean =
        alarmId == alarm.id &&
            outcome == SessionOutcome.Skipped &&
            (scheduledAt > now || scheduledAt.toLocalDateTime(zones.current()).date == alarm.skippedDate)

    private suspend fun discardAll(rows: List<SessionHistoryRow>): Outcome<Unit, DomainError> =
        rows.fold<SessionHistoryRow, Outcome<Unit, DomainError>>(Outcome.Success(Unit)) { done, row ->
            done.flatMap { recorder.discardSkip(row.alarmId, row.scheduledAt) }
        }

    /** Writes [rows] back after the alarm could not be stored; a failure is logged. */
    private suspend fun restoreAll(rows: List<SessionHistoryRow>) {
        rows.forEach { row ->
            val back = recorder.recordSkip(row.sessionId, row.alarmId, row.scheduledAt, row.endedAt ?: row.scheduledAt)
            if (back is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(RESTORE_ROW, back.error))
        }
    }

    private companion object {
        const val RESTORE_ROW = "restore skipped row after a failed undo"
    }
}
