package com.yawnandpawn.app.android

import android.util.Log
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger

/**
 * [Logger] on Logcat, tag [TAG]: user actions and scheduling at info level, failures at warning level. Events carry no
 * personal data (alarm ids are random UUIDs; storage causes are diagnostic text).
 */
class AndroidLogger : Logger {
    override fun log(event: LogEvent) {
        when (event) {
            is LogEvent.AlarmDeleted -> {
                Log.i(TAG, "AlarmDeleted alarmId=${event.alarmId} at=${event.at}")
            }

            is LogEvent.OperationFailed -> {
                Log.w(TAG, "OperationFailed operation=${event.operation} cause=${event.cause}")
            }

            is LogEvent.FireIgnored -> {
                Log.i(TAG, "FireIgnored kind=${event.kind} alarmId=${event.alarmId} reason=${event.reason}")
            }

            is LogEvent.OneTimeAlarmPassed -> {
                Log.i(TAG, "OneTimeAlarmPassed alarmId=${event.alarmId} missedAt=${event.missedAt}")
            }

            is LogEvent.AlarmsRescheduled -> {
                Log.i(TAG, "AlarmsRescheduled scheduled=${event.scheduled} disabled=${event.disabled} failed=${event.failed}")
            }
        }
    }

    companion object {
        const val TAG = "YawnAndPawn"
    }
}
