package com.yawnandpawn.app.android

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmRule
import com.yawnandpawn.app.core.alarm.nextOccurrence
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.stopApp
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AD-4: the app's start-up path (`YawnAndPawnApp.onCreate`) re-arms every stored alarm. */
@RunWith(RobolectricTestRunner::class)
class AppStartRescheduleTest {
    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()

    @After
    fun tearDown() {
        stopApp()
    }

    /** Waits for the start-up `rescheduleAll()` launched on the current Koin graph's application scope. */
    private fun awaitStartUp() = GlobalContext.get().get<ApplicationScope>().awaitChildren()

    @Test
    fun `app start arms exactly the enabled stored alarm at its next occurrence`() {
        awaitStartUp()
        val everyDay = DayOfWeek.entries.toSet()
        val enabled = anAlarm(id = "on", time = LocalTime(7, 0), repeatDays = everyDay, requestCode = 1000)
        val disabled = anAlarm(id = "off", time = LocalTime(8, 0), repeatDays = everyDay, enabled = false, requestCode = 1001)
        runBlocking {
            val repository = GlobalContext.get().get<AlarmRepository>()
            assertEquals(Outcome.Success(Unit), repository.upsert(enabled))
            assertEquals(Outcome.Success(Unit), repository.upsert(disabled))
        }
        val clock = GlobalContext.get().get<Clock>()
        val zone = GlobalContext.get().get<TimeZoneProvider>().current()

        // Restart the start-up path: a new Koin graph and a new start-up rescheduleAll over the stored alarms.
        stopApp()
        val before = clock.now()
        app.onCreate()
        awaitStartUp()
        val after = clock.now()

        val armed = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertEquals(listOf(enabled.requestCode), armed.map { shadowOf(it.operation).requestCode })
        val rule = AlarmRule(enabled.time, everyDay)
        // The real clock runs on between the two reads; the trigger is the next 07:00 after one of them.
        val expected = setOf(nextOccurrence(rule, before, zone), nextOccurrence(rule, after, zone)).map { it.toEpochMilliseconds() }
        assertTrue(armed.single().triggerAtTime in expected, "trigger ${armed.single().triggerAtTime}, expected one of $expected")
    }
}
