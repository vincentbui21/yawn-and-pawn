package com.yawnandpawn.app.ui.home

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.delete_alarm_confirm
import com.yawnandpawn.app.ui.resources.delete_alarm_keep
import com.yawnandpawn.app.ui.resources.delete_alarm_title
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource

/**
 * `dialog-confirm` before an alarm is deleted (from Home or the editor): "Delete your {time} alarm? This is logged.",
 * the destructive "Delete" and "Keep it", the safe default (Back and a tap outside keep it too).
 */
@Composable
internal fun DeleteAlarmConfirm(
    time: LocalTime,
    is24Hour: Boolean,
    onConfirm: () -> Unit,
    onKeep: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(Res.string.delete_alarm_title, formatClockTime(time, is24Hour)),
        confirmText = stringResource(Res.string.delete_alarm_confirm),
        safeText = stringResource(Res.string.delete_alarm_keep),
        onConfirm = onConfirm,
        onSafe = onKeep,
        destructive = true,
    )
}
