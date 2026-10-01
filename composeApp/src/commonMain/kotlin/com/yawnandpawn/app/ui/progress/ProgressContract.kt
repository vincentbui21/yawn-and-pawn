package com.yawnandpawn.app.ui.progress

import com.yawnandpawn.app.ui.format.Money
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/** How a session ended (EXPERIENCE.md glossary, FR-PRG-1). Test and Skipped are excluded from rates and streaks. */
enum class Outcome { OnTime, Snoozed, Missed, Skipped, Test }

/** The small `stat-tile`s and the streak card (all over the last 30 days). A `null` value has no mornings behind it yet. */
data class ProgressStats(
    val currentStreak: Int,
    val bestStreak: Int,
    /** On-time mornings over the last 30 days, in percent. */
    val onTime30Days: Int?,
    /** Average minutes from first ring to "I'm up"; 0 is "Under 1 min". */
    val averageMinutesToUp: Int?,
    /** Snoozes over the last 30 days. */
    val snoozes30Days: Int,
)

/** One morning of the hero ring: its date and outcome, `null` when no alarm rang that day. */
data class RingDay(
    val date: LocalDate,
    val outcome: Outcome?,
    val fallbackUsed: Boolean = false,
)

/** A day of the calendar with at least one session. */
data class CalendarDay(
    val date: LocalDate,
    val outcome: Outcome,
    val fallbackUsed: Boolean = false,
    val sessions: Int = 1,
)

/** The calendar's month: [firstDay] is its 1st; [days] only the days with a session; [today] is in a pill. */
data class CalendarMonth(
    val firstDay: LocalDate,
    val days: List<CalendarDay>,
    val today: LocalDate? = null,
    val hasPrevious: Boolean = true,
    val hasNext: Boolean = false,
) {
    val length: Int get() = firstDay.daysUntil(firstDay.plus(1, DateTimeUnit.MONTH))

    fun day(dayOfMonth: Int): CalendarDay? = days.firstOrNull { it.date.day == dayOfMonth }
}

/** A day tapped once, and where: its chip shows under the ring ([inCalendar] false) or under the calendar. */
data class DaySelection(
    val date: LocalDate,
    val outcome: Outcome,
    val inCalendar: Boolean,
)

/** The Insight card: one short line from the user's own data (a template, never an invented number). */
enum class Insight {
    /** Average time to up is lowest on Monday to Friday. */
    FastestOnWeekdays,

    /** Average time to up is lowest on Saturday and Sunday. */
    FastestOnWeekends,
}

/**
 * What Progress renders (owner redesign 2026-09-30 and notes 2026-10-01, feedback items 21 and 23). With no mornings
 * logged yet ([isEmpty]) the ring is empty with the prompt, and the calendar and Purchase history follow. No export and no
 * snoozes chart (owner decisions 2026-10-01 against FR-PRG-6 and FR-PRG-2's chart).
 */
data class ProgressUiState(
    val today: LocalDate? = null,
    /** The last 30 mornings, oldest first, ending today. Empty: 30 blank dots. */
    val ring: List<RingDay> = emptyList(),
    val stats: ProgressStats? = null,
    val calendar: CalendarMonth? = null,
    /** Paid this month (money is always `text`, never green or red). */
    val paidThisMonth: Money? = null,
    val insight: Insight? = null,
    /** The ring dot or calendar day tapped once: its label chip shows; a second tap or the chip opens Day detail. */
    val selection: DaySelection? = null,
) {
    val isEmpty: Boolean get() = stats == null
}

/** Everything the user can do on Progress. */
sealed interface ProgressIntent {
    data object PreviousMonth : ProgressIntent

    data object NextMonth : ProgressIntent

    /** A first tap on a ring dot or calendar day with a session: shows its label chip. */
    data class DaySelected(
        val selection: DaySelection,
    ) : ProgressIntent

    /** The chip, or a second tap on the selected day: opens Day detail. */
    data class DayTapped(
        val date: LocalDate,
    ) : ProgressIntent

    data object PurchaseHistoryClicked : ProgressIntent
}

/** The ring shows this many mornings. */
const val RING_DAYS: Int = 30
