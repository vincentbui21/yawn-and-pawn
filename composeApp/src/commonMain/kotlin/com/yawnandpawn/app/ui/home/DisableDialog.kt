package com.yawnandpawn.app.ui.home

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.disable_keep_on
import com.yawnandpawn.app.ui.resources.disable_rings_in_hour_one
import com.yawnandpawn.app.ui.resources.disable_rings_in_hours
import com.yawnandpawn.app.ui.resources.disable_rings_in_minute_one
import com.yawnandpawn.app.ui.resources.disable_rings_in_minutes
import com.yawnandpawn.app.ui.resources.disable_title
import com.yawnandpawn.app.ui.resources.disable_turn_off
import org.jetbrains.compose.resources.stringResource

/**
 * `dialog-confirm` for switching an alarm off under the commitment lock (0.1.7 polish): "Turn off the {time} alarm?" over
 * "It rings in {n} hours." (whole hours; 1 hour, or minutes under an hour), the destructive "Turn off" and "Keep it on",
 * the default dismiss. The turn-off is still logged; the dialog no longer says so.
 */
@Composable
internal fun DisableDialog(
    dialog: DisableUnderLock,
    is24Hour: Boolean,
    onIntent: (HomeIntent) -> Unit,
) {
    ConfirmDialog(
        title = stringResource(Res.string.disable_title, formatClockTime(dialog.time, is24Hour)),
        body = ringsInText(dialog.ringsIn),
        confirmText = stringResource(Res.string.disable_turn_off),
        safeText = stringResource(Res.string.disable_keep_on),
        onConfirm = { onIntent(HomeIntent.DisableConfirmed) },
        onSafe = { onIntent(HomeIntent.DisableCancelled) },
        destructive = true,
    )
}

/** "It rings in 7 hours." · "It rings in 1 hour." · "It rings in 25 minutes." · "It rings in 1 minute.". */
@Composable
private fun ringsInText(ringsIn: Countdown): String {
    val hours =
        when (ringsIn) {
            is Countdown.Minutes -> {
                return if (ringsIn.minutes == 1) {
                    stringResource(Res.string.disable_rings_in_minute_one)
                } else {
                    stringResource(Res.string.disable_rings_in_minutes, ringsIn.minutes)
                }
            }

            is Countdown.HoursMinutes -> {
                ringsIn.hours
            }

            is Countdown.DaysHours -> {
                ringsIn.days * HOURS_PER_DAY + ringsIn.hours
            }
        }
    return when (hours) {
        1 -> stringResource(Res.string.disable_rings_in_hour_one)
        else -> stringResource(Res.string.disable_rings_in_hours, hours)
    }
}

private const val HOURS_PER_DAY = 24
