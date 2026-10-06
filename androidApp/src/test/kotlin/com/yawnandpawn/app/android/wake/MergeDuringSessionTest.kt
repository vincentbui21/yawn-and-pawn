package com.yawnandpawn.app.android.wake

import android.app.AlarmManager
import android.content.Intent
import android.os.Looper
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.AlarmFiredReceiver
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 2.9 (FR-SES-7): an alarm that fires during a session joins it through the real receiver, service, engine and
 * Room `app.db`. One `session_merge` row per merged occurrence, never a second session or a second fee.
 */
@RunWith(RobolectricTestRunner::class)
class MergeDuringSessionTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val billing = FakeBilling()
    private val app = WakeApp(billing = billing)
    private val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")
    private val alarmA = anAlarm(id = "alarm-a", requestCode = 1000)

    /** Alarm B: every setting differs from A's, and it repeats every day. */
    private val alarmB =
        anAlarm(id = "alarm-b", requestCode = 1001, time = LocalTime(6, 1), repeatDays = DayOfWeek.entries.toSet()).copy(
            soundRef = "builtin:birdsong",
            volumePercent = 30,
            snoozeLengthMinutes = 15,
            graceSeconds = 60,
            vibration = false,
        )

    private fun upsert(vararg alarms: Alarm) =
        alarms.forEach {
            assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(it) })
        }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        app.koin.get<ApplicationScope>().awaitChildren()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The occurrence of [alarm] at [at] fires through its receiver; returns the service start it asked for, if any. */
    private fun fireThroughReceiver(
        alarm: Alarm,
        at: Instant,
    ): Intent? {
        app.app.sendBroadcast(
            AlarmFiredReceiver
                .intent(app.app, AlarmFiredReceiver.ACTION_ALARM)
                .putExtra(AlarmFiredReceiver.EXTRA_ALARM_ID, alarm.id)
                .putExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, at.toEpochMilliseconds()),
        )
        idle()
        return shadowOf(app.app).nextStartedService
    }

    private fun ringA(): ServiceController<WakeService> {
        upsert(alarmA, alarmB)
        val service = app.ring(AlarmFired(alarmA.id, scheduledAt))
        app.awaitRinging()
        return service
    }

    private fun slotTrigger(): Long? =
        shadowOf(app.app.getSystemService(AlarmManager::class.java))
            .scheduledAlarms
            .singleOrNull { shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT }
            ?.triggerAtTime

    @Test
    fun `a merge while ringing changes nothing - same session, config, sound, volume and timers - writes one row and re-arms B`() {
        val service = ringA()
        val before = app.engine.state.value
        val player = app.lastMediaPlayer()
        val gain = app.player.gain

        val start = assertNotNull(fireThroughReceiver(alarmB, scheduledAt + 1.minutes), "B starts the service")
        service.withIntent(start).startCommand(0, 2)
        val sessionId = (before as SessionState.Ringing).session.sessionId
        val merge = app.awaitMerges(sessionId).single()

        assertEquals(before, app.engine.state.value, "the merge is not a user interaction: nothing in the session changes")
        assertEquals(alarmA.soundRef, (app.engine.state.value as SessionState.Ringing).session.config.soundRef, "B's settings are ignored")
        assertEquals(AlarmFired(alarmB.id, scheduledAt + 1.minutes), AlarmFired(merge.alarmId, merge.scheduledAt))
        assertEquals(1, app.mediaPlayers.size, "one player")
        assertTrue(player.isReallyPlaying)
        assertEquals(gain, app.player.gain, "same volume")
        val nextB =
            shadowOf(app.app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single {
                shadowOf(it.operation).requestCode ==
                    1001
            }
        assertTrue(nextB.triggerAtTime > (scheduledAt + 1.minutes).toEpochMilliseconds(), "B's next occurrence is scheduled")
    }

    @Test
    fun `a merge during a snooze rings now with no grace and no fee, replaces the snooze slot with the heartbeat and writes one row`() {
        upsert(alarmA, alarmB)
        val snoozed =
            SessionState.Snoozed(
                aSession().copy(interactionDeadline = null, snoozesGranted = 1, snoozeEnd = Deadline.after(app.now(), 9.minutes)),
            )
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<ActiveSessionStore>().commit(snoozed) })
        app.koin.get<AlarmScheduler>().armSessionSlot(snoozed.session.snoozeEnd!!)
        val wall = System.currentTimeMillis()

        app.ring(AlarmFired(alarmB.id, scheduledAt))
        app.awaitRinging()

        val ringing = assertIs<SessionState.Ringing>(app.engine.state.value).session
        assertEquals(2, ringing.ringIndex)
        assertTrue(ringing.noGraceThisRing)
        assertEquals(1, ringing.snoozesGranted, "no fee")
        assertTrue(app.logs().none { "LaunchBilling" in it }, "no payment for the merge")
        val trigger = assertNotNull(slotTrigger())
        assertTrue(
            trigger < snoozed.session.snoozeEnd!!.wallMillis - 5.minutes.inWholeMilliseconds,
            "the heartbeat replaced the snooze-end slot",
        )
        assertTrue(trigger - wall <= SessionReducer.HEARTBEAT.inWholeMilliseconds + 5_000, "about one heartbeat away: ${trigger - wall} ms")
        assertEquals(1, app.awaitMerges(snoozed.session.sessionId).size)
        app.dispatch(SessionEvent.ImUpTapped)
        assertIs<SessionState.Loud>(app.engine.state.value, "no grace window")
    }

    /** [first] and [second] fire for the same minute and reach the service back to back (its commands run one by one). */
    private fun sameMinute(
        first: Alarm,
        second: Alarm,
    ) {
        upsert(alarmA, alarmB)

        val service = app.ring(AlarmFired(first.id, scheduledAt))
        service.withIntent(WakeService.alarmIntent(app.app, AlarmFired(second.id, scheduledAt))).startCommand(0, 2)
        app.awaitRinging()

        val session = assertIs<SessionState.Ringing>(app.engine.state.value).session
        assertEquals(first.id, session.config.alarmId, "the first delivered starts the session")
        assertEquals(second.id, app.awaitMerges(session.sessionId).single().alarmId)
        assertEquals(1, app.mediaPlayers.size, "one session, one player")
    }

    @Test
    fun `two alarms for the same minute make one session and one merge row - A delivered first`() = sameMinute(alarmA, alarmB)

    @Test
    fun `two alarms for the same minute make one session and one merge row - B delivered first`() = sameMinute(alarmB, alarmA)

    @Test
    fun `a disabled alarm that fires during a session starts nothing, writes no merge row and is logged`() {
        ringA()
        val sessionId = (app.engine.state.value as SessionState.Ringing).session.sessionId
        upsert(alarmB.copy(enabled = false))

        val start = fireThroughReceiver(alarmB, scheduledAt + 1.minutes)

        assertEquals(null, start, "no service start for a disabled alarm")
        assertEquals(emptyList(), app.merges(sessionId))
        assertTrue(app.logs().any { it == "FireIgnored kind=Alarm alarmId=alarm-b reason=alarm disabled" }, "${app.logs()}")
    }

    @Test
    fun `an alarm deleted before the service handles it is not merged and is logged`() {
        val service = ringA()
        val sessionId = (app.engine.state.value as SessionState.Ringing).session.sessionId

        service.withIntent(WakeService.alarmIntent(app.app, AlarmFired("deleted", scheduledAt))).startCommand(0, 2)
        app.awaitUntil("the fire is handled") {
            app.logs().any {
                it ==
                    "FireIgnored kind=Alarm alarmId=deleted reason=alarm deleted before it rang"
            }
        }

        assertEquals(emptyList(), app.merges(sessionId))
        assertFalse(app.engine.state.value !is SessionState.Ringing)
    }

    /** The fire of [alarm] at [at] reaches the running [service] as a new start, as the receiver would send it. */
    private fun startFor(
        service: ServiceController<WakeService>,
        alarm: Alarm,
        at: Instant,
    ) = service.withIntent(WakeService.alarmIntent(app.app, AlarmFired(alarm.id, at))).startCommand(0, 2)

    private fun awaitIgnored(
        app: WakeApp,
        alarmId: String,
        reason: String,
    ) = app.awaitUntil("the fire is ignored: $reason") { app.logs().any { it == "FireIgnored kind=Alarm alarmId=$alarmId reason=$reason" } }

    @Test
    fun `a repeating alarm switched off before the service handles it is not merged and is logged`() {
        val service = ringA()
        val sessionId = (app.engine.state.value as SessionState.Ringing).session.sessionId
        upsert(alarmB.copy(enabled = false))

        startFor(service, alarmB, scheduledAt + 1.minutes)
        awaitIgnored(app, alarmB.id, "repeating alarm switched off before it rang")

        assertEquals(emptyList(), app.merges(sessionId))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    @Test
    fun `a one-time alarm that is off by the time the service reads it still merges - RearmOnFire switched it off`() {
        val service = ringA()
        val sessionId = (app.engine.state.value as SessionState.Ringing).session.sessionId
        val oneTime = anAlarm(id = "alarm-c", requestCode = 1002, time = LocalTime(6, 1)).copy(enabled = false)
        upsert(oneTime)

        startFor(service, oneTime, scheduledAt + 1.minutes)

        assertEquals(oneTime.id, app.awaitMerges(sessionId).single().alarmId)
    }

    @Test
    fun `the session's own occurrence fired again is not merged into itself and is logged`() {
        val service = ringA()
        val before = app.engine.state.value
        val sessionId = (before as SessionState.Ringing).session.sessionId

        startFor(service, alarmA, scheduledAt)
        awaitIgnored(app, alarmA.id, "the session's own occurrence")

        assertEquals(emptyList(), app.merges(sessionId))
        assertEquals(before, app.engine.state.value)
    }

    @Test
    fun `an alarm read that does not answer in time counts as a failure and still merges`() {
        val hanging = HangingAlarmRepository()
        val app = WakeApp(repository = hanging)
        val stored = SessionState.Ringing(aSession())
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<ActiveSessionStore>().commit(stored) })

        app.ring(AlarmFired(alarmB.id, scheduledAt))
        app.awaitUntil("the alarm is being read") { hanging.reads > 0 }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(WakeService.ALARM_READ_TIMEOUT.inWholeMilliseconds + 1))

        assertEquals(alarmB.id, app.awaitMerges(stored.session.sessionId).single().alarmId)
    }

    private fun snoozedIn(app: WakeApp): SessionState.Snoozed {
        val snoozed =
            SessionState.Snoozed(
                aSession().copy(interactionDeadline = null, snoozesGranted = 1, snoozeEnd = Deadline.after(app.now(), 9.minutes)),
            )
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<ActiveSessionStore>().commit(snoozed) })
        return snoozed
    }

    @Test
    fun `an alarm that cannot be read during a snooze still merges and rings`() {
        val unreadable = FakeAlarmRepository(listOf(alarmB)).apply { failure = DomainError.StorageFailure("disk I/O error") }
        val app = WakeApp(repository = unreadable)
        val snoozed = snoozedIn(app)

        app.ring(AlarmFired(alarmB.id, scheduledAt))
        app.awaitRinging()

        val ringing = assertIs<SessionState.Ringing>(app.engine.state.value).session
        assertEquals(snoozed.session.sessionId, ringing.sessionId)
        assertEquals(snoozed.session.ringIndex + 1, ringing.ringIndex)
        assertEquals(alarmB.id, app.awaitMerges(snoozed.session.sessionId).single().alarmId)
    }

    @Test
    fun `an alarm deleted before the service handles it during a snooze is not merged, the snooze goes on and it is logged`() {
        val snoozed = snoozedIn(app)

        app.ring(AlarmFired("deleted", scheduledAt))
        awaitIgnored(app, "deleted", "alarm deleted before it rang")

        assertEquals(emptyList(), app.merges(snoozed.session.sessionId))
        assertEquals(snoozed.session.sessionId, assertIs<SessionState.Snoozed>(app.engine.state.value).session.sessionId)
    }
}

/** An alarm store whose reads never answer (a stuck database); counts them in [reads]. */
private class HangingAlarmRepository(
    delegate: AlarmRepository = FakeAlarmRepository(),
) : AlarmRepository by delegate {
    @Volatile
    var reads = 0

    override suspend fun get(id: String): Outcome<Alarm, DomainError> {
        reads++
        awaitCancellation()
    }
}
