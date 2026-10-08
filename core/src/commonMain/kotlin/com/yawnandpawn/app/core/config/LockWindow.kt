package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.nextOccurrence
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** One ring of one alarm: the alarm [alarmId] at [scheduledAt] (an instant, never a local time). */
data class Occurrence(
    val alarmId: String,
    val scheduledAt: Instant,
)

/**
 * The commitment lock window (PRD §6.2, AD-16): an enabled alarm is "in the window" when 0 < (its next occurrence − now)
 * ≤ [LENGTH], computed on instants with Story 1.6's [nextOccurrence], so a DST night counts real hours, not wall-clock
 * ones. A disabled alarm never locks.
 */
object LockWindow {
    /** 8 hours (PRD Q4). */
    val LENGTH: Duration = 8.hours

    /** True when [occurrence] is after [now] by at most [LENGTH]. */
    fun contains(
        occurrence: Instant,
        now: Instant,
    ): Boolean {
        val until = occurrence - now
        return until > Duration.ZERO && until <= LENGTH
    }

    /** The next occurrence of [alarm] after [now], when it is enabled and inside the window; otherwise null. */
    fun of(
        alarm: Alarm,
        now: Instant,
        zone: TimeZone,
    ): Occurrence? {
        if (!alarm.enabled) return null
        val next = nextOccurrence(alarm.toRule(), now, zone)
        return Occurrence(alarm.id, next).takeIf { contains(next, now) }
    }

    /**
     * For a global setting: the window applies when any enabled alarm is in its window, and the change waits for the
     * latest such occurrence (owner-approved default 2026-09-26). Ties go to the greater alarm id, so the result never
     * depends on the list order. Null when no alarm is in its window.
     */
    fun latest(
        alarms: List<Alarm>,
        now: Instant,
        zone: TimeZone,
    ): Occurrence? = latestOf(alarms.mapNotNull { of(it, now, zone) })

    /** The latest of [occurrences] (ties: the greater alarm id), or null for none. */
    fun latestOf(occurrences: List<Occurrence>): Occurrence? =
        occurrences.maxWithOrNull(compareBy<Occurrence> { it.scheduledAt }.thenBy { it.alarmId })
}
