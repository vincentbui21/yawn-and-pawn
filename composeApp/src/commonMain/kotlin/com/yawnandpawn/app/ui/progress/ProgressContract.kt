package com.yawnandpawn.app.ui.progress

import com.yawnandpawn.app.ui.format.Money
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/** How a session ended (EXPERIENCE.md glossary, FR-PRG-1). Test and Skipped are excluded from rates and streaks. */
enum class Outcome { OnTime, Snoozed, Missed, Skipped, Test }

/** The `stat-tile`s. A `null` rate or average has no mornings behind it yet ("No mornings yet"). */
data class ProgressStats(
    val currentStreak: Int,
    val bestStreak: Int,
    /** On-time mornings over the last 7 days, in percent. */
    val onTime7Days: Int?,
    /** On-time mornings over the last 30 days, in percent. */
    val onTime30Days: Int?,
    /** Average minutes from first ring to "I'm up"; 0 is "Under 1 min". */
    val averageMinutesToUp: Int?,
)

/** One bar of the snoozes chart: the week starting [weekStart] and how many snoozes it had. */
data class WeekSnoozes(
    val weekStart: LocalDate,
    val snoozes: Int,
)

/** A day of the calendar with at least one session. */
data class CalendarDay(
    val date: LocalDate,
    val outcome: Outcome,
    val fallbackUsed: Boolean = false,
    val sessions: Int = 1,
)

/** The calendar's month: [firstDay] is its 1st; [days] only the days with a session; [today] gets the accent ring. */
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

/** "Money paid" this week, this month and all time, in the phone's currency. */
data class MoneyPaid(
    val thisWeek: Money,
    val thisMonth: Money,
    val allTime: Money,
)

/** What Progress renders. With no mornings logged yet ([isEmpty]) only the empty note and the links show. */
data class ProgressUiState(
    val stats: ProgressStats? = null,
    /** The last 8 weeks, oldest first. */
    val weeks: List<WeekSnoozes> = emptyList(),
    /** The tapped bar, whose number shows above the chart; `null` shows the latest week. */
    val selectedWeek: Int? = null,
    val calendar: CalendarMonth? = null,
    val money: MoneyPaid? = null,
    /** Nothing logged yet: "Export CSV" is disabled with "Nothing to export yet.". */
    val canExport: Boolean = false,
) {
    val isEmpty: Boolean get() = stats == null
}

/** Everything the user can do on Progress. */
sealed interface ProgressIntent {
    data class WeekTapped(
        val index: Int,
    ) : ProgressIntent

    data object PreviousMonth : ProgressIntent

    data object NextMonth : ProgressIntent

    data class DayTapped(
        val date: LocalDate,
    ) : ProgressIntent

    data object PurchaseHistoryClicked : ProgressIntent

    data object ExportClicked : ProgressIntent
}
