package com.yawnandpawn.app.core.alarm

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
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

/**
 * The next ring of [alarm] strictly after [now] in [zone] (Story 7.11): its [nextOccurrence], unless that occurrence's
 * local date in [zone] is the alarm's [Alarm.skippedDate], in which case the occurrence after it. Scheduling, the Home
 * countdown and the editor header all use it.
 *
 * - The skipped date is a local date, so after a time-zone change the occurrence on that date in the new zone is still
 *   skipped.
 * - DST: a gap shifts the occurrence forward on the same date, and an overlap only has its earlier instance, so the
 *   occurrence after a skipped one is always on a later date ([nextOccurrence] after it).
 * - A past skipped date never matches, so it needs no clearing.
 */
fun nextRing(
    alarm: Alarm,
    now: Instant,
    zone: TimeZone,
): Instant = nextRing(alarm.toRule(), alarm.skippedDate, now, zone)

/** [nextRing] for a [rule] whose occurrence on [skippedDate] (if any) is skipped: the editor's header for its form. */
fun nextRing(
    rule: AlarmRule,
    skippedDate: LocalDate?,
    now: Instant,
    zone: TimeZone,
): Instant {
    val first = nextOccurrence(rule, now, zone)
    val skipped = skippedDate ?: return first
    return if (first.toLocalDateTime(zone).date == skipped) nextOccurrence(rule, first, zone) else first
}

/**
 * The enabled [alarm]'s next occurrence after [now] (in [zone]) is the skipped one (Story 7.11): Home's card says "Next
 * ring skipped" and offers "Don't skip" only then.
 */
fun Alarm.isNextOccurrenceSkipped(
    now: Instant,
    zone: TimeZone,
): Boolean {
    val skipped = skippedDate ?: return false
    return enabled && nextOccurrence(toRule(), now, zone).toLocalDateTime(zone).date == skipped
}

/** Time from [now] until [occurrence], never negative. The single source for the Home countdown (Story 1.9). */
fun durationUntil(
    occurrence: Instant,
    now: Instant,
): Duration = (occurrence - now).coerceAtLeast(Duration.ZERO)
