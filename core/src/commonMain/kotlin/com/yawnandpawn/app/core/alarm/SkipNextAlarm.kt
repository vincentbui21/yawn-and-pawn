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

    /**
     * Nothing to skip: the alarm is off (a skipped one-time alarm is off too), or its next occurrence is no longer the one
     * the user was asked about.
     */
    NothingToSkip,
}

/**
 * Skips one alarm's next occurrence (Story 7.11, FR-ALM-10; since 0.1.8 for each alarm of the day through the Skip
 * reminder's "Skip today's alarms", `SkipTodaysAlarms`): the alarm's next occurrence ([nextOccurrence] after now) does
 * not ring. Free, never charged, and allowed inside the commitment lock window: the Skipped row is the log.
 *
 * - A repeating alarm stores that occurrence's local date ([Alarm.skippedDate], in the current zone), so it is armed for
 *   the following one ([nextRing]); a one-time alarm is switched off instead (its row stays as the log).
 * - [SessionRecorder.recordSkip] writes one Skipped row (a new session id, `scheduled_at` the occurrence, no first ring)
 *   before the alarm is stored: a failed row write changes nothing; a failed alarm write removes the row again (a
 *   failure of that is logged), so nothing is half-applied. Then the system alarm and the Skip reminder follow
 *   ([AlarmScheduling.sync]).
 * - Idempotent: an occurrence already skipped is [SkipResult.AlreadySkipped], with no second row.
 * - Any enabled alarm (0.1.8: Story 7.11's per-alarm "Offer to skip" that gated it is gone). With [expected] (the
 *   occurrence the reminder was about), a skip confirmed after that occurrence passed skips nothing rather than the
 *   following one.
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
    suspend operator fun invoke(
        alarmId: String,
        expected: Instant? = null,
    ): Outcome<SkipResult, DomainError> =
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

                            expected != null && expected != occurrence -> {
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
 * cleared and the alarm is armed for that occurrence again, the Skip reminder following ([AlarmScheduling.sync]); the
 * reminder's "Today's alarms are skipped." confirmation goes ([AlarmScheduling.skipUndone], 0.1.8). A failed
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
                                            scheduling.skipUndone()
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

    /** Removes [rows]; when one removal fails, the rows already removed are put back (review fix), so nothing is half-done. */
    private suspend fun discardAll(rows: List<SessionHistoryRow>): Outcome<Unit, DomainError> {
        rows.forEachIndexed { index, row ->
            val removed = recorder.discardSkip(row.alarmId, row.scheduledAt)
            if (removed is Outcome.Failure) {
                restoreAll(rows.take(index))
                return removed
            }
        }
        return Outcome.Success(Unit)
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

/**
 * Story 7.11 review fix: removes the Skipped rows an alarm's stored state no longer skips, so a morning that will ring is
 * never logged as Skipped. Runs after [SaveAlarm] (an edit, or an identical new alarm switching a stored one on) or
 * [SetAlarmEnabled] stored `stored` (from [before]), only when the
 * alarm is on and either was off (a skipped one-time alarm switched back on) or its skipped date changed (an edit to
 * one-time drops it). A row goes when it is still to come and its local date (this zone) is not [Alarm.skippedDate].
 * Rows of an alarm that stays off are its log and stay. Best effort: the alarm is already stored, so a failure is logged.
 */
class DiscardStaleSkips(
    private val history: SessionHistoryRepository,
    private val recorder: SessionRecorder,
    private val zones: TimeZoneProvider,
    private val logger: Logger,
) {
    suspend fun after(
        before: Alarm,
        stored: Alarm,
        now: Instant,
    ) {
        if (!stored.enabled || (before.enabled && before.skippedDate == stored.skippedDate)) return
        val zone = zones.current()
        val rows =
            when (val all = history.all()) {
                is Outcome.Success -> {
                    all.value.filter {
                        it.alarmId == stored.id &&
                            it.outcome == SessionOutcome.Skipped &&
                            it.scheduledAt > now &&
                            it.scheduledAt.toLocalDateTime(zone).date != stored.skippedDate
                    }
                }

                is Outcome.Failure -> {
                    logger.log(LogEvent.OperationFailed.of(STALE_ROWS, all.error))
                    return
                }
            }
        rows.forEach { row ->
            val removed = recorder.discardSkip(row.alarmId, row.scheduledAt)
            if (removed is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(STALE_ROWS, removed.error))
        }
    }

    private companion object {
        const val STALE_ROWS = "remove skipped rows the alarm no longer skips"
    }
}
