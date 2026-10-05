package com.yawnandpawn.app.android.wake

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.AlarmFiredReceiver
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.WakeStage
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionSlotRearm
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeTime
import com.yawnandpawn.app.testing.SchedulerCall
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Story 1.14 and device test round 1: the fire handler starts the wake service first, whatever happens to the scheduling
 * part, re-arms after, and returns only once the service took the start (bounded).
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
            starts.onStartCommandReached(it.token())
        }
    private val fired = AlarmFired("alarm-a", Instant.fromEpochMilliseconds(1_000))
    private val repository = FakeAlarmRepository(listOf(anAlarm(id = "alarm-a")))

    private val logger = FakeLogger()
    private val timingLogger = FakeLogger()
    private val timings = WakeTimings(now = { fired.scheduledAt + 40.milliseconds }, logger = timingLogger)

    // Story 2.1: a refused start re-arms the session slot through the real SessionSlotRearm.
    private val time = FakeTime(clock = FakeClock(fired.scheduledAt + 1.minutes))
    private val sessionStore = FakeActiveSessionStore()
    private val slotScheduler = FakeAlarmScheduler()
    private val rearm = SessionSlotRearm(sessionStore, slotScheduler, time.clock, time.monotonicClock, time.bootCounter, FakeLogger())
    private val refusing = WakeServiceStarter(context, FakeLogger()) { throw IllegalStateException("background start not allowed") }

    private fun handler(
        starter: WakeServiceStarter = this.starter,
        starts: WakeServiceStarts = this.starts,
        timeSource: TimeSource = TimeSource.Monotonic,
        schedule: suspend (AlarmFired) -> Unit = { steps += "re-arm" },
    ) = WakeAlarmFiredHandler(
        repository,
        object : AlarmFiredHandler {
            override suspend fun onAlarmFired(fired: AlarmFired) = schedule(fired)

            override suspend fun onSessionSlotFired(alarm: AlarmFired?) = Unit

            override suspend fun onTestAlarmFired() = Unit
        },
        starter,
        logger,
        starts,
        timings,
        timeSource,
        rearm,
    )

    @Test
    fun `a refused alarm start arms the session slot one heartbeat later carrying the alarm, and the alarm is still re-armed`() {
        runBlocking { handler(starter = refusing).onAlarmFired(fired) }

        val heartbeat = Deadline.after(time.snapshot(), SessionReducer.HEARTBEAT)
        assertEquals(listOf<SchedulerCall>(SchedulerCall.ArmSessionSlot(heartbeat, fired)), slotScheduler.calls)
        assertEquals(listOf("re-arm"), steps)
    }

    @Test
    fun `a slot fire starts the service with the alarm it carries, and a refused one re-arms the slot with it`() {
        runBlocking { handler().onSessionSlotFired(fired) }
        assertEquals(WakeService.ACTION_SLOT, started.single().action)
        assertEquals(fired.alarmId, started.single().getStringExtra(AlarmFiredReceiver.EXTRA_ALARM_ID))
        assertEquals(emptyList(), slotScheduler.calls, "nothing re-armed when the service started")

        runBlocking { handler(starter = refusing).onSessionSlotFired(fired) }

        assertEquals(
            listOf<SchedulerCall>(SchedulerCall.ArmSessionSlot(Deadline.after(time.snapshot(), SessionReducer.HEARTBEAT), fired)),
            slotScheduler.calls,
        )
    }

    @Test
    fun `a refused slot start with no session and no alarm arms nothing`() {
        runBlocking { handler(starter = refusing).onSessionSlotFired(null) }

        assertEquals(emptyList(), slotScheduler.calls, "an orphan slot is not kept alive")
    }

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

            val handling = async { handler(starter = slow, starts = slowStarts, timeSource = testTimeSource).onAlarmFired(fired) }
            advanceTimeBy(5.seconds)
            runCurrent()
            assertFalse(handling.isCompleted, "still waiting for the service after 5 s")
            assertEquals(1, started.size, "the start was requested at once")

            slowStarts.onStartCommandReached(slowStarts.newToken())
            runCurrent()
            assertFalse(handling.isCompleted, "another start (a restore, a slot) does not end this fire's wait")

            slowStarts.onStartCommandReached(started.single().token())
            runCurrent()

            assertTrue(handling.isCompleted, "done once the service took the start")
            assertEquals(emptyList(), logger.events)
        }

    @Test
    fun `a service that never reaches onStartCommand ends the wait after 6 s, logged, and the slot fire waits too`() =
        runTest {
            val never = WakeServiceStarter(context, FakeLogger()) { started += it }
            val waitLog = LogEvent.OperationFailed("wait for wake service", "no onStartCommand within 6s; the broadcast finishes")

            val handling = async { handler(starter = never, starts = WakeServiceStarts(), timeSource = testTimeSource).onAlarmFired(fired) }
            advanceTimeBy(5.9.seconds)
            runCurrent()
            assertFalse(handling.isCompleted)
            advanceTimeBy(0.2.seconds)
            runCurrent()
            assertTrue(handling.isCompleted)
            assertEquals(listOf<LogEvent>(waitLog), logger.events)

            val slot =
                async { handler(starter = never, starts = WakeServiceStarts(), timeSource = testTimeSource).onSessionSlotFired(null) }
            runCurrent()
            assertFalse(slot.isCompleted, "the slot fire keeps the broadcast open too")
            advanceTimeBy(6.1.seconds)
            runCurrent()
            assertTrue(slot.isCompleted)
            assertEquals(listOf<LogEvent>(waitLog, waitLog), logger.events)
            assertEquals(listOf(WakeService.ACTION_ALARM, WakeService.ACTION_SLOT), started.map { it.action })
        }

    @Test
    fun `a start that throws something unexpected still re-arms the alarm`() {
        val broken = WakeServiceStarter(context, FakeLogger()) { throw UnsupportedOperationException("platform bug") }

        assertFailsWith<UnsupportedOperationException> { runBlocking { handler(starter = broken).onAlarmFired(fired) } }

        assertEquals(listOf("re-arm"), steps, "the next occurrence is armed anyway")
    }

    @Test
    fun `the service wait counts from the fire's start, so a slow re-arm never pushes it past the 8 s budget`() =
        runTest {
            val never = WakeServiceStarter(context, FakeLogger()) { started += it }

            val handling =
                async {
                    handler(starter = never, starts = WakeServiceStarts(), timeSource = testTimeSource) { delay(4.seconds) }
                        .onAlarmFired(fired)
                }
            advanceTimeBy(5.9.seconds)
            runCurrent()
            assertFalse(handling.isCompleted)
            advanceTimeBy(0.2.seconds)
            runCurrent()

            assertTrue(handling.isCompleted, "done 6 s after the fire, not 4 s + 6 s")
        }

    @Test
    fun `a start already taken ends the wait at once, also with no time left, and logs nothing`() {
        val zero = WakeServiceStarts(Duration.ZERO)
        val system = WakeServiceStarter(context, FakeLogger()) { zero.onStartCommandReached(it.token()) }

        runBlocking { handler(starter = system, starts = zero).onAlarmFired(fired) }
        runBlocking { handler(starter = system, starts = zero).onTestAlarmFired() }

        assertEquals(emptyList(), logger.events)
    }

    @Test
    fun `a test fire waits for its own start and logs a wait that times out`() =
        runTest {
            val slowStarts = WakeServiceStarts()
            val slow = WakeServiceStarter(context, FakeLogger()) { started += it }

            val handling = async { handler(starter = slow, starts = slowStarts, timeSource = testTimeSource).onTestAlarmFired() }
            advanceTimeBy(3.seconds)
            slowStarts.onStartCommandReached(slowStarts.newToken())
            runCurrent()
            assertFalse(handling.isCompleted, "another start does not end the test's wait")
            slowStarts.onStartCommandReached(started.single().token())
            runCurrent()
            assertTrue(handling.isCompleted)
            assertEquals(emptyList(), logger.events)

            val timingOut = async { handler(starter = slow, starts = slowStarts, timeSource = testTimeSource).onTestAlarmFired() }
            advanceTimeBy(6.1.seconds)
            runCurrent()
            assertTrue(timingOut.isCompleted)
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed("wait for wake service", "no onStartCommand within 6s; the broadcast finishes")),
                logger.events,
            )
        }

    /** The start token the handler put on [this] start intent. */
    private fun Intent.token(): Long = getLongExtra(WakeService.EXTRA_START_TOKEN, -1)
}
