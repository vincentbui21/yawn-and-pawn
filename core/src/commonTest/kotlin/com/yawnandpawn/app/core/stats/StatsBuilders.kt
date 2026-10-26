package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The zone the stats tests use unless a test is about zones. */
internal val StatsZone: TimeZone = TimeZone.of("Europe/Berlin")

private var sessionNumber = 0

/**
 * A history row scheduled on [day] at [at] local time in [zone] (`:core` tests may not use `:testing`, so this stands in
 * for its builders). A null [outcome] is a session still running: no end and no time to complete.
 */
internal fun aSession(
    day: LocalDate,
    outcome: SessionOutcome? = SessionOutcome.OnTime,
    at: LocalTime = LocalTime(6, 30),
    zone: TimeZone = StatsZone,
    timeToUp: Duration? = 3.minutes,
): SessionHistoryRow {
    val scheduledAt = LocalDateTime(day, at).toInstant(zone)
    val running = outcome == null
    return SessionHistoryRow(
        sessionId = "s${++sessionNumber}",
        alarmId = "a",
        scheduledAt = scheduledAt,
        firstRingAt = scheduledAt,
        endedAt = if (running) null else scheduledAt + (timeToUp ?: 30.minutes),
        snoozeCount = if (outcome == SessionOutcome.Snoozed) 1 else 0,
        checkTypes = listOf("Math"),
        timeToCompleteMs = if (running) null else timeToUp?.inWholeMilliseconds,
        fallbackUsed = false,
        directBoot = false,
        outcome = outcome,
    )
}

/** Days counted from [this]: `day(2)` is two days later, `day(-1)` the day before. */
internal fun LocalDate.day(offset: Int): LocalDate = plus(offset, DateTimeUnit.DAY)
