package com.yawnandpawn.app.android

import android.content.Intent
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.error.Outcome
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/** Story 1.10: every enabled alarm re-armed and every disabled code cancelled on each reschedule broadcast. */
@RunWith(RobolectricTestRunner::class)
class SystemEventsReceiverTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val newYork = TimeZone.of("America/New_York")

    private fun berlin(text: String): Instant = LocalDateTime.parse(text).toInstant(berlin)

    // Wednesday 2027-03-03, 06:00 in Berlin.
    private val app = SchedulingApp(berlin("2027-03-03T06:00"), "Europe/Berlin")
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    @After
    fun tearDown() {
        app.close()
    }

    private fun store(vararg alarms: com.yawnandpawn.app.core.alarm.Alarm) =
        runBlocking { alarms.forEach { assertEquals(Outcome.Success(Unit), app.repository.upsert(it)) } }

    private fun broadcast(action: String) {
        app.app.sendBroadcast(Intent(action).setPackage(app.app.packageName))
        app.awaitWork()
    }

    /** Two enabled alarms and a disabled one whose code is still armed from before (it must be cancelled). */
    private fun storeTwoEnabledAndOneDisabled() {
        store(
            anAlarm(id = "early", time = LocalTime(6, 30), requestCode = 1000),
            anAlarm(id = "weekdays", time = LocalTime(22, 0), repeatDays = weekdays, requestCode = 1001),
            anAlarm(id = "off", time = LocalTime(9, 0), enabled = false, requestCode = 1002),
        )
        app.koin.get<AlarmScheduler>().schedule("off", 1002, berlin("2027-03-03T09:00").toEpochMilliseconds())
    }

    private val expectedTwoEnabled =
        mapOf(
            1000 to berlin("2027-03-03T06:30").toEpochMilliseconds(),
            1001 to berlin("2027-03-03T22:00").toEpochMilliseconds(),
        )

    @Test
    fun `an enabled alarm saved in the app arms exactly one alarm clock at its next occurrence`() {
        runBlocking { assertIs<Outcome.Success<*>>(app.koin.get<SaveAlarm>()(AlarmDraft(time = LocalTime(7, 0)))) }

        assertEquals(mapOf(1000 to berlin("2027-03-03T07:00").toEpochMilliseconds()), app.armed())
    }

    @Test
    fun `boot completed arms every enabled alarm and cancels every disabled code`() {
        storeTwoEnabledAndOneDisabled()

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(expectedTwoEnabled, app.armed())
    }

    @Test
    fun `boot completed twice gives the same alarms`() {
        storeTwoEnabledAndOneDisabled()

        broadcast(Intent.ACTION_BOOT_COMPLETED)
        val first = app.armed()
        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(first, app.armed())
        assertEquals(2, app.alarmManager.scheduledAlarms.size)
    }

    @Test
    fun `a time set on a DST spring-forward day arms a 02 30 alarm at 03 30 local`() {
        // Saved the evening before, so 02:30 on the 28th is the occurrence it was armed for.
        store(anAlarm(id = "night", time = LocalTime(2, 30), requestCode = 1000, createdAt = berlin("2027-03-27T22:00")))
        // Berlin skips 02:00 to 03:00 on Sunday 2027-03-28; the user sets the clock to just after midnight.
        app.clock.set(berlin("2027-03-28T00:05"))

        broadcast(Intent.ACTION_TIME_CHANGED)

        assertEquals(mapOf(1000 to Instant.parse("2027-03-28T01:30:00Z").toEpochMilliseconds()), app.armed())
    }

    @Test
    fun `a zone change from Berlin to New York re-arms the alarm at 07 00 New York time`() {
        runBlocking { app.koin.get<SaveAlarm>()(AlarmDraft(time = LocalTime(7, 0))) }
        assertEquals(mapOf(1000 to berlin("2027-03-03T07:00").toEpochMilliseconds()), app.armed())

        app.setZone("America/New_York")
        broadcast(Intent.ACTION_TIMEZONE_CHANGED)

        assertEquals(mapOf(1000 to LocalDateTime.parse("2027-03-03T07:00").toInstant(newYork).toEpochMilliseconds()), app.armed())
    }

    @Test
    fun `an app update re-arms every alarm`() {
        storeTwoEnabledAndOneDisabled()

        broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertEquals(expectedTwoEnabled, app.armed())
    }

    @Test
    fun `an exact-alarm permission change re-arms every alarm`() {
        storeTwoEnabledAndOneDisabled()

        broadcast("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")

        assertEquals(expectedTwoEnabled, app.armed())
    }

    @Test
    fun `any other action sent straight to the receiver is ignored`() {
        store(anAlarm(id = "early", time = LocalTime(6, 30), requestCode = 1000))

        app.app.sendBroadcast(Intent("com.example.SPOOF").setClass(app.app, SystemEventsReceiver::class.java))
        app.awaitWork()

        assertEquals(emptyMap(), app.armed())
    }
}
