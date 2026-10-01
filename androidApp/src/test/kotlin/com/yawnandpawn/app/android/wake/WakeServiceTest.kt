package com.yawnandpawn.app.android.wake

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ComponentName
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import android.os.UserManager
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.AlarmFiredReceiver
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.CheckAnswer
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Story 1.14: an alarm fire rings through the receiver, the foreground service, the engine and the wake runtime. */
@RunWith(RobolectricTestRunner::class)
class WakeServiceTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")
    private val alarmA = anAlarm(id = "alarm-a", requestCode = 1000)
    private val fired = AlarmFired(alarmA.id, scheduledAt)

    private fun repository(): AlarmRepository = GlobalContext.get().get()

    private fun upsert(alarm: Alarm) = assertEquals(Outcome.Success(Unit), runBlocking { repository().upsert(alarm) })

    private fun store(app: WakeApp): ActiveSessionStore = app.koin.get()

    private fun notifications(app: WakeApp) = shadowOf(app.app.getSystemService(NotificationManager::class.java))

    private fun alarmStream(app: WakeApp) = app.app.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)

    private fun armedSlot(app: WakeApp): Boolean =
        shadowOf(app.app.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
            shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT
        }

    /** Stores alarm A and rings it through the receiver, like a fire of its system alarm. */
    private fun fireThroughReceiver(app: WakeApp) {
        upsert(alarmA)
        app.app.sendBroadcast(
            AlarmFiredReceiver
                .intent(app.app, AlarmFiredReceiver.ACTION_ALARM)
                .putExtra(AlarmFiredReceiver.EXTRA_ALARM_ID, fired.alarmId)
                .putExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, fired.scheduledAt.toEpochMilliseconds()),
        )
        shadowOf(Looper.getMainLooper()).idle()
        app.koin.get<ApplicationScope>().awaitChildren()
    }

    @Test
    fun `an enabled alarm rings in the foreground service on USAGE_ALARM at the set volume with the full-screen notification`() {
        val app = WakeApp()
        fireThroughReceiver(app)
        val intent = assertNotNull(shadowOf(app.app).nextStartedService, "the receiver started the wake service")

        val service = app.startService(intent).get()
        app.awaitRinging()

        val ringing = app.engine.state.value as SessionState.Ringing
        assertEquals(alarmA.id, ringing.session.config.alarmId)
        assertEquals(scheduledAt, ringing.session.config.scheduledAt)
        assertEquals(WakeNotifier.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        val notification = shadowOf(service).lastForegroundNotification
        assertEquals(
            ComponentName(app.app, WakeActivity::class.java),
            shadowOf(notification.fullScreenIntent).savedIntent.component,
        )
        val media = app.lastMediaPlayer()
        assertEquals(AudioAttributes.USAGE_ALARM, media.audioAttributes.usage)
        assertTrue(media.isReallyPlaying)
        assertEquals(
            Math.round(app.app.getSystemService(AudioManager::class.java).getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.8).toInt(),
            alarmStream(app),
        )
        assertTrue(app.vibrator.isVibrating)
        assertTrue(armedSlot(app), "the heartbeat slot is armed")
        assertTrue(app.runtime.isServiceRunning)
    }

    @Test
    fun `a fire while a session rings is merged into it with no second player`() {
        val app = WakeApp()
        upsert(alarmA)
        val controller = app.ring(fired)
        app.awaitRinging()
        val sessionId = (app.engine.state.value as SessionState.Ringing).session.sessionId

        controller.withIntent(WakeService.alarmIntent(app.app, AlarmFired("alarm-b", scheduledAt + 1.minutes))).startCommand(0, 2)
        app.awaitUntil("the merge is handled") { app.logs().any { it.startsWith("SessionEffectLogged type=RecordMergedOccurrence") } }

        assertEquals(sessionId, assertIs<SessionState.Ringing>(app.engine.state.value).session.sessionId)
        assertEquals(1, app.mediaPlayers.size, "one player")
    }

    @Test
    fun `when the session completes nothing is left - no service, sound, vibration, notification or slot, and the user volume is back`() {
        val app = WakeApp()
        app.app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        upsert(alarmA)
        val service = app.ring(fired).get()
        app.awaitRinging()

        app.dispatch(SessionEvent.ImUpTapped, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
        app.awaitUntil("the service stops") { shadowOf(service).isStoppedBySelf }

        assertEquals(SessionState.Idle, app.engine.state.value)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).notificationShouldRemoved)
        assertEquals(0, notifications(app).size())
        assertNull(app.player.sound)
        assertFalse(app.vibrator.isVibrating)
        assertEquals(2, alarmStream(app), "the user volume is restored")
        assertFalse(armedSlot(app), "the slot is cancelled")
    }

    @Test
    fun `a fire for an alarm deleted before it rang stops the service and rings nothing`() {
        val app = WakeApp()

        val service = app.ring(fired).get()
        app.awaitUntil("the service stops") { shadowOf(service).isStoppedBySelf }

        assertEquals(SessionState.Idle, app.engine.state.value)
        assertTrue(app.mediaPlayers.isEmpty())
        assertTrue(app.logs().any { it.startsWith("FireIgnored kind=Alarm alarmId=alarm-a reason=alarm deleted before it rang") })
    }

    @Test
    fun `a broken session store rings the emergency default until I am up`() {
        val broken = FakeActiveSessionStore().apply { commitFailure = DomainError.StorageFailure("disk full") }
        val app = WakeApp(store = broken)
        upsert(alarmA.copy(volumePercent = 60))

        val service = app.ring(fired).get()
        app.awaitUntil("the emergency ring") { app.runtime.emergency.value != null }

        assertEquals(EmergencyRing(scheduledAt, 60), app.runtime.emergency.value)
        assertEquals(AlarmSound.Default, app.player.sound)
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
        assertTrue(app.vibrator.isVibrating)
        assertEquals(WakeNotifier.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        val started = "EmergencyRingStarted cause=session not started: storage failure: disk full"
        assertTrue(app.logs().any { it.startsWith(started) }, "${app.logs()}")
        assertFalse(shadowOf(service).isStoppedBySelf, "it rings on while Idle")

        app.runtime.stopEmergency()
        app.awaitUntil("the service stops") { shadowOf(service).isStoppedBySelf }
        assertNull(app.player.sound)
    }

    @Test
    fun `the emergency ring stops by itself after 30 minutes`() {
        val broken = FakeActiveSessionStore().apply { commitFailure = DomainError.StorageFailure("disk full") }
        val app = WakeApp(store = broken)
        upsert(alarmA)
        val service = app.ring(fired).get()
        app.awaitUntil("the emergency ring") { app.runtime.emergency.value != null }

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(30))
        app.awaitUntil("the service stops") { shadowOf(service).isStoppedBySelf }

        assertNull(app.runtime.emergency.value)
        assertTrue(app.logs().any { it == "EmergencyRingStopped reason=30-minute limit" })
    }

    @Test
    fun `a crash in the wake flow is reported, the default sound rings and ProcessRestored is dispatched`() {
        val app = WakeApp(repository = ExplodingAlarmRepository)

        app.ring(fired)
        app.awaitUntil("the crash is handled") { app.logs().any { it.startsWith("SessionEventIgnored type=ProcessRestored") } }

        assertEquals(
            "database exploded",
            app.crashReporter.reported
                .single()
                .message,
        )
        assertEquals(AlarmSound.Default, app.player.sound)
        assertTrue(app.lastMediaPlayer().isReallyPlaying, "never silent")
        assertEquals(
            scheduledAt,
            app.runtime.emergency.value
                ?.alarmAt,
        )
    }

    @Test
    fun `a slot fire with no session leaves the engine Idle and stops the service`() {
        val app = WakeApp()

        val service = app.startService(WakeService.intent(app.app, WakeService.ACTION_SLOT)).get()
        app.awaitUntil("the service stops") { shadowOf(service).isStoppedBySelf }

        assertEquals(SessionState.Idle, app.engine.state.value)
    }

    @Test
    fun `a restore whose service start is refused is logged and the armed slot brings the ring back`() {
        val app =
            WakeApp(
                starter = { context ->
                    WakeServiceStarter(context, GlobalContext.get().get()) { throw ForegroundServiceStartNotAllowedException("background") }
                },
            )
        val written = runBlocking { store(app).commit(SessionState.Ringing(aSession())) }
        assertEquals(Outcome.Success(Unit), written)

        runBlocking { app.engine.restore() }

        assertIs<SessionState.Ringing>(app.engine.state.value)
        assertTrue(app.logs().any { it.startsWith("OperationFailed operation=start wake service") }, "${app.logs()}")
        assertTrue(armedSlot(app), "the slot (at most 60 s) fires the receiver, which starts the service again")
        assertFalse(app.runtime.isServiceRunning)
    }

    /** A snoozed session whose snooze end is due now, so its next step (a slot fire, a restore) re-rings. */
    private fun dueSnooze(app: WakeApp): SessionState.Snoozed =
        SessionState.Snoozed(
            aSession().copy(interactionDeadline = null, snoozeEnd = Deadline.after(app.now(), Duration.ZERO), snoozesGranted = 1),
        )

    @Test
    fun `a slot fire that cannot be saved during a snooze rings the emergency default`() {
        val broken = FakeActiveSessionStore()
        val app = WakeApp(store = broken)
        broken.row = SessionJson.encode(dueSnooze(app))
        broken.commitFailure = DomainError.StorageFailure("disk full")

        app.startService(WakeService.intent(app.app, WakeService.ACTION_SLOT))
        app.awaitUntil("the emergency ring") { app.runtime.emergency.value != null }

        assertEquals(AlarmSound.Default, app.player.sound)
        assertTrue(app.logs().any { it.startsWith("EmergencyRingStarted cause=slot fire not saved") }, "${app.logs()}")
    }

    @Test
    fun `a merge that cannot be saved during a snooze rings the emergency default`() {
        val broken = FakeActiveSessionStore()
        val app = WakeApp(store = broken)
        broken.row = SessionJson.encode(dueSnooze(app))
        broken.commitFailure = DomainError.StorageFailure("disk full")

        app.ring(fired)
        app.awaitUntil("the emergency ring") { app.runtime.emergency.value != null }

        assertEquals(
            scheduledAt,
            app.runtime.emergency.value
                ?.alarmAt,
        )
        assertTrue(app.logs().any { it.startsWith("EmergencyRingStarted cause=merge not saved") }, "${app.logs()}")
    }

    @Test
    fun `a restore start whose session cannot be loaded rings the emergency default`() {
        val broken = FakeActiveSessionStore().apply { loadFailure = DomainError.StorageFailure("disk I/O error") }
        val app = WakeApp(store = broken)

        app.startService(WakeService.intent(app.app, WakeService.ACTION_RESTORE))
        app.awaitUntil("the emergency ring") { app.runtime.emergency.value != null }

        assertTrue(app.logs().any { it.startsWith("EmergencyRingStarted cause=session not loaded") }, "${app.logs()}")
    }

    @Test
    fun `a flow that keeps crashing is reported three times, then logged once and the default rings on`() {
        val app = WakeApp(repository = ExplodingAlarmRepository)
        val controller = app.ring(fired)
        app.awaitUntil("the first crash") { app.crashReporter.reported.isNotEmpty() }

        (2..5).forEach { startId ->
            controller.withIntent(WakeService.alarmIntent(app.app, fired)).startCommand(0, startId)
            shadowOf(Looper.getMainLooper()).idle()
        }
        app.awaitUntil("the crash limit") { app.logs().any { "crashed 4 times" in it } }

        assertEquals(3, app.crashReporter.reported.size)
        assertEquals(1, app.logs().count { "not restarted" in it })
        assertEquals(AlarmSound.Default, app.player.sound)
    }

    @Test
    fun `the tick loop ends the grace window once it is over`() {
        val app = WakeApp()
        upsert(alarmA)
        app.ring(fired)
        app.awaitRinging()
        app.dispatch(SessionEvent.ImUpTapped)
        assertIs<SessionState.Grace>(app.engine.state.value)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21))
        app.awaitUntil("the grace window ends") { app.engine.state.value is SessionState.Loud }

        assertFalse(app.player.isMuted)
    }

    @Test
    fun `an alarm that cannot be read starts the service from the receiver and rings the emergency default`() {
        val unreadable = FakeAlarmRepository(listOf(alarmA)).apply { failure = DomainError.StorageFailure("disk I/O error") }
        val app = WakeApp(repository = unreadable)
        app.app.sendBroadcast(
            AlarmFiredReceiver
                .intent(app.app, AlarmFiredReceiver.ACTION_ALARM)
                .putExtra(AlarmFiredReceiver.EXTRA_ALARM_ID, fired.alarmId)
                .putExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, fired.scheduledAt.toEpochMilliseconds()),
        )
        app.awaitUntil("the receiver starts the service") { shadowOf(app.app).peekNextStartedService() != null }

        app.startService(shadowOf(app.app).nextStartedService)
        app.awaitUntil("the emergency ring") { app.runtime.emergency.value != null }

        assertEquals(EmergencyRing(scheduledAt, Alarm.DEFAULT_VOLUME_PERCENT), app.runtime.emergency.value)
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
    }

    @Test
    fun `a fire during a snooze ends the snooze and rings`() {
        val app = WakeApp()
        val snoozed =
            SessionState.Snoozed(
                aSession().copy(interactionDeadline = null, snoozeEnd = Deadline.after(app.now(), 9.minutes), snoozesGranted = 1),
            )
        assertEquals(Outcome.Success(Unit), runBlocking { store(app).commit(snoozed) })

        app.ring(fired)
        app.awaitRinging()

        val ringing = assertIs<SessionState.Ringing>(app.engine.state.value)
        assertEquals(snoozed.session.sessionId, ringing.session.sessionId)
        assertEquals(2, ringing.session.ringIndex)
    }

    @Test
    fun `a cold service start for a second alarm merges it into the stored ringing session`() {
        val app = WakeApp()
        val stored = SessionState.Ringing(aSession())
        assertEquals(Outcome.Success(Unit), runBlocking { store(app).commit(stored) })

        app.ring(fired)
        app.awaitUntil("the merge is handled") { app.logs().any { it.startsWith("SessionEffectLogged type=RecordMergedOccurrence") } }

        assertEquals(stored.session.sessionId, assertIs<SessionState.Ringing>(app.engine.state.value).session.sessionId)
        assertEquals(1, app.mediaPlayers.size, "one player")
    }

    @Test
    fun `a session started while the phone is unlocked is not before the first unlock, one started locked is`() {
        val app = WakeApp()
        upsert(alarmA)
        app.ring(fired)
        app.awaitRinging()
        assertFalse((app.engine.state.value as SessionState.Ringing).session.beforeFirstUnlock)
        app.dispatch(SessionEvent.ImUpTapped, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
        app.awaitUntil("the session ends") { app.engine.state.value == SessionState.Idle }

        shadowOf(app.app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        upsert(alarmA.copy(repeatDays = setOf(DayOfWeek.MONDAY)))
        app.ring(fired)
        app.awaitRinging()

        assertTrue((app.engine.state.value as SessionState.Ringing).session.beforeFirstUnlock)
    }

    @Test
    fun `a slot start with no ringing notification up posts the quiet one without a full-screen intent`() {
        val app = WakeApp()

        val service = app.startService(WakeService.intent(app.app, WakeService.ACTION_SLOT)).get()

        val notification = shadowOf(service).lastForegroundNotification
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, notification.channelId)
        assertNull(notification.fullScreenIntent)
        app.awaitUntil("the service stops") { shadowOf(service).isStoppedBySelf }
        assertEquals(1, shadowOf(service).stopSelfResultId, "it stops its own latest start")
    }
}

/** An alarm store whose every call throws: an unexpected exception inside the wake flow. */
private object ExplodingAlarmRepository : AlarmRepository {
    override fun observeAll(): Flow<List<Alarm>> = throw IllegalStateException("database exploded")

    override suspend fun listAll(): Outcome<List<Alarm>, DomainError> = throw IllegalStateException("database exploded")

    override suspend fun get(id: String): Outcome<Alarm, DomainError> = throw IllegalStateException("database exploded")

    override suspend fun upsert(alarm: Alarm): Outcome<Unit, DomainError> = throw IllegalStateException("database exploded")

    override suspend fun delete(id: String): Outcome<Unit, DomainError> = throw IllegalStateException("database exploded")
}
