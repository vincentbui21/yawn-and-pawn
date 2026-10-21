package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reset.DeleteAllData
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.data.reset.FileDeletionMarker
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Story 5.9: "Delete all data" on the real app (Koin graph, Room, `AndroidAlarmScheduler`) leaves nothing armed in
 * AlarmManager: two saved alarms, the test alarm and the session slot are all cancelled, and the marker is gone.
 */
@RunWith(RobolectricTestRunner::class)
class DeleteAllDataAlarmsTest {
    // Wednesday 2027-03-03, 06:00 in Berlin.
    private val app = SchedulingApp(Instant.parse("2027-03-03T05:00:00Z"), "Europe/Berlin")

    @After
    fun tearDown() {
        app.close()
    }

    @Test
    fun `deleting all data cancels both alarms, the test alarm and the session slot, so AlarmManager holds nothing`() {
        val koin = app.koin
        runBlocking { koin.get<SessionEngine>().restore() }
        val save = koin.get<SaveAlarm>()
        assertIs<Outcome.Success<Alarm>>(runBlocking { save(AlarmDraft(time = LocalTime(7, 0))) })
        assertIs<Outcome.Success<Alarm>>(runBlocking { save(AlarmDraft(time = LocalTime(8, 30), repeatDays = setOf(DayOfWeek.MONDAY))) })
        val scheduler = koin.get<AlarmScheduler>()
        assertEquals(Outcome.Success(Unit), scheduler.scheduleTest((app.clock.now() + 10.seconds).toEpochMilliseconds()))
        val now = TimeSnapshot.of(app.clock, koin.get(), koin.get())
        assertEquals(Outcome.Success(Unit), scheduler.armSessionSlot(Deadline.after(now, 9.minutes)))
        assertEquals(setOf(1000, 1001, RequestCodes.TEST_ALARM, RequestCodes.SESSION_SLOT), app.armed().keys)

        val deleteAllData = koin.get<DeleteAllData>()
        assertEquals(Outcome.Success(Unit), runBlocking { deleteAllData() })

        assertEquals(emptyList<ShadowAlarmManager.ScheduledAlarm>(), app.alarmManager.scheduledAlarms)
        assertEquals(Outcome.Success(emptyList<Alarm>()), runBlocking { app.repository.listAll() })
        assertFalse(FileDeletionMarker.markerFile(app.app).exists())
        assertFalse(deleteAllData.pending.value)
    }
}
