package com.yawnandpawn.app.android

import android.content.Intent
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.core.log.FireKind
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.stopApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class AndroidLogAndTimeSignalTest {
    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()

    @After
    fun tearDown() {
        stopApp()
    }

    private fun registeredTimeReceivers(): Int =
        shadowOf(app).registeredReceivers.count { it.intentFilter.hasAction(Intent.ACTION_TIME_TICK) }

    @Test
    fun `Koin binds the Logger and the time change signal to their Android adapters`() {
        val koin = GlobalContext.get()

        assertIs<AndroidLogger>(koin.get<Logger>())
        assertIs<AndroidTimeChangeSignal>(koin.get<TimeChangeSignal>())
    }

    @Test
    fun `the logger writes deletions at info and failures at warning level`() {
        ShadowLog.clear()
        val logger = AndroidLogger()

        logger.log(LogEvent.AlarmDeleted("a1", Instant.parse("2027-03-03T06:00:00Z")))
        logger.log(LogEvent.OperationFailed("load alarms", "storage failure: closed"))

        val logs = ShadowLog.getLogsForTag(AndroidLogger.TAG)
        assertEquals(listOf(Log.INFO, Log.WARN), logs.map { it.type })
        assertEquals("AlarmDeleted alarmId=a1 at=2027-03-03T06:00:00Z", logs[0].msg)
        assertEquals("OperationFailed operation=load alarms cause=storage failure: closed", logs[1].msg)
    }

    @Test
    fun `the logger writes ignored fires, reschedule summaries and passed one-time alarms at info level`() {
        ShadowLog.clear()
        val logger = AndroidLogger()

        logger.log(LogEvent.FireIgnored(FireKind.Alarm, "a1", "alarm disabled"))
        logger.log(LogEvent.AlarmsRescheduled(scheduled = 2, disabled = 1, failed = 0))
        logger.log(LogEvent.OneTimeAlarmPassed("a2", Instant.parse("2027-03-03T06:00:00Z")))

        val logs = ShadowLog.getLogsForTag(AndroidLogger.TAG)
        assertEquals(listOf(Log.INFO, Log.INFO, Log.INFO), logs.map { it.type })
        assertEquals("FireIgnored kind=Alarm alarmId=a1 reason=alarm disabled", logs[0].msg)
        assertEquals("AlarmsRescheduled scheduled=2 disabled=1 failed=0", logs[1].msg)
        assertEquals("OneTimeAlarmPassed alarmId=a2 missedAt=2027-03-03T06:00:00Z", logs[2].msg)
    }

    @Test
    fun `the logger writes session effects and ignored session events at info level by type name`() {
        ShadowLog.clear()
        val logger = AndroidLogger()

        logger.log(LogEvent.SessionEffectLogged("SoundAt", entry = true))
        logger.log(LogEvent.SessionEventIgnored("ImUpTapped", sessionId = null))

        val logs = ShadowLog.getLogsForTag(AndroidLogger.TAG)
        assertEquals(listOf(Log.INFO, Log.INFO), logs.map { it.type })
        assertEquals("SessionEffectLogged type=SoundAt entry=true", logs[0].msg)
        assertEquals("SessionEventIgnored type=ImUpTapped sessionId=null", logs[1].msg)
    }

    @Test
    fun `the time signal emits on a minute tick, a time set and a zone change, only while collected`() {
        val signal = AndroidTimeChangeSignal(app)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var received = 0
        val before = registeredTimeReceivers()

        scope.launch { signal.changes().collect { received++ } }
        assertEquals(before + 1, registeredTimeReceivers())
        listOf(Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED).forEach { action ->
            app.sendBroadcast(Intent(action))
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(3, received)

        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, registeredTimeReceivers())
    }
}
