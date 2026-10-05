package com.yawnandpawn.app.android.wake

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.WakeStage
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Story 1.14 and device test round 1: the fire handler starts the wake service first, whatever happens to the scheduling
 * part, re-arms after, and returns only once the service took the start (bounded).
 */
@RunWith(RobolectricTestRunner::class)
class WakeAlarmFiredHandlerTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val starts = WakeServiceStarts()

    /** What happened, in order: "start <action>" for a service start, "re-arm" for the scheduling part. */
    private val steps = mutableListOf<String>()
    private val started = mutableListOf<Intent>()

    /** Like the system: the service reaches `onStartCommand` as soon as it is started. */
    private val starter =
        WakeServiceStarter(context, FakeLogger()) {
            started += it
            steps += "start ${it.action}"
            starts.onStartCommandReached()
        }
    private val fired = AlarmFired("alarm-a", Instant.fromEpochMilliseconds(1_000))
    private val repository = FakeAlarmRepository(listOf(anAlarm(id = "alarm-a")))

    private val logger = FakeLogger()
    private val timingLogger = FakeLogger()
    private val timings = WakeTimings(now = { fired.scheduledAt + 40.milliseconds }, logger = timingLogger)

    private fun handler(
        starter: WakeServiceStarter = this.starter,
        starts: WakeServiceStarts = this.starts,
        schedule: suspend (AlarmFired) -> Unit = { steps += "re-arm" },
    ) = WakeAlarmFiredHandler(
        repository,
        object : AlarmFiredHandler {
            override suspend fun onAlarmFired(fired: AlarmFired) = schedule(fired)

            override suspend fun onSessionSlotFired() = Unit

            override suspend fun onTestAlarmFired() = Unit
        },
        starter,
        logger,
        starts,
        timings,
    )

    private fun startedAlarms() = started.map { it.action to it.getStringExtra("alarmId") }

    @Test
    fun `an enabled alarm starts the service first and is re-armed after`() {
        timings.fired(fired.scheduledAt)

        runBlocking { handler().onAlarmFired(fired) }

        assertEquals(listOf("start ${WakeService.ACTION_ALARM}", "re-arm"), steps)
        assertEquals(listOf<LogEvent>(LogEvent.WakeTiming(WakeStage.ServiceStartRequested, 40)), timingLogger.events)
        assertEquals(emptyList(), logger.events, "the service took the start, so nothing is logged")
    }

    @Test
    fun `a re-arm that throws still starts the service`() {
        assertFailsWith<IllegalStateException> { runBlocking { handler { error("scheduler broke") }.onAlarmFired(fired) } }

        assertEquals(listOf(WakeService.ACTION_ALARM to "alarm-a"), startedAlarms())
    }

    @Test
    fun `a re-arm that overruns the receiver budget and is cancelled still starts the service`() {
        val finished = runBlocking { withTimeoutOrNull(50.milliseconds) { handler { awaitCancellation() }.onAlarmFired(fired) } }

        assertNull(finished, "timed out")
        assertEquals(listOf(WakeService.ACTION_ALARM to "alarm-a"), startedAlarms())
    }

    @Test
    fun `an alarm that cannot be read still rings, a deleted or disabled one does not`() {
        repository.failure = DomainError.StorageFailure("disk I/O error")
        runBlocking { handler {}.onAlarmFired(fired) }
        assertEquals(1, started.size, "unreadable rings the emergency default")

        repository.failure = null
        runBlocking { handler {}.onAlarmFired(AlarmFired("gone", fired.scheduledAt)) }
        runBlocking { repository.upsert(anAlarm(id = "alarm-a", enabled = false)) }
        runBlocking { handler {}.onAlarmFired(fired) }
        assertEquals(1, started.size)
    }

    @Test
    fun `a test fire starts the service with the test action, and a refused start is logged`() {
        runBlocking { handler().onTestAlarmFired() }
        assertEquals(listOf(WakeService.ACTION_TEST), started.map { it.action })

        val refusing = WakeServiceStarter(context, FakeLogger()) { throw IllegalStateException("background start not allowed") }
        runBlocking { handler(starter = refusing).onTestAlarmFired() }

        assertEquals(
            listOf<LogEvent>(LogEvent.OperationFailed("start test alarm", "service start refused; the test does not ring")),
            logger.events,
        )
    }

    @Test
    fun `the fire is handled only once the service reached onStartCommand`() =
        runTest {
            val slowStarts = WakeServiceStarts()
            // The system starts the service, but onStartCommand comes later (a frozen process).
            val slow = WakeServiceStarter(context, FakeLogger()) { started += it }

            val handling = async { handler(starter = slow, starts = slowStarts).onAlarmFired(fired) }
            advanceTimeBy(5.seconds)
            runCurrent()
            assertFalse(handling.isCompleted, "still waiting for the service after 5 s")
            assertEquals(1, started.size, "the start was requested at once")

            slowStarts.onStartCommandReached()
            runCurrent()

            assertTrue(handling.isCompleted, "done once the service took the start")
            assertEquals(emptyList(), logger.events)
        }

    @Test
    fun `a service that never reaches onStartCommand ends the wait after 6 s, logged, and the slot fire waits too`() =
        runTest {
            val never = WakeServiceStarter(context, FakeLogger()) { started += it }
            val waitLog = LogEvent.OperationFailed("wait for wake service", "no onStartCommand within 6s; the broadcast finishes")

            val handling = async { handler(starter = never, starts = WakeServiceStarts()).onAlarmFired(fired) }
            advanceTimeBy(5.9.seconds)
            runCurrent()
            assertFalse(handling.isCompleted)
            advanceTimeBy(0.2.seconds)
            runCurrent()
            assertTrue(handling.isCompleted)
            assertEquals(listOf<LogEvent>(waitLog), logger.events)

            val slot = async { handler(starter = never, starts = WakeServiceStarts()).onSessionSlotFired() }
            runCurrent()
            assertFalse(slot.isCompleted, "the slot fire keeps the broadcast open too")
            advanceTimeBy(6.1.seconds)
            runCurrent()
            assertTrue(slot.isCompleted)
            assertEquals(listOf<LogEvent>(waitLog, waitLog), logger.events)
            assertEquals(listOf(WakeService.ACTION_ALARM, WakeService.ACTION_SLOT), started.map { it.action })
        }
}
