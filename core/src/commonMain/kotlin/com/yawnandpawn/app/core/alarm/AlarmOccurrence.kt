package com.yawnandpawn.app.core.alarm

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/** A weekly rule always matches within 7 days of today; day 7 covers "today, but the time has passed". */
private const val DAYS_TO_SCAN = 7

/**
 * The first instant strictly after [now] whose local date in [zone] rings under [rule], at the rule time
 * (FR-ALM-5, FR-ALM-9). Scheduler and Home countdown both use this function.
 *
 * - DST gap (the rule time does not exist that day): shifted forward by the gap length, e.g. 02:30 becomes 03:30.
 * - DST overlap (the rule time exists twice): only the earlier instance; the later one is never produced, so the
 *   next call after the earlier instance returns the following ringing day.
 * - A zone change needs no special handling: call again with the new zone.
 */
fun nextOccurrence(
    rule: AlarmRule,
    now: Instant,
    zone: TimeZone,
): Instant {
    val today = now.toLocalDateTime(zone).date
    return (0..DAYS_TO_SCAN)
        .asSequence()
        .map { offset -> today.plus(DatePeriod(days = offset)) }
        .filter { date -> rule.ringsOn(date.dayOfWeek) }
        // kotlinx-datetime resolves a gap forward by its length and an overlap to the earlier offset.
        .map { date -> LocalDateTime(date, rule.time).toInstant(zone) }
        .first { candidate -> candidate > now }
}

/** Time from [now] until [occurrence], never negative. The single source for the Home countdown (Story 1.9). */
fun durationUntil(
    occurrence: Instant,
    now: Instant,
): Duration = (occurrence - now).coerceAtLeast(Duration.ZERO)
