package com.yawnandpawn.app.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.session.SessionSlotRearm
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Re-arms every alarm (`rescheduleAll()`, AD-4) when the system clears or shifts them: after a reboot, a clock or
 * time-zone change, an app update, and a change of the exact-alarm permission. Then (Story 2.1) it arms the session
 * slot for a session left in `runtime.db` ([SessionSlotRearm.afterSystemEvent]): at once while it rings, at the snooze
 * end while snoozed. It never restores the session, never starts a foreground service (no media foreground service from
 * a boot receiver on Android 15+) and never starts an activity: the slot's fire starts the wake service, which restores.
 * Runs inside `goAsync()` and finishes the pending result in every case. Exported only because these are system
 * broadcasts (all protected, so no other app can send them); any other action is ignored. `directBootAware`.
 * `LOCKED_BOOT_COMPLETED` is Story 2.3.
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
        val rearm = get<SessionSlotRearm>()
        runWithinBudget(get(), get(), "reschedule alarms") {
            // The session slot first: a ringing session comes back at once, whatever the alarms' re-arm takes.
            try {
                rearm.afterSystemEvent()
            } finally {
                scheduling.rescheduleAll()
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
    }
}
