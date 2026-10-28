package com.yawnandpawn.app.core.history

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** How a session ended, as Progress (Epic 6) shows it. Stored as a stable string by the `app.db` adapter. */
enum class SessionOutcome {
    /** The check was passed without a paid snooze. */
    OnTime,

    /** The check was passed after one or more paid snoozes. */
    Snoozed,

    /** Nobody interacted for 30 minutes (FR-ALM-9). */
    Missed,

    /** The occurrence was skipped ahead of time ("Skip this one", Story 7.11); `SessionRecorder.recordSkip` writes it. */
    Skipped,

    /** A test alarm (FR-ALM-12), whatever its ending. */
    Test,
}

/**
 * One row of session history (AD-18): what Progress (Epic 6) reads. Written only by `SessionRecorder`, upserted by
 * [sessionId]. Paid amounts are not here; they come from purchase records keyed by [sessionId] (AD-7, AD-8).
 *
 * @property scheduledAt the occurrence the session rang for.
 * @property firstRingAt when the session's first ring started (wall time); null for a [SessionOutcome.Skipped] occurrence,
 *   which never rang (Story 7.11).
 * @property endedAt when the session ended (wall time; for a skipped occurrence, when it was skipped); null while it runs.
 * @property snoozeCount paid snoozes granted.
 * @property checkTypes the stable type names of the check steps (`CheckType.id`), in order.
 * @property timeToCompleteMs from the first ring to the end, for a completed session; null otherwise.
 * @property fallbackUsed the fallback check replaced the plan (FR-PWK-11).
 * @property fallbackFrom the id (`CheckType.id`) of the check the fallback replaced (Story 3.9); null without a fallback.
 * @property directBoot the session started before the first unlock after a boot.
 * @property outcome how it ended; null while it runs.
 */
data class SessionHistoryRow(
    val sessionId: String,
    val alarmId: String,
    val scheduledAt: Instant,
    val firstRingAt: Instant?,
    val endedAt: Instant?,
    val snoozeCount: Int,
    val checkTypes: List<String>,
    val timeToCompleteMs: Long?,
    val fallbackUsed: Boolean,
    val directBoot: Boolean,
    val outcome: SessionOutcome?,
    val fallbackFrom: String? = null,
)

/**
 * Port for session history (`session_history` in `app.db`). `SessionRecorder` is its only writer (AD-18; a writer
 * scan test enforces it). Storage errors come back as `StorageFailure`, never thrown.
 */
interface SessionHistoryRepository {
    /** Inserts [row], or replaces the row with the same session id: writing the same row twice leaves one row. */
    suspend fun upsert(row: SessionHistoryRow): Outcome<Unit, DomainError>

    /** The row of [sessionId], or null when there is none. */
    suspend fun find(sessionId: String): Outcome<SessionHistoryRow?, DomainError>

    /**
     * The [SessionOutcome.Missed] row that ended last, or null; emits again after every change (Home's missed note,
     * Story 1.16). A read only. A storage failure is thrown into the flow; the collector catches it.
     */
    fun observeLatestMissed(): Flow<SessionHistoryRow?>

    /**
     * Every row, oldest `scheduled_at` first (then session id), again after every change (Progress and Home, Epic 6).
     * A read only. A storage failure or an unreadable row is thrown into the flow; the collector catches it.
     */
    fun observeAll(): Flow<List<SessionHistoryRow>>

    /**
     * Records that the occurrence of [SessionMergeRow.alarmId] at [SessionMergeRow.scheduledAt] joined session
     * [SessionMergeRow.sessionId] (FR-SES-7, Story 2.9). Insert or ignore on those three: recording the same merge twice
     * (a replay after a crash) leaves one row with the first `mergedAt`.
     */
    suspend fun recordMerge(merge: SessionMergeRow): Outcome<Unit, DomainError>

    /** The merges into [sessionId], oldest first (Day detail, Epic 6). */
    suspend fun merges(sessionId: String): Outcome<List<SessionMergeRow>, DomainError>

    /**
     * Every row, in no particular order (Epic 6 stats, one read: the Story 6.4 celebration snapshot). A read only; a
     * storage error is a `StorageFailure`, never thrown.
     */
    suspend fun all(): Outcome<List<SessionHistoryRow>, DomainError>

    /**
     * Deletes the [SessionOutcome.Skipped] row of the alarm [alarmId] for the occurrence at [scheduledAt] ("Don't skip",
     * Story 7.11); no such row is a success. A write: only `SessionRecorder` calls it.
     */
    suspend fun deleteSkipped(
        alarmId: String,
        scheduledAt: Instant,
    ): Outcome<Unit, DomainError>
}

/**
 * One row of `session_merge` (Story 2.9): an alarm occurrence that rang during session [sessionId] and joined it
 * instead of starting its own ("{time} alarm merged into this session", Epic 6). [mergedAt] is when it joined.
 */
data class SessionMergeRow(
    val sessionId: String,
    val alarmId: String,
    val scheduledAt: Instant,
    val mergedAt: Instant,
)
