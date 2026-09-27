package com.yawnandpawn.app.ui.format

import androidx.compose.runtime.Composable
import kotlinx.datetime.DayOfWeek
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
