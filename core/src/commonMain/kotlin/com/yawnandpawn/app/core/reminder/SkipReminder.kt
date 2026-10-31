package com.yawnandpawn.app.core.reminder

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.nextOccurrence
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The Skip reminder's rule (0.1.8, FR-ALM-10, owner decision 2026-10-31): one global switch in Settings replaces Story
 * 7.11's per-alarm "Offer to skip 2 h before". While it is on, each local date gets at most one notification "Up
 * already?", [LEAD] before its first ring, offering to skip that day's alarms; a reminder that would come with [MIN_LEFT]
 * or less left is never posted.
 */
object SkipReminder {
    /** How long before a date's first ring the reminder is posted. */
    val LEAD: Duration = 2.hours

    /** A reminder is only posted while more than this is left before the ring (a new alarm, a late job). */
    val MIN_LEFT: Duration = 15.minutes

    /** How many days ahead a first ring is looked for: a weekly rule always rings within 7 days of today. */
    internal const val DAYS_AHEAD = 7
}

/**
 * The date a reminder is for: [date]'s first ring is at [firstRing] (the local clock time [time] in the zone it was
 * computed in; a DST gap shifts it like the ring itself).
 */
data class SkipReminderTarget(
    val date: LocalDate,
    val firstRing: Instant,
    val time: LocalTime,
)

/**
 * The Skip reminder's stored state (0.1.8): the Settings switch ([enabled], on by default) and the date reminders are
 * handled through ([lastRemindedDate]: posted, due while one could not be posted, or come with too little time left), so
 * a date never gets two and never comes back. Nothing else is stored.
 */
data class SkipReminderPrefs(
    val enabled: Boolean = true,
    val lastRemindedDate: LocalDate? = null,
)

/**
 * Port for [SkipReminderPrefs], kept in the device-protected settings DataStore by `:data` (so "Delete all data" clears
 * it and a backup carries it). Storage errors come back as `StorageFailure`, never thrown.
 */
interface SkipReminderStore {
    /** Emits on every change; an unreadable store is logged and reads as the defaults (on, no date reminded). */
    fun observe(): Flow<SkipReminderPrefs>

    /** One read; a store that cannot be read is a `StorageFailure` (nothing is posted or scheduled then). */
    suspend fun read(): Outcome<SkipReminderPrefs, DomainError>

    suspend fun setEnabled(on: Boolean): Outcome<Unit, DomainError>

    suspend fun setLastRemindedDate(date: LocalDate): Outcome<Unit, DomainError>
}

/**
 * Port for the Skip reminder notification (0.1.8): channel "Upcoming alarms"; the reminder and its confirmation are two
 * notifications of their own (a post replaces the one of its kind). Never requests the notification permission; failures
 * are values, never thrown.
 */
interface SkipReminderNotifier {
    /** `POST_NOTIFICATIONS` is granted and the app's notifications are enabled. */
    fun canPost(): Boolean

    /**
     * Posts "Up already?" / "Your {time} alarm rings in {h} h {m} min. Skip today's alarms?" for [target], with the actions
     * "Skip today's alarms" and "Keep"; the first ring is [ringsIn] away. With [tomorrow] (the target date is not the
     * local date at post time: a ring just after midnight, posted the evening before) it says "tomorrow's" instead.
     */
    fun post(
        target: SkipReminderTarget,
        ringsIn: Duration,
        tomorrow: Boolean = false,
    ): Outcome<Unit, DomainError>

    /**
     * Replaces the reminder with "Today's alarms are skipped. No charge." ("Tomorrow's" with [tomorrow]), gone by itself
     * after [dismissIn].
     */
    fun showSkipped(
        dismissIn: Duration,
        tomorrow: Boolean = false,
    ): Outcome<Unit, DomainError>

    /** Removes the confirmation only (a skip was undone); the reminder, if one shows, stays. None is not an error. */
    fun cancelSkipped(): Outcome<Unit, DomainError>

    /** Removes Story 7.11's pre-alarm notification of the alarm with [requestCode] (update cleanup); none is not an error. */
    fun cancelLegacy(requestCode: Int): Outcome<Unit, DomainError>

    /** Removes the reminder and its confirmation, if posted; none is not an error. */
    fun cancel(): Outcome<Unit, DomainError>
}

/**
 * What [skipReminderPlan] found: [target] is the next date to remind (null: none in the next week), and the dates before
 * it that come with [SkipReminder.MIN_LEFT] or less left (or whose first ring has passed) are handled through
 * [handledThrough] (null: none), so they never come back (a one-time alarm that rang and was switched off, a ring then
 * an edit).
 */
data class SkipReminderPlan(
    val handledThrough: LocalDate?,
    val target: SkipReminderTarget?,
)

/**
 * The next date to remind, in [zone] at [now], and the dates handled before it (0.1.8, [SkipReminderPlan]):
 * - A date's first ring is the earliest ring on it of the enabled alarms: a repeating alarm rings on a date of its days at
 *   its time, unless that date is its skipped date; a one-time alarm rings on the date of its next occurrence. An
 *   occurrence already past counts too when it came after the alarm was last saved or switched on (it rang), so once a
 *   date's first ring has passed, that date gets no reminder.
 * - Only dates after [remindedThrough] count (monotonic: a date handled once never comes back). The first of them whose
 *   first ring is more than [SkipReminder.MIN_LEFT] away is the target; the ones before it are handled.
 */
fun skipReminderPlan(
    alarms: List<Alarm>,
    now: Instant,
    zone: TimeZone,
    remindedThrough: LocalDate?,
): SkipReminderPlan {
    val today = now.toLocalDateTime(zone).date
    val enabled = alarms.filter { it.enabled }
    val firstRings =
        (0..SkipReminder.DAYS_AHEAD)
            .map { offset -> today.plus(DatePeriod(days = offset)) }
            .filter { date -> remindedThrough == null || date > remindedThrough }
            .mapNotNull { date -> enabled.mapNotNull { it.ringOn(date, now, zone) }.minOrNull()?.let { date to it } }
    val target = firstRings.firstOrNull { (_, first) -> first - now > SkipReminder.MIN_LEFT }
    return SkipReminderPlan(
        handledThrough = firstRings.takeWhile { it != target }.lastOrNull()?.first,
        target = target?.let { (date, first) -> SkipReminderTarget(date, first, first.toLocalDateTime(zone).time) },
    )
}

/** When this enabled alarm rings on [date] (see [skipReminderTarget]), or null when it does not. */
private fun Alarm.ringOn(
    date: LocalDate,
    now: Instant,
    zone: TimeZone,
): Instant? {
    val rule = toRule()
    return when {
        rule.isOneTime -> nextOccurrence(rule, now, zone).takeIf { it.toLocalDateTime(zone).date == date }
        !rule.ringsOn(date.dayOfWeek) || date == skippedDate -> null
        else -> LocalDateTime(date, rule.time).toInstant(zone).takeIf { it > now || it > updatedAt }
    }
}
