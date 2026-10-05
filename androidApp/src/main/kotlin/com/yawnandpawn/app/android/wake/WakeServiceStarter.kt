package com.yawnandpawn.app.android.wake

import android.content.Context
import android.content.Intent
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlin.time.Instant

/**
 * Starts [WakeService] in the foreground. A fire from `setAlarmClock` lets the app start a foreground service from the
 * background for a short while, so [startAlarm], [startSlot] and [startTest] (called from the alarm receiver) are
 * allowed. A start the platform refuses (`ForegroundServiceStartNotAllowedException`, an `IllegalStateException`, for
 * example a restore from the background) is logged and returns false. The session slot then starts the service again:
 * the alarm handler re-arms it for a refused alarm or slot start (`SessionSlotRearm.afterRefusedStart`, Story 2.1), and
 * a restored session's own entry effects arm it for a refused restore.
 */
class WakeServiceStarter(
    private val context: Context,
    private val logger: Logger,
    private val startService: (Intent) -> Unit = { context.startForegroundService(it) },
) {
    /** The stored alarm [fired] rings; [token] (`WakeServiceStarts`) tells the receiver when the service took it. */
    fun startAlarm(
        fired: AlarmFired,
        token: Long,
    ): Boolean = start(WakeService.alarmIntent(context, fired).withToken(token))

    /** The session slot fired, standing also for [alarm] when it carried one (Story 2.1), retried since [retrySince]. */
    fun startSlot(
        token: Long,
        alarm: AlarmFired? = null,
        retrySince: Instant? = null,
    ): Boolean = start(WakeService.slotIntent(context, alarm, retrySince).withToken(token))

    /** The test alarm fired (Story 1.18): the service rings the pending test. */
    fun startTest(token: Long): Boolean = start(WakeService.intent(context, WakeService.ACTION_TEST).withToken(token))

    private fun Intent.withToken(token: Long): Intent = putExtra(WakeService.EXTRA_START_TOKEN, token)

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
