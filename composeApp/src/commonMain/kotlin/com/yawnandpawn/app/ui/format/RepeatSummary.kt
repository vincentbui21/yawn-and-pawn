package com.yawnandpawn.app.ui.format

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.repeat_every_day
import com.yawnandpawn.app.ui.resources.repeat_once
import com.yawnandpawn.app.ui.resources.repeat_weekdays
import com.yawnandpawn.app.ui.resources.repeat_weekends
import kotlinx.datetime.DayOfWeek
import org.jetbrains.compose.resources.stringResource

/** Monday to Friday: the "Weekdays" repeat choice. */
val Weekdays: Set<DayOfWeek> =
    setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

/** Saturday and Sunday. */
val Weekends: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/**
 * EXPERIENCE.md alarm repeat summary: "Every day", "Once", "Weekdays" (Mon to Fri) and "Weekends" (Sat and Sun, owner
 * decision 2026-09-27), otherwise locale short day names ("Mon, Wed, Fri").
 */
@Composable
fun repeatSummary(days: Set<DayOfWeek>): String =
    when {
        days.isEmpty() -> stringResource(Res.string.repeat_once)
        days.containsAll(WeekOrder) -> stringResource(Res.string.repeat_every_day)
        days == Weekdays -> stringResource(Res.string.repeat_weekdays)
        days == Weekends -> stringResource(Res.string.repeat_weekends)
        else -> WeekOrder.filter { it in days }.joinToString(", ") { dayName(it, DayNameStyle.Short) }
    }
