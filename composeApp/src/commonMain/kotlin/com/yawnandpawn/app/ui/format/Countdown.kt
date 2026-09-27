package com.yawnandpawn.app.ui.format

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.home_rings_in_days
import com.yawnandpawn.app.ui.resources.home_rings_in_hours
import com.yawnandpawn.app.ui.resources.home_rings_in_minutes
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

/** The Home next-alarm countdown, in the unit EXPERIENCE.md picks for its length. */
sealed interface Countdown {
    /** Under 1 h: "Rings in {minutes} min". */
    data class Minutes(
        val minutes: Int,
    ) : Countdown

    /** 1 h to under 24 h: "Rings in {hours} h {minutes} min". */
    data class HoursMinutes(
        val hours: Int,
        val minutes: Int,
    ) : Countdown

    /** 24 h or more: "Rings in {days} d {hours} h". */
    data class DaysHours(
        val days: Int,
        val hours: Int,
    ) : Countdown
}

/** [untilRing] rounded up to the whole minute (a ring 30 s away is "Rings in 1 min"), then split per [Countdown]. */
fun countdownOf(untilRing: Duration): Countdown {
    val seconds = untilRing.inWholeSeconds.coerceAtLeast(0)
    val minutes = ((seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).coerceAtLeast(1)
    return when {
        minutes < MINUTES_PER_HOUR -> {
            Countdown.Minutes(minutes.toInt())
        }

        minutes < MINUTES_PER_DAY -> {
            Countdown.HoursMinutes((minutes / MINUTES_PER_HOUR).toInt(), (minutes % MINUTES_PER_HOUR).toInt())
        }

        else -> {
            Countdown.DaysHours((minutes / MINUTES_PER_DAY).toInt(), ((minutes % MINUTES_PER_DAY) / MINUTES_PER_HOUR).toInt())
        }
    }
}

/** "Rings in 45 min" · "Rings in 7 h 12 min" · "Rings in 2 d 3 h". */
@Composable
fun countdownText(countdown: Countdown): String =
    when (countdown) {
        is Countdown.Minutes -> stringResource(Res.string.home_rings_in_minutes, countdown.minutes)
        is Countdown.HoursMinutes -> stringResource(Res.string.home_rings_in_hours, countdown.hours, countdown.minutes)
        is Countdown.DaysHours -> stringResource(Res.string.home_rings_in_days, countdown.days, countdown.hours)
    }

private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24L * 60L
