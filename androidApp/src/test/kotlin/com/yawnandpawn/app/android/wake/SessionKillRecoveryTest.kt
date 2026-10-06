package com.yawnandpawn.app.android.wake

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.SessionSlotReceiver
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 2.1: a kill during a session is recovered within one slot delivery. A "kill" discards the process (the wake
 * service, the engine and its Koin graph) and boots a new one over the same Room `runtime.db` and `app.db` in
 * device-protected storage; the armed session slot is then delivered through [SessionSlotReceiver], as the system would.
 */
@RunWith(RobolectricTestRunner::class)
class SessionKillRecoveryTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")
    private val alarmA = anAlarm(id = "alarm-a", requestCode = 1000).copy(gradualVolume = true)
    private val fired = AlarmFired(alarmA.id, scheduledAt)

    /** The running process: its app graph and its wake service. */
    private class Process(
        val app: WakeApp,
        val service: ServiceController<WakeService>,
    )

    private fun slots(app: WakeApp): List<ShadowAlarmManager.ScheduledAlarm> =
        shadowOf(app.app.getSystemService(AlarmManager::class.java)).scheduledAlarms.filter {
            shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT
        }

    /** Alarm A rings in a first process: Ringing in runtime.db and its history start row in app.db. */
    private fun ringFirst(): Process {
        val app = WakeApp()
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarmA) })
        val service = app.ring(fired)
        app.awaitRinging()
        // The start row is written after the first sound; the test kills only once it is in, as a real process holding a
        // half-done write would release its database lock when it dies (here the closed instance would keep it).
        val sessionId = (app.engine.state.value as SessionState.Ringing).session.sessionId
        app.awaitUntil("the history start row is written") {
            (runBlocking { app.koin.get<SessionHistoryRepository>().find(sessionId) } as? Outcome.Success)?.value != null
        }
        return Process(app, service)
    }

    /** The session as the dead process left it in runtime.db. */
    private fun stored(app: WakeApp): SessionState {
        val loaded = assertIs<Outcome.Success<StoredSession>>(runBlocking { app.koin.get<ActiveSessionStore>().load() })
        return assertIs<StoredSession.Found>(loaded.value).state
    }

    /** The last process also committed [state] (and armed its slot as it would have), then died. */
    private fun commit(
        process: Process,
        state: SessionState,
    ) {
        assertEquals(
            Outcome.Success(Unit),
            runBlocking {
                process.app.koin
                    .get<ActiveSessionStore>()
                    .commit(state)
            },
        )
    }

    /** The process dies: no cleanup runs (the service's coroutines simply stop), and a new process boots. */
    private fun kill(process: Process): WakeApp {
        process.service.destroy()
        shadowOf(process.app.app).clearStartedServices()
        val next = WakeApp()
        assertEquals(SessionState.Idle, next.engine.state.value, "a new process starts with nothing in memory")
        return next
    }

    /**
     * The system fires the armed slot: its broadcast reaches [SessionSlotReceiver], whose handler starts the wake service
     * with the slot action; the service is started as the system would. Returns the service.
     */
    private fun deliverSlot(
        app: WakeApp,
        running: ServiceController<WakeService>? = null,
    ): ServiceController<WakeService> {
        val slot = assertNotNull(slots(app).singleOrNull(), "exactly one slot is armed")
        app.app.sendBroadcast(Intent(shadowOf(slot.operation).savedIntent))
        shadowOf(Looper.getMainLooper()).idle()
        app.koin.get<ApplicationScope>().awaitChildren()
        val start = assertNotNull(shadowOf(app.app).nextStartedService, "the slot receiver starts the wake service")
        assertEquals(WakeService.ACTION_SLOT, start.action)
        return running?.withIntent(start)?.startCommand(0, 2) ?: app.startService(start)
    }

    /**
     * Ends the session with "I'm up" and the placeholder answer; history then holds one row for it (rows are keyed by
     * session id), started by the first process and finished by the new one with [outcome].
     */
    private fun finishAndCheckHistory(
        app: WakeApp,
        sessionId: String,
        outcome: SessionOutcome = SessionOutcome.OnTime,
    ) {
        if (app.engine.state.value is SessionState.Ringing) app.dispatch(SessionEvent.ImUpTapped)
        app.solveCheck()
        try {
            app.awaitUntil("the session ends") { app.engine.state.value == SessionState.Idle }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; state ${app.engine.state.value::class.simpleName}; logs ${app.logs()}", e)
        }
        val row =
            assertIs<Outcome.Success<SessionHistoryRow?>>(
                runBlocking { app.koin.get<SessionHistoryRepository>().find(sessionId) },
            ).value
        assertEquals(outcome, assertNotNull(row, "one history row").outcome)
        assertEquals(scheduledAt, row.scheduledAt)
        assertEquals(emptyList(), slots(app), "no slot after the session")
    }

    private fun assertRingsAgain(
        app: WakeApp,
        before: SessionData,
    ): SessionData {
        val after = (app.engine.state.value as SessionState.Ring).session
        assertEquals(before.sessionId, after.sessionId, "the same session")
        assertEquals(before.ringIndex, after.ringIndex, "the same ring")
        assertEquals(before.snoozesGranted, after.snoozesGranted, "the same snooze count")
        assertEquals(before.checkRun, after.checkRun, "the same check progress")
        assertNotEquals(before.interactionDeadline, after.interactionDeadline, "a fresh 30-minute deadline")
        assertNull(after.paying)
        assertEquals(1, slots(app).size, "the heartbeat slot is armed again, one only")
        assertEquals(scheduledAt, app.koin.get<WakeNotifier>().shownFor, "the ringing notification is back")
        assertTrue(app.runtime.isServiceRunning)
        return after
    }

    @Test
    fun `a kill in Ringing rings again on the same ring at the set volume with no ramp`() {
        val first = ringFirst()
        val before = (stored(first.app) as SessionState.Ringing).session
        assertTrue(first.app.player.gain < 1f, "the fresh ring ramps")

        val app = kill(first)
        deliverSlot(app)
        app.awaitRinging()

        assertRingsAgain(app, before)
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
        assertEquals(1f, app.player.gain, 1e-6f, "a restored ring plays at the set volume, no ramp")
        assertTrue(app.vibrator.isVibrating)
        finishAndCheckHistory(app, before.sessionId)
    }

    @Test
    fun `a kill in Ringing with a payment in flight rings again with paying cleared and no billing`() {
        val first = ringFirst()
        val ringing = stored(first.app) as SessionState.Ringing
        commit(first, SessionState.Ringing(ringing.session.copy(paying = PurchaseIntentId("intent-1"))))

        val app = kill(first)
        deliverSlot(app)
        app.awaitRinging()

        assertRingsAgain(app, ringing.session)
        assertTrue(app.logs().none { "LaunchBilling" in it }, "billing is never relaunched")
        finishAndCheckHistory(app, ringing.session.sessionId)
    }

    @Test
    fun `a kill in Grace comes back in Grace, muted, and goes Loud when the grace window ends`() {
        val first = ringFirst()
        first.app.dispatch(SessionEvent.ImUpTapped)
        val grace = assertIs<SessionState.Grace>(stored(first.app))

        val app = kill(first)
        deliverSlot(app)
        app.awaitUntil("Grace is restored") { app.engine.state.value is SessionState.Grace && app.runtime.isServiceRunning }

        assertRingsAgain(app, grace.session)
        assertTrue(app.player.isMuted || app.player.sound == null, "muted during the grace window")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21))
        app.awaitUntil("the grace window ends") { app.engine.state.value is SessionState.Loud && app.player.sound != null }
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
        finishAndCheckHistory(app, grace.session.sessionId)
    }

    @Test
    fun `a kill in Grace whose window ended while the process was dead goes Loud on the same restore`() {
        val first = ringFirst()
        first.app.dispatch(SessionEvent.ImUpTapped)
        val grace = assertIs<SessionState.Grace>(stored(first.app))

        val app = kill(first)
        // The process stays dead past the grace end: the main looper's clock is the system clock here.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(25))
        deliverSlot(app)
        app.awaitUntil("Loud") { app.engine.state.value is SessionState.Loud && app.player.sound != null }

        assertEquals(grace.session.sessionId, (app.engine.state.value as SessionState.Loud).session.sessionId)
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
        finishAndCheckHistory(app, grace.session.sessionId)
    }

    @Test
    fun `a kill in Loud rings again loud`() {
        val first = ringFirst()
        first.app.dispatch(SessionEvent.ImUpTapped)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21))
        first.app.awaitUntil("Loud") { first.app.engine.state.value is SessionState.Loud }
        val loud = assertIs<SessionState.Loud>(stored(first.app))

        val app = kill(first)
        deliverSlot(app)
        app.awaitUntil("Loud rings") { app.engine.state.value is SessionState.Loud && app.player.sound != null }

        assertRingsAgain(app, loud.session)
        assertFalse(app.player.isMuted)
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
        finishAndCheckHistory(app, loud.session.sessionId)
    }

    @Test
    fun `a kill in Snoozed stays silent until the snooze end, then rings as the next ring with the same snooze count`() {
        val first = ringFirst()
        val ringing = (stored(first.app) as SessionState.Ringing).session
        // The dead process had granted a 9-minute snooze: Snoozed committed, its slot armed at the snooze end.
        val snoozeEnd = Deadline.after(first.app.now(), 9.minutes)
        commit(first, SessionState.Snoozed(ringing.copy(snoozesGranted = 1, interactionDeadline = null, snoozeEnd = snoozeEnd)))
        assertEquals(
            Outcome.Success(Unit),
            first.app.koin
                .get<AlarmScheduler>()
                .armSessionSlot(snoozeEnd),
        )

        val app = kill(first)
        // An early delivery (the slot is the only backstop): the snooze goes on, silent, its slot at the snooze end.
        val service = deliverSlot(app)
        app.awaitUntil("the snooze is restored") { app.engine.state.value is SessionState.Snoozed }
        assertNull(app.player.sound, "silent while snoozed")
        assertEquals(1, slots(app).size)
        // Wall time + the monotonic time left: the two clocks can drift a few milliseconds apart in a test run.
        val drift = slots(app).single().triggerAtTime - snoozeEnd.wallMillis
        assertTrue(drift in -1_000L..1_000L, "the slot waits for the snooze end (off by $drift ms)")

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(9))
        deliverSlot(app, service)
        app.awaitRinging()

        val next = assertIs<SessionState.Ringing>(app.engine.state.value).session
        assertEquals(ringing.sessionId, next.sessionId)
        assertEquals(2, next.ringIndex, "Ringing(ringIndex + 1)")
        assertEquals(1, next.snoozesGranted)
        assertEquals(1, slots(app).size, "the heartbeat slot")
        finishAndCheckHistory(app, ringing.sessionId, SessionOutcome.Snoozed)
    }

    @Test
    fun `an alarm that fires into a killed process with a stored session merges into it, never a second session`() {
        val first = ringFirst()
        val before = (stored(first.app) as SessionState.Ringing).session

        val app = kill(first)
        assertEquals(
            Outcome.Success(Unit),
            runBlocking { app.koin.get<AlarmRepository>().upsert(anAlarm(id = "alarm-b", requestCode = 1001)) },
        )
        app.ring(AlarmFired("alarm-b", scheduledAt + 1.minutes))
        app.awaitMerges(before.sessionId)

        assertEquals(before.sessionId, assertIs<SessionState.Ringing>(app.engine.state.value).session.sessionId)
        assertEquals(1, app.mediaPlayers.size, "one player")
    }

    @Test
    fun `the notification posted by the restored session is the ringing one`() {
        val first = ringFirst()
        val app = kill(first)

        deliverSlot(app)
        app.awaitRinging()

        val notifications = shadowOf(app.app.getSystemService(NotificationManager::class.java)).allNotifications
        assertTrue(notifications.any { it.fullScreenIntent != null }, "the full-screen intent is posted again")
    }
}
