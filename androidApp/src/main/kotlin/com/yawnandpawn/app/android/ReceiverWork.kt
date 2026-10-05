package com.yawnandpawn.app.android

import android.content.BroadcastReceiver
import android.content.Intent
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Well under the 10 s a broadcast may take, so the system never kills the process for it. */
internal val RECEIVER_WORK_BUDGET: Duration = 8.seconds

/**
 * Runs [work] inside `goAsync()` on [scope], at most [RECEIVER_WORK_BUDGET] (an overrun is logged as [operation]), and
 * finishes the pending result in every case, also when [work] throws (the scope logs it).
 */
internal fun BroadcastReceiver.runWithinBudget(
    scope: ApplicationScope,
    logger: Logger,
    operation: String,
    work: suspend () -> Unit,
) {
    val pending = goAsync()
    scope.launch {
        try {
            withTimeoutOrNull(RECEIVER_WORK_BUDGET) { work() }
                ?: logger.log(LogEvent.OperationFailed(operation, "took longer than $RECEIVER_WORK_BUDGET"))
        } finally {
            pending.finish()
        }
    }
}

/**
 * Puts the alarm [fired] in the extras [AlarmFiredReceiver.EXTRA_ALARM_ID] and [AlarmFiredReceiver.EXTRA_SCHEDULED_AT];
 * null puts a null id, so an intent that replaces another (`FLAG_UPDATE_CURRENT`) carries no alarm any more.
 */
internal fun Intent.putAlarmFired(fired: AlarmFired?): Intent =
    putExtra(AlarmFiredReceiver.EXTRA_ALARM_ID, fired?.alarmId)
        .apply { fired?.let { putExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, it.scheduledAt.toEpochMilliseconds()) } }

/** The alarm in this intent's extras ([putAlarmFired]), or null when it carries none. */
internal fun Intent.alarmFiredOrNull(): AlarmFired? {
    val alarmId = getStringExtra(AlarmFiredReceiver.EXTRA_ALARM_ID)
    if (alarmId == null || !hasExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT)) return null
    return AlarmFired(alarmId, Instant.fromEpochMilliseconds(getLongExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, 0)))
}
