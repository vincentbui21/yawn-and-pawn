package com.yawnandpawn.app.ui.format

import androidx.compose.runtime.Composable
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** The phone's 12/24-hour setting; read on every call (no clock read). */
@Composable
expect fun is24HourClock(): Boolean

/** [time] as the phone shows times: "07:00" (24-hour) or "7:00 AM" (12-hour, locale AM/PM markers). */
expect fun formatClockTime(
    time: LocalTime,
    is24Hour: Boolean,
): String

/** How a weekday is written in the phone's locale. */
enum class DayNameStyle {
    /** One letter, for the day chips ("M"). */
    Narrow,

    /** The alarm repeat summary ("Mon"). */
    Short,

    /** What TalkBack reads on a day chip ("Monday"). */
    Full,
}

/** [day] in the phone's locale, in [style]. */
expect fun dayName(
    day: DayOfWeek,
    style: DayNameStyle,
): String

/** Monday first, as the day chips show them (M T W T F S S). */
val WeekOrder: List<DayOfWeek> =
    listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY,
    )

/** The locale's AM ([am] true) or PM marker, for the 12-hour time wheel. */
expect fun periodName(am: Boolean): String

/** [date] as a long locale date without the year, for the ringing screen ("Monday, September 28"). */
expect fun formatLongDate(date: LocalDate): String

/** How a date is written in the phone's locale (Progress, Day detail, Purchase history). */
enum class DateStyle {
    /** A calendar or history month heading ("September 2026"). */
    MonthYear,

    /** A snoozes-chart week label ("9/22"). */
    Numeric,

    /** A day without the weekday ("Sep 22"). */
    DayMonth,

    /** A day with its short weekday ("Tue, Sep 22"). */
    WeekdayDayMonth,
}

/** [date] in the phone's locale, in [style]. */
expect fun formatDate(
    date: LocalDate,
    style: DateStyle,
): String
