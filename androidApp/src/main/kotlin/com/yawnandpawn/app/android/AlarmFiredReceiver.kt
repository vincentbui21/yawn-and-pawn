package com.yawnandpawn.app.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.log.FireKind
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Receives every system alarm the app armed (AD-4): a stored alarm, the session slot or the test alarm, told apart by
 * the action. It only hands the fire to the [AlarmFiredHandler] port, inside `goAsync()`, and finishes the pending
 * result in every case. Not exported (only the app's own PendingIntents reach it), `directBootAware`, and it never
 * starts a foreground service or an activity itself.
 */
class AlarmFiredReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private sealed interface Fire {
        data class Alarm(
            val fired: AlarmFired,
        ) : Fire

        data object SessionSlot : Fire

        data object TestAlarm : Fire
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val logger = get<Logger>()
        val fire =
            when (intent.action) {
                ACTION_ALARM -> intent.toAlarmFired()?.let { Fire.Alarm(it) }
                ACTION_SESSION_SLOT -> Fire.SessionSlot
                ACTION_TEST_ALARM -> Fire.TestAlarm
                else -> null
            }
        if (fire == null) {
            logger.log(LogEvent.FireIgnored(FireKind.Alarm, intent.getStringExtra(EXTRA_ALARM_ID), "unknown action or missing extras"))
            return
        }
        val handler = get<AlarmFiredHandler>()
        val pending = goAsync()
        get<ApplicationScope>().launch {
            try {
                withTimeoutOrNull(WORK_BUDGET) {
                    when (fire) {
                        is Fire.Alarm -> handler.onAlarmFired(fire.fired)
                        Fire.SessionSlot -> handler.onSessionSlotFired()
                        Fire.TestAlarm -> handler.onTestAlarmFired()
                    }
                } ?: logger.log(LogEvent.OperationFailed("handle alarm fire", "took longer than $WORK_BUDGET"))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ALARM = "com.yawnandpawn.app.action.ALARM_FIRED"
        const val ACTION_SESSION_SLOT = "com.yawnandpawn.app.action.SESSION_SLOT_FIRED"
        const val ACTION_TEST_ALARM = "com.yawnandpawn.app.action.TEST_ALARM_FIRED"
        const val EXTRA_ALARM_ID = "alarmId"
        const val EXTRA_SCHEDULED_AT = "scheduledAt"

        /** Well under the 10 s a broadcast may take, so the system never kills the process for it. */
        private val WORK_BUDGET = 8.seconds

        /** The explicit intent for [action]: the operation of a system alarm without its extras. */
        fun intent(
            context: Context,
            action: String,
        ): Intent = Intent(context, AlarmFiredReceiver::class.java).setAction(action)

        private fun Intent.toAlarmFired(): AlarmFired? {
            val alarmId = getStringExtra(EXTRA_ALARM_ID)
            if (alarmId == null || !hasExtra(EXTRA_SCHEDULED_AT)) return null
            return AlarmFired(alarmId, Instant.fromEpochMilliseconds(getLongExtra(EXTRA_SCHEDULED_AT, 0)))
        }
    }
}
