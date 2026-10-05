package com.yawnandpawn.app.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.stopApp
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeTime
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class AndroidAlarmSchedulerTest {
    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()
    private val alarmManager: ShadowAlarmManager = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val time = FakeTime()
    private val logger = FakeLogger()
    private val scheduler = AndroidAlarmScheduler(app, time.clock, time.monotonicClock, time.bootCounter, logger)
    private val trigger = 1_804_143_600_000L

    @After
    fun tearDown() {
        stopApp()
    }

    private fun only(): ShadowAlarmManager.ScheduledAlarm = alarmManager.scheduledAlarms.single()

    @Test
    fun `schedule arms exactly one alarm clock at the trigger, with an immutable broadcast carrying the extras and the code`() {
        assertEquals(Outcome.Success(Unit), scheduler.schedule("alarm-1", 1000, trigger))

        val alarm = only()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(trigger, alarm.triggerAtTime)
        assertEquals(trigger, assertNotNull(alarm.alarmClockInfo).triggerTime)
        val operation = shadowOf(alarm.operation)
        assertTrue(operation.isBroadcast)
        assertTrue(operation.isImmutable)
        assertEquals(1000, operation.requestCode)
        val intent = operation.savedIntent
        assertEquals(ComponentName(app, AlarmFiredReceiver::class.java), intent.component)
        assertEquals(AlarmFiredReceiver.ACTION_ALARM, intent.action)
        assertEquals("alarm-1", intent.getStringExtra(AlarmFiredReceiver.EXTRA_ALARM_ID))
        assertEquals(trigger, intent.getLongExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, 0))
    }

    @Test
    fun `the show intent is an immutable activity intent opening MainActivity`() {
        scheduler.schedule("alarm-1", 1000, trigger)

        val show = shadowOf(assertNotNull(only().alarmClockInfo).showIntent)
        assertTrue(show.isActivity)
        assertTrue(show.isImmutable)
        assertEquals(ComponentName(app, MainActivity::class.java), show.savedIntent.component)
    }

    @Test
    fun `scheduling a code again replaces its alarm, and other codes stay armed`() {
        scheduler.schedule("a", 1000, trigger)
        scheduler.schedule("b", 1001, trigger + 1)
        scheduler.schedule("a", 1000, trigger + 2)

        val byCode = alarmManager.scheduledAlarms.associate { shadowOf(it.operation).requestCode to it.triggerAtTime }
        assertEquals(mapOf(1000 to trigger + 2, 1001 to trigger + 1), byCode)
    }

    @Test
    fun `cancel removes the alarm with that code and cancels its PendingIntent`() {
        scheduler.schedule("a", 1000, trigger)
        scheduler.schedule("b", 1001, trigger)
        val operation = alarmManager.scheduledAlarms.first { shadowOf(it.operation).requestCode == 1000 }.operation

        assertEquals(Outcome.Success(Unit), scheduler.cancel(1000))

        assertEquals(listOf(1001), alarmManager.scheduledAlarms.map { shadowOf(it.operation).requestCode })
        assertTrue(shadowOf(operation).isCanceled)
    }

    @Test
    fun `cancelling a code that is not armed succeeds and changes nothing`() {
        scheduler.schedule("a", 1000, trigger)

        assertEquals(Outcome.Success(Unit), scheduler.cancel(1234))

        assertEquals(1, alarmManager.scheduledAlarms.size)
    }

    @Test
    fun `the session slot and the test alarm use their reserved codes and their own actions`() {
        val deadline = Deadline.after(time.snapshot(), 60.seconds)

        scheduler.armSessionSlot(deadline)
        scheduler.scheduleTest(trigger)
        scheduler.schedule("a", 1000, trigger)

        val byCode = alarmManager.scheduledAlarms.associateBy { shadowOf(it.operation).requestCode }
        assertEquals(setOf(RequestCodes.SESSION_SLOT, RequestCodes.TEST_ALARM, 1000), byCode.keys)
        val slotAlarm = byCode.getValue(RequestCodes.SESSION_SLOT)
        val slot = shadowOf(slotAlarm.operation).savedIntent
        val test = shadowOf(byCode.getValue(RequestCodes.TEST_ALARM).operation).savedIntent
        assertEquals(SessionSlotReceiver.ACTION_SESSION_SLOT, slot.action)
        assertEquals(ComponentName(app, SessionSlotReceiver::class.java), slot.component, "Story 2.1: the slot has its own receiver")
        assertTrue(shadowOf(slotAlarm.operation).isBroadcast)
        assertTrue(shadowOf(slotAlarm.operation).isImmutable)
        assertEquals(
            ComponentName(app, MainActivity::class.java),
            shadowOf(assertNotNull(slotAlarm.alarmClockInfo).showIntent).savedIntent.component,
            "the show intent opens the app",
        )
        assertEquals(AlarmFiredReceiver.ACTION_TEST_ALARM, test.action)
        assertNull(slot.getStringExtra(AlarmFiredReceiver.EXTRA_ALARM_ID))
        assertEquals(trigger, test.getLongExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, 0))

        scheduler.cancelSessionSlot()
        scheduler.cancel(RequestCodes.TEST_ALARM)
        assertEquals(listOf(1000), alarmManager.scheduledAlarms.map { shadowOf(it.operation).requestCode })
    }

    @Test
    fun `re-arming the slot replaces it and what it carries - one pending slot, the alarm it stands for, then none`() {
        val alarm = AlarmFired("alarm-b", Instant.fromEpochMilliseconds(trigger))

        scheduler.armSessionSlot(Deadline.after(time.snapshot(), 60.seconds), alarm)
        val carrying = shadowOf(only().operation).savedIntent
        assertEquals("alarm-b", carrying.getStringExtra(AlarmFiredReceiver.EXTRA_ALARM_ID))
        assertEquals(trigger, carrying.getLongExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, 0))

        scheduler.armSessionSlot(Deadline.after(time.snapshot(), 30.seconds))

        val slot = only()
        assertEquals((time.clock.now() + 30.seconds).toEpochMilliseconds(), slot.triggerAtTime)
        // FLAG_UPDATE_CURRENT: the system alarm and the extras it carries are replaced.
        assertNull(
            shadowOf(slot.operation).savedIntent.getStringExtra(AlarmFiredReceiver.EXTRA_ALARM_ID),
            "the new arming carries no alarm",
        )
        assertEquals(Outcome.Success(Unit), scheduler.cancelSessionSlot())
        assertTrue(alarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun `a slot deadline from this boot is armed at now plus the monotonic time left, even after a wall-clock jump`() {
        val deadline = Deadline.after(time.snapshot(), 9.minutes)
        time.advanceBy(1.minutes)
        // The user sets the clock back an hour: the slot must still fire 8 real minutes from now.
        time.setWall(time.clock.now() - 1.hours)

        scheduler.armSessionSlot(deadline)

        assertEquals((time.clock.now() + 8.minutes).toEpochMilliseconds(), only().triggerAtTime)
    }

    @Test
    fun `a slot deadline from another boot is armed at its wall time`() {
        val deadline = Deadline.after(time.snapshot(), 9.minutes)
        time.reboot()
        time.setWall(time.clock.now() + 2.minutes)

        scheduler.armSessionSlot(deadline)

        assertEquals(deadline.wallMillis, only().triggerAtTime)
    }

    @Test
    @Config(sdk = [31])
    fun `on API 31 without the exact-alarm permission nothing is armed and ExactAlarmNotPermitted is logged`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertEquals(Outcome.Failure(DomainError.ExactAlarmNotPermitted), scheduler.schedule("a", 1000, trigger))
        assertEquals(Outcome.Failure(DomainError.ExactAlarmNotPermitted), scheduler.scheduleTest(trigger))
        assertEquals(
            Outcome.Failure(DomainError.ExactAlarmNotPermitted),
            scheduler.armSessionSlot(Deadline.after(time.snapshot(), 1.minutes)),
        )

        assertTrue(alarmManager.scheduledAlarms.isEmpty(), "no inexact fallback")
        assertEquals(3, logger.events.size)
        val event = assertIs<LogEvent.OperationFailed>(logger.events.first())
        assertTrue(event.cause.startsWith("exact alarms not permitted on API 31"), event.cause)
    }

    @Test
    @Config(sdk = [32])
    fun `on API 32 without the exact-alarm permission nothing is armed`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertEquals(Outcome.Failure(DomainError.ExactAlarmNotPermitted), scheduler.schedule("a", 1000, trigger))
        assertTrue(alarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    @Config(sdk = [31])
    fun `on API 31 with the exact-alarm permission the alarm clock is armed`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        assertEquals(Outcome.Success(Unit), scheduler.schedule("a", 1000, trigger))
        assertEquals(trigger, only().triggerAtTime)
        assertTrue(logger.events.isEmpty())
    }

    private fun assertUseExactAlarmIsEnough() {
        // Robolectric reports canScheduleExactAlarms() false by default; API 33+ must not look at it.
        assertFalse(app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())

        assertEquals(Outcome.Success(Unit), scheduler.schedule("a", 1000, trigger))
        assertEquals(trigger, only().triggerAtTime)
    }

    @Test
    @Config(sdk = [33])
    fun `on API 33 USE_EXACT_ALARM is enough and the permission check is not consulted`() = assertUseExactAlarmIsEnough()

    @Test
    fun `on API 34 USE_EXACT_ALARM is enough and the permission check is not consulted`() = assertUseExactAlarmIsEnough()

    @Test
    @Config(shadows = [SecurityExceptionAlarmManager::class])
    fun `a SecurityException from setAlarmClock is ExactAlarmNotPermitted, logged, with nothing armed`() {
        assertEquals(Outcome.Failure(DomainError.ExactAlarmNotPermitted), scheduler.schedule("a", 1000, trigger))

        assertTrue(alarmManager.scheduledAlarms.isEmpty(), "no inexact fallback")
        val event = assertIs<LogEvent.OperationFailed>(logger.events.single())
        assertEquals("set alarm clock", event.operation)
        assertTrue(event.cause.startsWith("exact alarms not permitted on API 34 (code 1000)"), event.cause)
        assertTrue(event.cause.endsWith("exact alarms are off"), event.cause)
    }

    @Test
    @Config(shadows = [AlarmLimitAlarmManager::class])
    fun `any other exception from setAlarmClock is a SchedulerFailure, logged, so callers keep going`() {
        val outcome = scheduler.schedule("a", 1000, trigger)

        assertEquals(
            Outcome.Failure(DomainError.SchedulerFailure("IllegalStateException: Maximum limit of concurrent alarms 500 reached")),
            outcome,
        )
        assertTrue(alarmManager.scheduledAlarms.isEmpty())
        val event = assertIs<LogEvent.OperationFailed>(logger.events.single())
        assertEquals("refused (code 1000): IllegalStateException: Maximum limit of concurrent alarms 500 reached", event.cause)
    }

    @Test
    fun `the PendingIntents are immutable flags-wise too`() {
        scheduler.schedule("a", 1000, trigger)

        assertTrue(shadowOf(only().operation).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(shadowOf(assertNotNull(only().alarmClockInfo).showIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }
}

/** `setAlarmClock` throws as when the exact-alarm permission is revoked under the app. */
@Implements(AlarmManager::class)
class SecurityExceptionAlarmManager : ShadowAlarmManager() {
    @Implementation
    public override fun setAlarmClock(
        info: AlarmManager.AlarmClockInfo,
        operation: PendingIntent,
    ): Unit = throw SecurityException("exact alarms are off")
}

/** `setAlarmClock` throws as when the app hits the per-app alarm limit. */
@Implements(AlarmManager::class)
class AlarmLimitAlarmManager : ShadowAlarmManager() {
    @Implementation
    public override fun setAlarmClock(
        info: AlarmManager.AlarmClockInfo,
        operation: PendingIntent,
    ): Unit = throw IllegalStateException("Maximum limit of concurrent alarms 500 reached")
}
