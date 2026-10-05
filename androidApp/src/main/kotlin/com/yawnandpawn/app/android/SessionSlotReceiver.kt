package com.yawnandpawn.app.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.log.FireKind
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.time.Instant

/**
 * Receives the session slot (AD-4, Story 2.1): the backup alarm that brings a session back after a kill. It hands the
 * fire, with the alarm the slot also stands for when it carries one ([AlarmFiredHandler.onSessionSlotFired]), to the
 * handler inside `goAsync()`; the handler starts `WakeService` with the slot action, which restores the stored session
 * before anything else. The pending result is finished in every case. Not exported, no intent filter (only the app's
 * own PendingIntent reaches it), `directBootAware`, and it never starts an activity or calls `SessionEngine.restore`.
 */
class SessionSlotReceiver :
    BroadcastReceiver(),
    KoinComponent {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val logger = get<Logger>()
        if (intent.action != ACTION_SESSION_SLOT) {
            logger.log(LogEvent.FireIgnored(FireKind.SessionSlot, alarmId = null, reason = "unknown action"))
            return
        }
        val alarm = intent.alarmFiredOrNull()
        val retrySince = intent.retrySinceOrNull()
        val handler = get<AlarmFiredHandler>()
        runWithinBudget(get(), logger, "handle session slot fire") { handler.onSessionSlotFired(alarm, retrySince) }
    }

    companion object {
        const val ACTION_SESSION_SLOT = "com.yawnandpawn.app.action.SESSION_SLOT_FIRED"

        /** The slot's operation intent, carrying [alarm] (or no alarm) and [retrySince] (or none) in its extras. */
        fun intent(
            context: Context,
            alarm: AlarmFired? = null,
            retrySince: Instant? = null,
        ): Intent =
            Intent(context, SessionSlotReceiver::class.java)
                .setAction(ACTION_SESSION_SLOT)
                .putAlarmFired(alarm)
                .putRetrySince(retrySince)
    }
}
