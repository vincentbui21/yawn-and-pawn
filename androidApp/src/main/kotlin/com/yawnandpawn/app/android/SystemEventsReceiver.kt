package com.yawnandpawn.app.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yawnandpawn.app.android.wake.UnlockSignals
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.SessionSlotRearm
import com.yawnandpawn.app.core.session.UserLockState
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Re-arms every alarm (`rescheduleAll()`, AD-4) when the system clears or shifts them: after a reboot, a clock or
 * time-zone change, an app update, and a change of the exact-alarm permission. Then (Story 2.1) it arms the session
 * slot for a session left in `runtime.db` ([SessionSlotRearm.afterSystemEvent]): at once while it rings, at the snooze
 * end while snoozed. It never restores the session, never starts a foreground service (no media foreground service from
 * a boot receiver on Android 15+) and never starts an activity: the slot's fire starts the wake service, which restores.
 * Runs inside `goAsync()` and finishes the pending result in every case. Exported only because these are system
 * broadcasts (all protected, so no other app can send them); any other action is ignored. `directBootAware`, and it
 * also takes `LOCKED_BOOT_COMPLETED` (Story 2.2), so a session comes back after a reboot before the first unlock.
 *
 * Story 2.2: after `TIME_SET` or `TIMEZONE_CHANGED` the slot of a snooze is armed again from its stored `Deadline`
 * (new wall time + the monotonic time left), so a clock change neither ends nor shortens it; a ringing session gets
 * the slot at once. Nothing re-resolves the frozen `SessionConfig`.
 */
class SystemEventsReceiver :
    BroadcastReceiver(),
    KoinComponent {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in ACTIONS) return
        // After an update, a slot the previous version armed to AlarmFiredReceiver is cancelled (Story 2.1 review).
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) AndroidAlarmScheduler.cancelLegacySessionSlot(context)
        val scheduling = get<AlarmScheduling>()
        val rearm = get<SessionSlotRearm>()
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) unlockSignal()
        runWithinBudget(get(), get(), "reschedule alarms") {
            // The alarms first (review): a slow store open at boot that overruns the budget in the slot re-arm must not
            // leave every alarm unarmed. Then the session slot.
            scheduling.rescheduleAll()
            rearm.afterSystemEvent()
        }
    }

    /**
     * `BOOT_COMPLETED` arrives only after the first unlock (Story 2.4): a ring running before it gets the unlock. Sent
     * before the re-arm so the unlock never waits for it; whatever it throws is logged and the re-arm still runs.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun unlockSignal() {
        try {
            if (get<UserLockState>().isUserUnlocked()) get<UnlockSignals>().onUnlocked()
        } catch (e: Exception) {
            get<Logger>().log(LogEvent.OperationFailed("apply the unlock", e::class.simpleName.orEmpty()))
        }
    }

    companion object {
        /** Every action in the manifest filter, and nothing else. */
        val ACTIONS: Set<String> =
            setOf(
                // Before the first unlock (Story 2.2): the session comes back without waiting for the user to unlock.
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31); never sent on older versions.
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            )
    }
}
