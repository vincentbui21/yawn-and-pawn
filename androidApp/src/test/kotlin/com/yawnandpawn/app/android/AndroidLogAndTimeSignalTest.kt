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

    /**
     * The log lines under [AndroidLogger.TAG] whose message is one of [expected], in order. Work the app starts on its own
     * (on a background thread, after `ShadowLog.clear()`) logs under the same tag, so only the test's own lines count.
     */
    private fun ownLogs(vararg expected: String): List<ShadowLog.LogItem> =
        ShadowLog.getLogsForTag(AndroidLogger.TAG).filter { it.msg in expected }

    @Test
    fun `the logger writes deletions at info and failures at warning level`() {
        ShadowLog.clear()
        val logger = AndroidLogger()

        logger.log(LogEvent.AlarmDeleted("a1", Instant.parse("2027-03-03T06:00:00Z")))
        logger.log(LogEvent.OperationFailed("load alarms", "storage failure: closed"))

        val expected =
            listOf(
                "AlarmDeleted alarmId=a1 at=2027-03-03T06:00:00Z",
                "OperationFailed operation=load alarms cause=storage failure: closed",
            )
        val logs = ownLogs(*expected.toTypedArray())
        assertEquals(expected, logs.map { it.msg })
        assertEquals(listOf(Log.INFO, Log.WARN), logs.map { it.type })
    }

    @Test
    fun `the logger writes ignored fires, reschedule summaries and passed one-time alarms at info level`() {
        ShadowLog.clear()
        val logger = AndroidLogger()

        logger.log(LogEvent.FireIgnored(FireKind.Alarm, "a1", "alarm disabled"))
        logger.log(LogEvent.AlarmsRescheduled(scheduled = 2, disabled = 1, failed = 0))
        logger.log(LogEvent.OneTimeAlarmPassed("a2", Instant.parse("2027-03-03T06:00:00Z")))

        val expected =
            listOf(
                "FireIgnored kind=Alarm alarmId=a1 reason=alarm disabled",
                "AlarmsRescheduled scheduled=2 disabled=1 failed=0",
                "OneTimeAlarmPassed alarmId=a2 missedAt=2027-03-03T06:00:00Z",
            )
        val logs = ownLogs(*expected.toTypedArray())
        assertEquals(expected, logs.map { it.msg })
        assertEquals(listOf(Log.INFO, Log.INFO, Log.INFO), logs.map { it.type })
    }

    @Test
    fun `the logger writes session effects and ignored session events at info level by type name`() {
        ShadowLog.clear()
        val logger = AndroidLogger()

        logger.log(LogEvent.SessionEffectLogged("SoundAt", entry = true))
        logger.log(LogEvent.SessionEventIgnored("ImUpTapped", sessionId = null))

        val expected = listOf("SessionEffectLogged type=SoundAt entry=true", "SessionEventIgnored type=ImUpTapped sessionId=null")
        val logs = ownLogs(*expected.toTypedArray())
        assertEquals(expected, logs.map { it.msg })
        assertEquals(listOf(Log.INFO, Log.INFO), logs.map { it.type })
    }

    @Test
    fun `the logger writes why a confirm sheet closed at info level (4_18 device check)`() {
        ShadowLog.clear()
        val logger = AndroidLogger()

        logger.log(LogEvent.ConfirmSheetClosed("confirm", "snooze unavailable: Offline"))

        val expected = "ConfirmSheetClosed sheet=confirm reason=snooze unavailable: Offline"
        val logs = ownLogs(expected)
        assertEquals(listOf(expected), logs.map { it.msg })
        assertEquals(listOf(Log.INFO), logs.map { it.type })
    }

    @Test
    fun `the logger writes one line at info level when all data was deleted (Story 5_9)`() {
        ShadowLog.clear()
        val logger = AndroidLogger()

        logger.log(LogEvent.AllDataDeleted(alarms = 2, keptRecords = 1, at = Instant.parse("2027-03-03T06:00:00Z")))

        val expected = "AllDataDeleted alarms=2 keptRecords=1 at=2027-03-03T06:00:00Z"
        val logs = ownLogs(expected)
        assertEquals(listOf(expected), logs.map { it.msg })
        assertEquals(listOf(Log.INFO), logs.map { it.type })
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
