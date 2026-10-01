package com.yawnandpawn.app.android

import android.util.Log
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger

/**
 * [Logger] on Logcat, tag [TAG]: user actions at info level, failures at warning level. Events carry no personal data
 * (alarm ids are random UUIDs; storage causes are diagnostic text).
 */
class AndroidLogger : Logger {
    override fun log(event: LogEvent) {
        when (event) {
            is LogEvent.AlarmDeleted -> Log.i(TAG, "AlarmDeleted alarmId=${event.alarmId} at=${event.at}")
            is LogEvent.OperationFailed -> Log.w(TAG, "OperationFailed operation=${event.operation} cause=${event.cause}")
        }
    }

    companion object {
        const val TAG = "YawnAndPawn"
    }
}
