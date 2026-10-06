package com.yawnandpawn.app.android

import android.content.Intent
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionSlotRearm
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.aSession
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
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

    /** Saves [draft] in the app, once the stored session is restored (the alarms are editable then, Story 2.6). */
    private fun save(draft: AlarmDraft) =
        runBlocking {
            app.koin.get<SessionEngine>().restore()
            app.koin.get<SaveAlarm>()(draft)
        }

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
        assertIs<Outcome.Success<*>>(save(AlarmDraft(time = LocalTime(7, 0))))

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
        save(AlarmDraft(time = LocalTime(7, 0)))
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

    /** Binds [store] as runtime.db for the slot re-arm (Story 2.1 review tests). */
    private fun bindSessionStore(store: ActiveSessionStore) =
        loadKoinModules(
            module {
                single<ActiveSessionStore> { store }
                single { SessionSlotRearm(get(), get(), get(), get(), get(), get()) }
            },
        )

    private fun slots() = app.alarmManager.scheduledAlarms.filter { shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT }

    private fun logs() = ShadowLog.getLogsForTag(AndroidLogger.TAG).map { it.msg }

    @Test
    fun `the alarms are re-armed before the session slot is read, so a slow runtime db cannot leave them unarmed`() {
        storeTwoEnabledAndOneDisabled()
        var armedWhenSlotRead: Map<Int, Long>? = null
        val stored = FakeActiveSessionStore()
        bindSessionStore(
            object : ActiveSessionStore by stored {
                override suspend fun load(): Outcome<StoredSession, DomainError> {
                    armedWhenSlotRead = app.armed()
                    return stored.load()
                }
            },
        )

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(expectedTwoEnabled, armedWhenSlotRead)
    }

    @Test
    fun `a session store that cannot be read is logged, arms no slot and the alarms are still re-armed`() {
        storeTwoEnabledAndOneDisabled()
        bindSessionStore(FakeActiveSessionStore().apply { loadFailure = DomainError.StorageFailure("disk I/O error") })
        ShadowLog.clear()

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(expectedTwoEnabled, app.armed())
        assertEquals(emptyList(), slots())
        assertTrue(logs().any { it.startsWith("OperationFailed operation=read session for slot") }, "${logs()}")
    }

    @Test
    fun `a time change, a zone change and an app update arm the slot at once for a stored ringing session`() {
        val ringing = FakeActiveSessionStore()
        bindSessionStore(ringing)
        assertEquals(Outcome.Success(Unit), runBlocking { ringing.commit(SessionState.Ringing(aSession())) })

        listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach { action ->
            app.clearArmed()

            broadcast(action)

            val slot = slots().singleOrNull()
            assertEquals(app.clock.now().toEpochMilliseconds() + 1_000, slot?.triggerAtTime, action)
        }
        assertEquals(emptyList(), shadowOf(app.app).allStartedServices, "no service from a system event")
    }

    @Test
    fun `an app update cancels the session slot the previous version armed to the alarm receiver`() {
        val operation = app.armLegacySessionSlot(berlin("2027-03-03T06:01").toEpochMilliseconds())

        broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertEquals(emptyList(), slots(), "nothing stored, so no new slot either")
        assertTrue(shadowOf(operation).isCanceled)
    }

    @Test
    fun `any other action sent straight to the receiver is ignored`() {
        store(anAlarm(id = "early", time = LocalTime(6, 30), requestCode = 1000))

        app.app.sendBroadcast(Intent("com.example.SPOOF").setClass(app.app, SystemEventsReceiver::class.java))
        app.awaitWork()

        assertEquals(emptyMap(), app.armed())
    }
}
