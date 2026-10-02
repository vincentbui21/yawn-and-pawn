package com.yawnandpawn.app.android.wake

import android.app.NotificationManager
import android.os.Looper
import android.os.SystemClock
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 1.16: a ring nobody touches stops after 30 minutes on the monotonic clock and is logged Missed. The wall clock
 * is a [FakeClock] that only moves when a test says so; the main looper's clock (monotonic time) drives the service.
 */
@RunWith(RobolectricTestRunner::class)
class ForgottenAlarmTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")
    private val alarm = anAlarm(id = "alarm-a", requestCode = 1000)
    private val history = FakeSessionHistoryRepository()
    private val wall = FakeClock(scheduledAt)

    private class Ring(
        val app: WakeApp,
        val service: ServiceController<WakeService>,
    )

    private fun ring(): Ring {
        val app = WakeApp(history = history, clock = wall)
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        val service = app.ring(AlarmFired(alarm.id, scheduledAt))
        app.awaitRinging()
        return Ring(app, service)
    }

    /** Lets [duration] pass on the monotonic clock, running the service's timers as they come due. */
    private fun pass(duration: Duration) = shadowOf(Looper.getMainLooper()).idleFor(duration)

    /**
     * Waits, in real time only, until the tick loop is back from a tick that changed nothing and has armed its retry
     * 1 s from now on the main thread's clock. The tick itself runs on ApplicationScope threads, so without this the
     * next [pass] could start before the retry is armed, and the retry would land later than the test expects.
     */
    private fun WakeApp.awaitRetryArmed() =
        awaitUntil("the tick loop arms its 1 s retry") {
            shadowOf(Looper.getMainLooper()).nextScheduledTaskTime == Duration.ofMillis(SystemClock.uptimeMillis() + 1_000)
        }

    private fun Ring.assertStillRinging(at: String) {
        app.awaitUntil("the background work settles at $at") { app.engine.state.value is SessionState.Ring }
        assertIs<SessionState.Ringing>(app.engine.state.value, "ringing at $at")
        assertFalse(shadowOf(service.get()).isStoppedBySelf, "the service runs at $at")
    }

    /** The session was stopped as Missed: nothing rings, vibrates or shows, the service stopped, history has it. */
    private fun Ring.assertStoppedAsMissed() {
        // The service stops on Missed; the engine then writes history and reaches Idle on ApplicationScope.
        app.awaitUntil("the service stops and the session is recorded") {
            shadowOf(service.get()).isStoppedBySelf && app.engine.state.value == SessionState.Idle
        }
        assertNull(app.player.sound, "no sound")
        assertFalse(app.vibrator.isVibrating, "no vibration")
        assertEquals(0, shadowOf(app.app.getSystemService(NotificationManager::class.java)).size(), "no notification")
        assertEquals(SessionOutcome.Missed, history.rows.single().outcome)
    }

    @Test
    fun `30 minutes with no interaction stop the alarm, log it Missed and stop the service`() {
        val ring = ring()

        pass(Duration.ofMinutes(29).plusSeconds(59))
        ring.assertStillRinging("29:59")
        pass(Duration.ofSeconds(2))

        ring.assertStoppedAsMissed()
    }

    @Test
    fun `an interaction at minute 20 moves the stop to minute 50`() {
        val ring = ring()
        pass(Duration.ofMinutes(20))
        ring.app.dispatch(SessionEvent.UserInteracted)

        pass(Duration.ofMinutes(29).plusSeconds(59))
        ring.assertStillRinging("49:59")
        pass(Duration.ofSeconds(2))

        ring.assertStoppedAsMissed()
    }

    @Test
    fun `a wall clock set back an hour during the ring neither shortens nor extends the 30 minutes`() {
        val ring = ring()
        pass(Duration.ofMinutes(10))
        wall.set(scheduledAt + 10.minutes - 1.hours)

        pass(Duration.ofMinutes(19).plusSeconds(59))
        ring.assertStillRinging("29:59")
        pass(Duration.ofSeconds(2))

        ring.assertStoppedAsMissed()
    }

    @Test
    fun `a wall clock jump alone does not stop the alarm early`() {
        val ring = ring()
        wall.set(scheduledAt + 2.hours)

        pass(Duration.ofMinutes(5))

        ring.assertStillRinging("5:00 after a two-hour wall jump")
    }

    @Test
    fun `a timeout that cannot be saved is retried about once a second, never in a busy loop, until it is`() {
        val store = CommitCountingStore()
        val app = WakeApp(history = history, clock = wall, store = store)
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        val ring = Ring(app, app.ring(AlarmFired(alarm.id, scheduledAt)))
        app.awaitRinging()
        pass(Duration.ofMinutes(29).plusSeconds(59))
        ring.assertStillRinging("29:59")
        store.inner.commitFailure = DomainError.StorageFailure("disk full")
        val before = store.commitAttempts.get()

        // Exactly to the 30-minute deadline (not past it): the timeout's tick runs on ApplicationScope threads, and the
        // retry it arms is 1 s after the tick returns. Passing more time here could also cover that retry, depending on
        // how fast those threads are.
        pass(Duration.ofSeconds(1))
        app.awaitUntil("the timeout's commit fails") { store.commitAttempts.get() == before + 1 }
        app.awaitRetryArmed()
        // Without time passing nothing is retried: no busy loop (real time passes while the background threads run).
        repeat(IDLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(IDLE_ROUND_MILLIS)
        }
        assertEquals(before + 1, store.commitAttempts.get(), "no retry before a second passes")
        // Each second brings one retry, and not a moment sooner.
        (2..RETRIES).forEach { attempt ->
            pass(Duration.ofMillis(999))
            assertEquals(before + attempt - 1, store.commitAttempts.get(), "retry ${attempt - 1} not before a second")
            pass(Duration.ofMillis(1))
            app.awaitUntil("retry ${attempt - 1}") { store.commitAttempts.get() == before + attempt }
            app.awaitRetryArmed()
        }

        assertIs<SessionState.Ringing>(app.engine.state.value, "still ringing while the timeout cannot be saved")
        store.inner.commitFailure = null
        pass(Duration.ofSeconds(1))
        ring.assertStoppedAsMissed()
    }

    @Test
    fun `a slot fire after the timer was missed in deep sleep stops the alarm`() {
        // Deep sleep: time since boot moves on, but the main-thread timer (uptime) does not, so the service's own
        // 30-minute wait is still in the future. Only the slot's tick can stop the alarm.
        val monotonic = FakeMonotonicClock(elapsedMillis = START_ELAPSED_MILLIS)
        val app = WakeApp(history = history, clock = wall, monotonic = monotonic)
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        val ring = Ring(app, app.ring(AlarmFired(alarm.id, scheduledAt)))
        app.awaitRinging()
        monotonic.set(START_ELAPSED_MILLIS + 31.minutes.inWholeMilliseconds)

        ring.service.withIntent(WakeService.intent(app.app, WakeService.ACTION_SLOT)).startCommand(0, 2)

        ring.assertStoppedAsMissed()
    }

    private companion object {
        const val RETRIES = 3
        const val START_ELAPSED_MILLIS = 1_000_000L
        const val IDLE_ROUNDS = 20
        const val IDLE_ROUND_MILLIS = 10L
    }
}

/** The session store of the test, counting every commit attempt (failed ones too). */
private class CommitCountingStore(
    val inner: FakeActiveSessionStore = FakeActiveSessionStore(),
) : ActiveSessionStore by inner {
    val commitAttempts = AtomicInteger()

    override suspend fun commit(state: SessionState): Outcome<Unit, DomainError> =
        inner.commit(state).also { commitAttempts.incrementAndGet() }
}
