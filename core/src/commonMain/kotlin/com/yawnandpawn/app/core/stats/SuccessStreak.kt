package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** What the zero-snooze Success screen shows (Story 6.4, FR-MSG-3). */
sealed interface SuccessStreak {
    /** The streak [before] and [after] this session, from a loaded snapshot. [after] 0: "Up on time." only. */
    data class Known(
        val before: Int,
        val after: Int,
    ) : SuccessStreak {
        /** The streak grew with this session: only then the count-up and the confetti play. */
        val grew: Boolean get() = after > before
    }

    /** The snapshot was still loading: "Up on time." only, no number (never a wrong one). */
    data object Pending : SuccessStreak

    /** No snapshot (the read failed, or it was never requested in this process): "You're up. That's what counts." */
    data object Unknown : SuccessStreak

    companion object {
        /** No streak to show: "Up on time." only (the paid and test paths, and every caller before Epic 6). */
        val NONE = Known(before = 0, after = 0)
    }
}

/**
 * The streak around a completed zero-snooze session [sessionId] (Story 6.4): [currentStreak] of [snapshot] without any
 * row of [sessionId] (its start row or an early end row), then of the same rows plus this session as OnTime (built from
 * [alarmId] and [scheduledAt], so its day is the local date of [scheduledAt]), both with the same [today] and [zone]
 * (Story 6.1). Pure.
 */
@Suppress("LongParameterList") // The session's three facts and the day context, each needed once.
fun successStreak(
    snapshot: List<SessionHistoryRow>,
    sessionId: String,
    alarmId: String,
    scheduledAt: Instant,
    today: LocalDate,
    zone: TimeZone,
): SuccessStreak.Known {
    val others = snapshot.filterNot { it.sessionId == sessionId }
    val self =
        SessionHistoryRow(
            sessionId = sessionId,
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            firstRingAt = scheduledAt,
            endedAt = scheduledAt,
            snoozeCount = 0,
            checkTypes = emptyList(),
            timeToCompleteMs = null,
            fallbackUsed = false,
            directBoot = false,
            outcome = SessionOutcome.OnTime,
        )
    return SuccessStreak.Known(
        before = currentStreak(others, today, zone),
        after = currentStreak(others + self, today, zone),
    )
}
