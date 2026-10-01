package com.yawnandpawn.app.android

import android.content.Intent
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Story 1.10: a fired system alarm reaches the Epic 1 handler through the real PendingIntent and receiver. */
@RunWith(RobolectricTestRunner::class)
class AlarmFiredReceiverTest {
    private val berlin = TimeZone.of("Europe/Berlin")

    private fun berlin(text: String): Instant = LocalDateTime.parse(text).toInstant(berlin)

    // Sunday 2027-03-07, 20:00 in Berlin.
    private val app = SchedulingApp(berlin("2027-03-07T20:00"), "Europe/Berlin")
    private val scheduler: AlarmScheduler
        get() = app.koin.get()

    @After
    fun tearDown() {
        app.close()
    }

    /** Lets the system fire the alarm armed under [requestCode] at its trigger time. */
    private fun fire(requestCode: Int) {
        val alarm =
            app.alarmManager.scheduledAlarms.single {
                org.robolectric.Shadows
                    .shadowOf(it.operation)
                    .requestCode == requestCode
            }
        app.clock.set(Instant.fromEpochMilliseconds(alarm.triggerAtTime) + 30.milliseconds)
        app.alarmManager.fireAlarm(alarm)
        app.awaitWork()
    }

    @Test
    fun `a weekday alarm that fires on Monday at 07 00 is re-armed for Tuesday at 07 00`() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        runBlocking { app.repository.upsert(anAlarm(id = "weekday", repeatDays = weekdays, requestCode = 1000)) }
        scheduler.schedule("weekday", 1000, berlin("2027-03-08T07:00").toEpochMilliseconds())

        fire(1000)

        assertEquals(mapOf(1000 to berlin("2027-03-09T07:00").toEpochMilliseconds()), app.armed())
        assertEquals(
            true,
            runBlocking {
                app.repository
                    .get("weekday")
                    .valueOrNull()
                    ?.enabled
            },
        )
    }

    @Test
    fun `a one-time alarm that fires is switched off and nothing stays armed`() {
        runBlocking { app.repository.upsert(anAlarm(id = "once", requestCode = 1000)) }
        scheduler.schedule("once", 1000, berlin("2027-03-08T07:00").toEpochMilliseconds())

        fire(1000)

        assertEquals(emptyMap(), app.armed())
        assertEquals(
            false,
            runBlocking {
                app.repository
                    .get("once")
                    .valueOrNull()
                    ?.enabled
            },
        )
    }

    @Test
    fun `a fire for a deleted alarm arms nothing and is logged`() {
        scheduler.schedule("gone", 1000, berlin("2027-03-08T07:00").toEpochMilliseconds())
        ShadowLog.clear()

        fire(1000)

        assertEquals(emptyMap(), app.armed())
        assertTrue(ShadowLog.getLogsForTag(AndroidLogger.TAG).any { it.msg.startsWith("FireIgnored kind=Alarm alarmId=gone") })
    }

    @Test
    fun `session-slot and test fires are logged and ignored`() {
        scheduler.scheduleTest(berlin("2027-03-07T20:01").toEpochMilliseconds())
        runBlocking { assertEquals(Outcome.Success(Unit), scheduler.schedule("x", 1000, berlin("2027-03-08T07:00").toEpochMilliseconds())) }
        ShadowLog.clear()

        // A deadline from an earlier boot is armed at its wall time.
        scheduler.armSessionSlot(Deadline(berlin("2027-03-07T20:02").toEpochMilliseconds(), elapsedMillis = 0, bootCount = Int.MIN_VALUE))

        fire(RequestCodes.TEST_ALARM)
        fire(RequestCodes.SESSION_SLOT)

        assertEquals(setOf(1000), app.armed().keys)
        val logs = ShadowLog.getLogsForTag(AndroidLogger.TAG).map { it.msg }
        assertTrue(logs.any { it.startsWith("FireIgnored kind=TestAlarm") }, "$logs")
        assertTrue(logs.any { it.startsWith("FireIgnored kind=SessionSlot") }, "$logs")
    }

    @Test
    fun `an alarm broadcast without its extras is logged and ignored`() {
        ShadowLog.clear()

        app.app.sendBroadcast(AlarmFiredReceiver.intent(app.app, AlarmFiredReceiver.ACTION_ALARM))
        app.awaitWork()

        assertTrue(ShadowLog.getLogsForTag(AndroidLogger.TAG).any { it.msg.startsWith("FireIgnored kind=Alarm alarmId=null") })
    }

    @Test
    fun `an unknown action sent to the alarm receiver is logged and ignored`() {
        ShadowLog.clear()

        app.app.sendBroadcast(Intent("com.example.SPOOF").setClass(app.app, AlarmFiredReceiver::class.java))
        app.awaitWork()

        assertTrue(ShadowLog.getLogsForTag(AndroidLogger.TAG).any { it.msg.startsWith("FireIgnored") })
    }
}
