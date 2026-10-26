package com.yawnandpawn.app.ui.progress

import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.aSessionHistoryRow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Story 6.5 test data: mornings at 07:00 UTC around [FixtureToday], the fake clock's Wednesday 2027-03-03. */
internal val FixtureToday: LocalDate = DEFAULT_FAKE_INSTANT.toLocalDateTime(TimeZone.UTC).date

/** [offset] days from [FixtureToday] (negative: back). */
internal fun day(offset: Int): LocalDate = FixtureToday.plus(offset, DateTimeUnit.DAY)

/** A finished 07:00 UTC session on [date] with [outcome], [snoozes] paid snoozes and [millisToUp] to get up. */
internal fun morning(
    date: LocalDate,
    outcome: SessionOutcome = SessionOutcome.OnTime,
    snoozes: Int = 0,
    millisToUp: Long? = THREE_MINUTES_MS,
    id: String = "session-$date-$outcome",
): SessionHistoryRow {
    val at = date.atTime(7, 0).toInstant(TimeZone.UTC)
    return aSessionHistoryRow(sessionId = id).copy(
        scheduledAt = at,
        firstRingAt = at,
        endedAt = at,
        snoozeCount = snoozes,
        timeToCompleteMs = millisToUp,
        outcome = outcome,
    )
}

/**
 * EXPERIENCE.md F9's typical month, ending today: 12 on-time mornings in a row (today included), a snoozed morning
 * before them (1 snooze), a test alarm, then another snoozed morning (3 snoozes). Streak 12, best 12, 12 of 14 on time
 * (86%), 3 min to get up, 4 snoozes.
 */
internal fun typicalMonth(): List<SessionHistoryRow> =
    (0 downTo -11).map { morning(day(it)) } +
        listOf(
            morning(day(-12), SessionOutcome.Snoozed, snoozes = 1),
            morning(day(-13), SessionOutcome.Test, millisToUp = null),
            morning(day(-14), SessionOutcome.Snoozed, snoozes = 3),
        )

internal const val THREE_MINUTES_MS = 180_000L
