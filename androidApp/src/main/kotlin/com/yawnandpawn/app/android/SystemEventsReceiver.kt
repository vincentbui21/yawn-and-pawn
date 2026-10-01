package com.yawnandpawn.app.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.time.Duration.Companion.seconds

/**
 * Re-arms every alarm (`rescheduleAll()`, AD-4) when the system clears or shifts them: after a reboot, a clock or
 * time-zone change, an app update, and a change of the exact-alarm permission. Runs inside `goAsync()` and finishes
 * the pending result in every case. Exported only because these are system broadcasts (all protected, so no other app
 * can send them); any other action is ignored. `directBootAware`; it never starts a foreground service or an activity.
 * `LOCKED_BOOT_COMPLETED` is Epic 2.
 */
class SystemEventsReceiver :
    BroadcastReceiver(),
    KoinComponent {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in ACTIONS) return
        val scheduling = get<AlarmScheduling>()
        val logger = get<Logger>()
        val pending = goAsync()
        get<ApplicationScope>().launch {
            try {
                withTimeoutOrNull(WORK_BUDGET) { scheduling.rescheduleAll() }
                    ?: logger.log(LogEvent.OperationFailed("reschedule alarms", "took longer than $WORK_BUDGET"))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Every action in the manifest filter, and nothing else. */
        val ACTIONS: Set<String> =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31); never sent on older versions.
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            )

        /** Well under the 10 s a broadcast may take. */
        private val WORK_BUDGET = 8.seconds
    }
}
