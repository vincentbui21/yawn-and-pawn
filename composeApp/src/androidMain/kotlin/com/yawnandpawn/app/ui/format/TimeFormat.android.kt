package com.yawnandpawn.app.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import java.text.DateFormatSymbols
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
actual fun is24HourClock(): Boolean {
    // Reading the configuration makes a locale or settings change recompose the caller.
    LocalConfiguration.current
    return DateFormat.is24HourFormat(LocalContext.current)
}

actual fun formatClockTime(
    time: LocalTime,
    is24Hour: Boolean,
): String {
    val pattern = if (is24Hour) "HH:mm" else "h:mm a"
    return java.time.LocalTime
        .of(time.hour, time.minute)
        .format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

actual fun dayName(
    day: DayOfWeek,
    style: DayNameStyle,
): String {
    val textStyle =
        when (style) {
            DayNameStyle.Narrow -> TextStyle.NARROW
            DayNameStyle.Short -> TextStyle.SHORT
            DayNameStyle.Full -> TextStyle.FULL
        }
    return java.time.DayOfWeek
        .of(day.isoDayNumber)
        .getDisplayName(textStyle, Locale.getDefault())
}

actual fun periodName(am: Boolean): String {
    val markers = DateFormatSymbols.getInstance(Locale.getDefault()).amPmStrings
    return if (am) markers[0] else markers[1]
}

actual fun formatLongDate(date: LocalDate): String {
    val locale = Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
    return java.time.LocalDate
        .of(date.year, date.month.number, date.day)
        .format(DateTimeFormatter.ofPattern(pattern, locale))
}
