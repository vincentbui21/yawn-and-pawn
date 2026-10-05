package com.yawnandpawn.app.android.wake

import android.app.AlarmManager
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.aSession
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Story 2.2 through the real receivers, scheduler and service: a clock or zone change re-arms the session slot from its
 * stored deadline (new wall time + the monotonic time left), and a reboot or an app update arms it from wall time so
 * the session comes back. The time ports are fakes; the boot count is `Settings.Global.BOOT_COUNT`.
 */
@RunWith(RobolectricTestRunner::class)
class SessionClockAndRebootTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val clock = FakeClock(Instant.parse("2027-03-08T06:00:00Z"))
    private val monotonic = FakeMonotonicClock(elapsedMillis = 5_000_000)
    private val app = WakeApp(clock = clock, monotonic = monotonic)

    private fun wall(): Long = clock.now().toEpochMilliseconds()

    private fun slot(): ShadowAlarmManager.ScheduledAlarm =
        assertNotNull(
            shadowOf(app.app.getSystemService(AlarmManager::class.java)).scheduledAlarms.singleOrNull {
                shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT
            },
            "exactly one session slot",
        )

    private fun stored(state: SessionState) =
        assertEquals(
            Outcome.Success(Unit),
            runBlocking {
                app.koin.get<ActiveSessionStore>().commit(state)
            },
        )

    private fun storedNow(): SessionState =
        assertIs<StoredSession.Found>(
            assertIs<Outcome.Success<StoredSession>>(
                runBlocking {
                    app.koin.get<ActiveSessionStore>().load()
                },
            ).value,
        ).state

    private fun broadcast(action: String) {
        app.app.sendBroadcast(Intent(action).setPackage(app.app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        app.koin.get<ApplicationScope>().awaitChildren()
    }

    /** The phone was off for [off] and booted again: a new boot count, the elapsed clock restarted. */
    private fun reboot(off: Duration) {
        clock.advanceBy(off)
        monotonic.reboot()
        monotonic.advanceBy(30.seconds)
        Settings.Global.putInt(app.app.contentResolver, Settings.Global.BOOT_COUNT, 2)
    }

    /** The system fires the armed slot; its receiver starts the wake service, which is started as the system would. */
    private fun deliverSlot() {
        app.app.sendBroadcast(Intent(shadowOf(slot().operation).savedIntent))
        shadowOf(Looper.getMainLooper()).idle()
        app.koin.get<ApplicationScope>().awaitChildren()
        app.startService(assertNotNull(shadowOf(app.app).nextStartedService, "the slot starts the wake service"))
    }

    /** A session made at "now" on boot 1: interaction deadline 30 min ahead. */
    private fun session(): SessionData = aSession().copy(interactionDeadline = Deadline.after(app.now(), 30.minutes))

    private fun snoozed(minutes: Int): SessionState.Snoozed =
        SessionState.Snoozed(
            session().copy(interactionDeadline = null, snoozesGranted = 1, snoozeEnd = Deadline.after(app.now(), minutes.minutes)),
        )

    @Test
    fun `a clock set 1 h back or forward at minute 3 of a 9-minute snooze re-arms the slot 6 minutes from the new wall time`() {
        listOf(1.hours, (-1).hours).forEach { jump ->
            stored(snoozed(minutes = 9))
            clock.advanceBy(3.minutes)
            monotonic.advanceBy(3.minutes)

            clock.advanceBy(jump)
            broadcast(Intent.ACTION_TIME_CHANGED)

            assertEquals(wall() + 6.minutes.inWholeMilliseconds, slot().triggerAtTime, "jump $jump")
            assertEquals(SessionState.Idle, app.engine.state.value, "the engine is not touched")
            assertIs<SessionState.Snoozed>(storedNow(), "the session is not ended or re-resolved")
        }
    }

    @Test
    fun `a zone change during a ring arms the slot at once and leaves the stored session as it was`() {
        val ringing = SessionState.Ringing(session())
        stored(ringing)

        broadcast(Intent.ACTION_TIMEZONE_CHANGED)

        assertEquals(wall() + 1.seconds.inWholeMilliseconds, slot().triggerAtTime)
        assertEquals(ringing, storedNow(), "the frozen config is not re-resolved")
    }

    @Test
    fun `after a reboot a snooze still ahead is armed at its wall time`() {
        val snooze = snoozed(minutes = 9)
        stored(snooze)
        reboot(off = 2.minutes)

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(snooze.session.snoozeEnd?.wallMillis, slot().triggerAtTime)
    }

    @Test
    fun `after a reboot through the snooze end the slot fires at once and the restore rings the next ring`() {
        stored(snoozed(minutes = 9))
        reboot(off = 3.hours)

        broadcast(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(wall() + 1.seconds.inWholeMilliseconds, slot().triggerAtTime)
        deliverSlot()
        app.awaitRinging()

        val next = assertIs<SessionState.Ringing>(app.engine.state.value).session
        assertEquals(2, next.ringIndex)
        assertEquals(1, next.snoozesGranted)
    }

    @Test
    fun `a ringing session after 10 hours off comes back before the first unlock, with a fresh 30-minute deadline`() {
        val ringing = SessionState.Ringing(session())
        stored(ringing)
        reboot(off = 10.hours)

        broadcast(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        assertEquals(wall() + 1.seconds.inWholeMilliseconds, slot().triggerAtTime)
        deliverSlot()
        app.awaitRinging()

        val restored = assertIs<SessionState.Ringing>(app.engine.state.value).session
        assertEquals(ringing.session.sessionId, restored.sessionId)
        assertEquals(Deadline.after(app.now(), 30.minutes), restored.interactionDeadline)
        assertEquals(true, app.lastMediaPlayer().isReallyPlaying, "it rings")
    }

    @Test
    fun `an app update during a ring arms the slot at once and the session comes back on the same step with the same counts`() {
        val ringing = SessionState.Ringing(session().copy(ringIndex = 2, snoozesGranted = 1))
        stored(ringing)

        broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)
        assertEquals(wall() + 1.seconds.inWholeMilliseconds, slot().triggerAtTime)
        deliverSlot()
        app.awaitRinging()

        val restored = assertIs<SessionState.Ringing>(app.engine.state.value).session
        assertEquals(ringing.session.sessionId, restored.sessionId)
        assertEquals(2, restored.ringIndex)
        assertEquals(1, restored.snoozesGranted)
        assertEquals(ringing.session.checkRun, restored.checkRun)
    }
}
