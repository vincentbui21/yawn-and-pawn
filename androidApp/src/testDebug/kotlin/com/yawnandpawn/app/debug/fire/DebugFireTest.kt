package com.yawnandpawn.app.debug.fire

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aRegisteredCode
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.checkConfigsOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Story 1.18: the debug fire-now hook arms a real or test ring N seconds ahead through the real scheduler port. */
@RunWith(RobolectricTestRunner::class)
class DebugFireTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val clock = FakeClock()
    private val scheduler = FakeAlarmScheduler()
    private val testAlarms = FakeTestAlarmStore()
    private val repository = FakeAlarmRepository()
    private val fixture = AlarmUseCasesFixture(repository = repository, clock = clock, scheduler = scheduler)
    private val debugFire =
        DebugFire(
            fixture.repository,
            fixture.save,
            scheduler,
            ScheduleTestAlarm(scheduler, testAlarms, clock, FakeLogger()),
            clock,
            FakeTimeZoneProvider(),
            checkConfigs = fixture.checkConfigs,
        )

    private fun inSeconds(seconds: Int) = clock.now().toEpochMilliseconds() + seconds * 1_000L

    @Test
    fun `a test fire with no alarm id arms a test with the defaults`() {
        val armed = runBlocking { debugFire.fire(DebugFire.FireRequest(seconds = 5, test = true)) }

        assertEquals(Outcome.Success(clock.now() + 5.seconds), armed)
        assertEquals(inSeconds(5), scheduler.armed[RequestCodes.TEST_ALARM])
        assertTrue(testAlarms.pending!!.testMode)
        assertEquals(DebugFire.LABEL, testAlarms.pending!!.label)
    }

    @Test
    fun `a test fire of a stored alarm uses its values`() {
        val stored = anAlarm(id = "alarm-a", requestCode = 1001, label = "Work")
        runBlocking { fixture.repository.upsert(stored) }

        runBlocking { debugFire.fire(DebugFire.FireRequest(seconds = 5, alarmId = "alarm-a", test = true)) }

        assertEquals("alarm-a", testAlarms.pending?.alarmId)
        assertEquals("Work", testAlarms.pending?.label)
    }

    @Test
    fun `a test fire of a stored alarm rings its own checks, and the default ones without (Epic 3 device check)`() {
        val qr = CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, code = aRegisteredCode())
        val stored = anAlarm(id = "alarm-a", requestCode = 1001).copy(checkMode = CheckMode.All)
        runBlocking { fixture.checkConfigs.saveWithAlarm(stored, checkConfigsOf(stored.id, listOf(qr))) }

        runBlocking { debugFire.fire(DebugFire.FireRequest(seconds = 5, alarmId = "alarm-a", test = true)) }
        assertEquals(CheckPlan(CheckMode.All, listOf(qr)), testAlarms.pending?.checkPlan)

        runBlocking { debugFire.fire(DebugFire.FireRequest(seconds = 5, test = true)) }
        assertEquals(CheckPlan.default(), testAlarms.pending?.checkPlan)
    }

    @Test
    fun `a real fire of a stored alarm arms it under its own code`() {
        runBlocking { fixture.repository.upsert(anAlarm(id = "alarm-a", requestCode = 1001)) }

        runBlocking { debugFire.fire(DebugFire.FireRequest(seconds = 30, alarmId = "alarm-a")) }

        assertEquals(inSeconds(30), scheduler.armed[1001])
    }

    @Test
    fun `a real fire with no alarm id saves a one-time Debug fire alarm and arms it`() {
        val armed = runBlocking { debugFire.fire(DebugFire.FireRequest(seconds = 3)) }

        assertIs<Outcome.Success<*>>(armed)
        val saved = repository.current.single()
        assertEquals(DebugFire.LABEL, saved.label)
        assertTrue(saved.repeatDays.isEmpty())
        assertEquals(inSeconds(3), scheduler.armed[saved.requestCode])
    }

    @Test
    fun `an unknown alarm id arms nothing, and the delay is clamped`() {
        assertIs<Outcome.Failure<DomainError>>(runBlocking { debugFire.fire(DebugFire.FireRequest(alarmId = "missing")) })
        assertTrue(scheduler.armed.isEmpty())

        runBlocking { debugFire.fire(DebugFire.FireRequest(seconds = 0, test = true)) }
        assertEquals(inSeconds(1), scheduler.armed[RequestCodes.TEST_ALARM])
    }

    @Test
    fun `the receiver is registered for the fire action behind the shell's DUMP permission`() {
        val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()

        DebugFireReceiver.register(app)

        val registered = shadowOf(app).registeredReceivers.single { it.broadcastReceiver is DebugFireReceiver }
        assertTrue(registered.intentFilter.hasAction(DebugFire.ACTION))
        assertEquals(DebugFireReceiver.SENDER_PERMISSION, registered.broadcastPermission)
        assertTrue(shadowOf(app).getReceiversForIntent(Intent(DebugFire.ACTION)).any { it is DebugFireReceiver })
    }
}
