package com.yawnandpawn.app.android.wake

import android.content.Context
import android.content.Intent
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger

/**
 * Starts [WakeService] in the foreground. A fire from `setAlarmClock` lets the app start a foreground service from the
 * background for a short while, so [startAlarm] and [startSlot] (called from the alarm receiver) are allowed. A start
 * the platform refuses (`ForegroundServiceStartNotAllowedException`, an `IllegalStateException`, for example a restore
 * at app start from the background) is logged and returns false: the armed session slot (at most 60 s away) fires the
 * receiver, which starts the service again.
 */
class WakeServiceStarter(
    private val context: Context,
    private val logger: Logger,
    private val startService: (Intent) -> Unit = { context.startForegroundService(it) },
) {
    /** The stored alarm [fired] rings. */
    fun startAlarm(fired: AlarmFired): Boolean = start(WakeService.alarmIntent(context, fired))

    /** The session slot fired. */
    fun startSlot(): Boolean = start(WakeService.intent(context, WakeService.ACTION_SLOT))

    /** A session (or the emergency ring) needs the service and it is not running. */
    fun startRestore(): Boolean = start(WakeService.intent(context, WakeService.ACTION_RESTORE))

    private fun start(intent: Intent): Boolean =
        try {
            startService(intent)
            true
        } catch (e: IllegalStateException) {
            refused(e)
        } catch (e: SecurityException) {
            refused(e)
        }

    private fun refused(e: RuntimeException): Boolean {
        logger.log(LogEvent.OperationFailed("start wake service", "${e::class.simpleName}; the session slot brings the ring back"))
        return false
    }
}
