package com.yawnandpawn.app.ui.daydetail

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.config.CommitmentAction
import com.yawnandpawn.app.core.config.CommitmentEvent
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.stats.SessionOnDay
import com.yawnandpawn.app.core.stats.wholeMinutes
import com.yawnandpawn.app.ui.checks.checkTypeOfId
import com.yawnandpawn.app.ui.progress.Outcome
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/*
 * Story 6.7: Day detail from the stored facts only (Epic 6 owner question Q1, owner decision 2026-10-26, round 5
 * `day-facts-*`). Pure: session history rows, their purchase records (6.2's sessionsOn), their merges, the commitment
 * events and the alarms, mapped for one local date. Nothing is invented: a fact history does not hold is left out.
 */

/**
 * Day detail of [date]: one [SessionDetail] per finished session of [sessions] (6.2's `sessionsOn` for [date], already
 * in `scheduledAt` order), then the alarm changes logged for [date]. [merges] are by session id; [alarms] by alarm id
 * (a deleted alarm is missing, so its sessions show the time only).
 */
internal fun dayDetailOf(
    date: LocalDate,
    sessions: List<SessionOnDay>,
    merges: Map<String, List<SessionMergeRow>>,
    events: List<CommitmentEvent>,
    alarms: Map<String, Alarm>,
    zone: TimeZone,
): DayDetailUiState {
    val details =
        sessions.mapNotNull { session ->
            sessionDetailOf(session, merges[session.row.sessionId].orEmpty(), alarms[session.row.alarmId], zone)
        }
    val changes = alarmChangesOn(events, date, zone)
    return DayDetailUiState(
        date = date,
        sessions = details,
        changes = changes,
        nothingLogged = details.isEmpty() && changes.isEmpty(),
    )
}

/**
 * One session: its alarm time (the local time of `scheduledAt`) with the alarm's current label while it has one (not
 * for a test or skipped session), the outcome, and for a counted session (On time, Snoozed, Missed) the stored tiles (rings 1 + `snoozeCount`), the money
 * paid per currency, the payments Google refunded, the time to get up and the timeline ([morningEventsOf]). Test and
 * skipped sessions get the hero data only. `null` for a session still running (no outcome).
 */
internal fun sessionDetailOf(
    session: SessionOnDay,
    merges: List<SessionMergeRow>,
    alarm: Alarm?,
    zone: TimeZone,
): SessionDetail? {
    val row = session.row
    val outcome = row.outcome?.toUi() ?: return null
    val alarmTime = row.scheduledAt.localTime(zone)
    val label = alarm?.label?.trim()?.takeIf { it.isNotEmpty() }
    if (outcome == Outcome.Test || outcome == Outcome.Skipped) {
        // AC 4: the marker, the outcome word and the alarm time only.
        return SessionDetail(alarmTime, null, outcome, events = emptyList(), snoozes = 0, rings = 0, paid = emptyList(), minutesToUp = null)
    }
    val timed = outcome == Outcome.OnTime || outcome == Outcome.Snoozed
    return SessionDetail(
        alarmTime = alarmTime,
        alarmLabel = label,
        outcome = outcome,
        events = morningEventsOf(row, session.purchases, merges, zone),
        snoozes = row.snoozeCount,
        rings = 1 + row.snoozeCount,
        paid = session.paid,
        minutesToUp = row.timeToCompleteMs?.takeIf { timed }?.let { wholeMinutes(it.milliseconds) },
        stranded = session.stranded.map { record -> record.price.takeIf { record.priceSource.isAmountPaid } },
    )
}

/**
 * The timeline of a counted session, stored facts only, in time order:
 * - "Alarm rang" at `firstRingAt` ("Rang before your first unlock" with Direct Boot);
 * - one snooze per counted purchase record of the session ([paidRecords]: Granted, Consumed or Reused), at its purchase
 *   time, or with no time when it was bought outside this session's span (a reused payment); its price only when it is
 *   what was charged;
 * - one row per merged alarm, at `mergedAt`;
 * - the fallback check, with no time (history keeps none), just before the end;
 * - at `endedAt`: "Stopped after 30 minutes" for a missed session, otherwise the session's checks solved. Left out
 *   without an end time, or when no check id is known.
 */
internal fun morningEventsOf(
    row: SessionHistoryRow,
    paidRecords: List<PurchaseRecord>,
    merges: List<SessionMergeRow>,
    zone: TimeZone,
): List<MorningEvent> {
    val start = row.firstRingAt
    val end = row.endedAt ?: (start + UNENDED_SPAN)
    val snoozes =
        paidRecords
            .sortedWith(compareBy(nullsLast()) { it: PurchaseRecord -> it.snoozeNumber }.thenBy { it.purchasedAt })
            .map { record ->
                val inSession = record.purchasedAt in start..end
                // A payment bought outside this session's span (reused from an earlier session) sits right after the
                // first ring, in its snooze-number place, with no time rather than a wrong one.
                val at = if (inSession) record.purchasedAt else start
                val event =
                    MorningEvent.Snoozed(
                        time = record.purchasedAt.takeIf { inSession }?.localTime(zone),
                        minutes = null,
                        price = record.price.takeIf { record.priceSource.isAmountPaid },
                    )
                at to event
            }
    val merged = merges.map { merge -> merge.mergedAt to MorningEvent.Merged(merge.mergedAt.localTime(zone), merge.scheduledAt.localTime(zone)) }
    val checks = row.checkTypes.mapNotNull(::checkTypeOfId)
    return buildList {
        add(MorningEvent.Rang(start.localTime(zone), beforeFirstUnlock = row.directBoot))
        // Stable: equal instants keep snoozes before merges, and snoozes in their snooze-number order.
        addAll((snoozes + merged).sortedBy { it.first }.map { it.second })
        if (row.fallbackUsed) checks.firstOrNull()?.let { add(MorningEvent.FallbackUsed(time = null, check = it)) }
        row.endedAt?.let { ended ->
            when {
                row.outcome == SessionOutcome.Missed -> add(MorningEvent.Stopped(ended.localTime(zone)))
                checks.isNotEmpty() -> add(MorningEvent.CheckSolved(ended.localTime(zone), checks))
                else -> Unit
            }
        }
    }
}

/**
 * The alarm changes logged for [date]: each commitment event whose `occurrenceAt` falls on [date] in [zone], in
 * `occurrenceAt` order, as "Your {time} alarm was turned off" or "deleted".
 */
internal fun alarmChangesOn(
    events: List<CommitmentEvent>,
    date: LocalDate,
    zone: TimeZone,
): List<AlarmChange> =
    events
        .filter { it.occurrenceAt.toLocalDateTime(zone).date == date }
        .sortedBy { it.occurrenceAt }
        .map { AlarmChange(it.occurrenceAt.localTime(zone), deleted = it.action == CommitmentAction.Deleted) }

private fun SessionOutcome.toUi(): Outcome =
    when (this) {
        SessionOutcome.OnTime -> Outcome.OnTime
        SessionOutcome.Snoozed -> Outcome.Snoozed
        SessionOutcome.Missed -> Outcome.Missed
        SessionOutcome.Skipped -> Outcome.Skipped
        SessionOutcome.Test -> Outcome.Test
    }

private fun Instant.localTime(zone: TimeZone): LocalTime = toLocalDateTime(zone).time

/** With no stored end, a snooze bought within this long of the first ring still belongs to the session's timeline. */
private val UNENDED_SPAN = 30.minutes
