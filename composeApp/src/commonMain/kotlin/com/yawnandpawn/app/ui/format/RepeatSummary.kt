package com.yawnandpawn.app.ui.format

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.repeat_every_day
import com.yawnandpawn.app.ui.resources.repeat_once
import kotlinx.datetime.DayOfWeek
import org.jetbrains.compose.resources.stringResource

/** EXPERIENCE.md alarm repeat summary: "Every day", "Once", otherwise locale short day names ("Mon, Wed, Fri"). */
@Composable
fun repeatSummary(days: Set<DayOfWeek>): String =
    when {
        days.isEmpty() -> stringResource(Res.string.repeat_once)
        days.containsAll(WeekOrder) -> stringResource(Res.string.repeat_every_day)
        else -> WeekOrder.filter { it in days }.joinToString(", ") { dayName(it, DayNameStyle.Short) }
    }
