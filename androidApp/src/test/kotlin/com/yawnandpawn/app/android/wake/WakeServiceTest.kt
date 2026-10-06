package com.yawnandpawn.app.android.wake

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
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
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.atomic.AtomicBoolean
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
    private val alarmB = anAlarm(id = "alarm-b", requestCode = 1001)
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
        upsert(alarmB)
        val controller = app.ring(fired)
        app.awaitRinging()
        val sessionId = (app.engine.state.value as SessionState.Ringing).session.sessionId

        controller.withIntent(WakeService.alarmIntent(app.app, AlarmFired("alarm-b", scheduledAt + 1.minutes))).startCommand(0, 2)
        val merge = app.awaitMerges(sessionId).single()
        assertEquals(AlarmFired("alarm-b", scheduledAt + 1.minutes), AlarmFired(merge.alarmId, merge.scheduledAt))

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
    fun `an orphan slot fire is logged, cancels the slot, removes its notification and leaves no service running`() {
        val app = WakeApp()
        // A slot is still armed although runtime.db holds nothing (for example from a process that died after the end).
        app.koin
            .get<AlarmScheduler>()
            .armSessionSlot(Deadline.after(app.now(), 1.minutes))

        val service = app.startService(WakeService.intent(app.app, WakeService.ACTION_SLOT)).get()
        app.awaitUntil("the service stops") { shadowOf(service).isStoppedBySelf }

        assertEquals(SessionState.Idle, app.engine.state.value)
        assertTrue(app.logs().any { it == "FireIgnored kind=SessionSlot alarmId=null reason=no session stored" }, "${app.logs()}")
        assertFalse(armedSlot(app), "the slot is cancelled")
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).notificationShouldRemoved, "its notification is removed")
        assertEquals(0, notifications(app).size(), "no notification left")
    }

    @Test
    fun `a slot carrying an alarm with no session stored rings that alarm as a new session`() {
        val app = WakeApp()
        upsert(alarmA)

        app.startService(WakeService.slotIntent(app.app, fired))
        app.awaitRinging()

        val ringing = assertIs<SessionState.Ringing>(app.engine.state.value)
        assertEquals(alarmA.id, ringing.session.config.alarmId)
        assertEquals(scheduledAt, ringing.session.config.scheduledAt)
        assertTrue(armedSlot(app))
    }

    @Test
    fun `a slot carrying an alarm into a stored ringing session merges it - one session, one player`() {
        val app = WakeApp()
        val stored = SessionState.Ringing(aSession())
        assertEquals(Outcome.Success(Unit), runBlocking { store(app).commit(stored) })

        upsert(alarmB)
        app.startService(WakeService.slotIntent(app.app, AlarmFired("alarm-b", scheduledAt)))
        app.awaitMerges(stored.session.sessionId)

        assertEquals(stored.session.sessionId, assertIs<SessionState.Ringing>(app.engine.state.value).session.sessionId)
        assertEquals(1, app.mediaPlayers.size, "one player")
    }

    @Test
    fun `a swipe from Recents keeps the session, the player and the notification`() {
        val app = WakeApp()
        upsert(alarmA)
        val controller = app.ring(fired)
        app.awaitRinging()

        controller.get().onTaskRemoved(Intent(app.app, WakeActivity::class.java))
        shadowOf(Looper.getMainLooper()).idle()

        assertIs<SessionState.Ringing>(app.engine.state.value)
        assertTrue(app.lastMediaPlayer().isReallyPlaying, "still playing")
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(app.runtime.isServiceRunning)
        assertEquals(1, notifications(app).size())
    }

    @Test
    fun `the emergency ring arms a backup slot carrying its alarm, and after a kill that slot rings it as a real session`() {
        val broken = FakeActiveSessionStore().apply { commitFailure = DomainError.StorageFailure("disk full") }
        val first = WakeApp(store = broken)
        upsert(alarmA)
        val service = first.ring(fired)
        first.awaitUntil("the emergency ring") { first.runtime.emergency.value != null }
        val slot =
            shadowOf(first.app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single {
                shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT
            }
        val carried = shadowOf(slot.operation).savedIntent
        assertEquals(alarmA.id, carried.getStringExtra(AlarmFiredReceiver.EXTRA_ALARM_ID))
        assertEquals(scheduledAt.toEpochMilliseconds(), carried.getLongExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, 0))

        // The process dies while the emergency ring plays; storage works again in the new one.
        service.destroy()
        val app = WakeApp()
        app.app.sendBroadcast(Intent(carried))
        shadowOf(Looper.getMainLooper()).idle()
        app.koin.get<ApplicationScope>().awaitChildren()
        app.startService(assertNotNull(shadowOf(app.app).nextStartedService))
        app.awaitRinging()

        assertEquals(alarmA.id, assertIs<SessionState.Ringing>(app.engine.state.value).session.config.alarmId)
        assertNull(app.runtime.emergency.value)
    }

    @Test
    fun `I'm up on the emergency ring cancels its backup slot`() {
        val broken = FakeActiveSessionStore().apply { commitFailure = DomainError.StorageFailure("disk full") }
        val app = WakeApp(store = broken)
        upsert(alarmA)
        app.ring(fired)
        app.awaitUntil("the emergency ring") { app.runtime.emergency.value != null }
        assertTrue(armedSlot(app))

        app.runtime.stopEmergency()

        assertFalse(armedSlot(app), "no slot left after the emergency ring")
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

        upsert(alarmA)
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
        // The state is published before its effects run: wait for the unmute too.
        app.awaitUntil("the grace window ends") { app.engine.state.value is SessionState.Loud && !app.player.isMuted }

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

        upsert(alarmA)
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

        upsert(alarmA)
        app.ring(fired)
        app.awaitMerges(stored.session.sessionId)

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

    private fun slot(app: WakeApp): ShadowAlarmManager.ScheduledAlarm? =
        shadowOf(app.app.getSystemService(AlarmManager::class.java)).scheduledAlarms.singleOrNull {
            shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT
        }

    /** The armed slot fired: the system no longer holds it. */
    private fun slotFired(app: WakeApp) {
        app.app.getSystemService(AlarmManager::class.java).cancel(assertNotNull(slot(app)?.operation, "a slot is armed"))
        assertNull(slot(app))
    }

    /** The slot is armed about one heartbeat from now and carries alarm A. */
    private fun assertSlotCarriesAlarmA(app: WakeApp) {
        val armed = assertNotNull(slot(app), "the slot is armed")
        assertEquals(alarmA.id, shadowOf(armed.operation).savedIntent.getStringExtra(AlarmFiredReceiver.EXTRA_ALARM_ID))
        val inMillis = armed.triggerAtTime - System.currentTimeMillis()
        assertTrue(inMillis in 50_000..60_000, "one heartbeat ahead: $inMillis ms")
    }

    /** Rings alarm A into the emergency ring ([broken] store), then delivers its backup slot (carrying A) after it fired. */
    private fun backupSlotFire(broken: FakeActiveSessionStore): WakeApp {
        val app = WakeApp(store = broken)
        upsert(alarmA)
        val controller = app.ring(fired)
        app.awaitUntil("the emergency ring") { app.runtime.emergency.value != null }
        slotFired(app)

        controller.withIntent(WakeService.slotIntent(app.app, fired)).startCommand(0, 2)
        app.awaitUntil("the backup slot is armed again") { slot(app) != null }
        return app
    }

    @Test
    fun `a slot fire while the emergency ring plays arms its backup slot again, carrying the alarm`() {
        val app = backupSlotFire(FakeActiveSessionStore().apply { commitFailure = DomainError.StorageFailure("disk full") })

        assertSlotCarriesAlarmA(app)
        assertNotNull(app.runtime.emergency.value, "it rings on")
    }

    @Test
    fun `a slot fire while the emergency ring plays and the session cannot be loaded still arms its backup slot`() {
        val app = backupSlotFire(FakeActiveSessionStore().apply { loadFailure = DomainError.StorageFailure("disk I/O error") })

        assertSlotCarriesAlarmA(app)
    }

    @Test
    fun `a slot fire with no alarm whose session cannot be loaded rings the emergency default`() {
        val broken = FakeActiveSessionStore().apply { loadFailure = DomainError.StorageFailure("disk I/O error") }
        val app = WakeApp(store = broken)

        app.startService(WakeService.intent(app.app, WakeService.ACTION_SLOT))
        app.awaitUntil("the emergency ring") { app.runtime.emergency.value != null }

        assertTrue(app.logs().any { it.startsWith("EmergencyRingStarted cause=session not loaded") }, "${app.logs()}")
        assertNotNull(slot(app), "with its backup slot")
    }

    @Test
    fun `a slot carrying an alarm into a broken store rings the emergency default with a backup slot carrying the alarm`() {
        val broken = FakeActiveSessionStore().apply { commitFailure = DomainError.StorageFailure("disk full") }
        val app = WakeApp(store = broken)
        upsert(alarmA)

        app.startService(WakeService.slotIntent(app.app, fired))
        app.awaitUntil("the emergency ring") { app.runtime.emergency.value != null }

        assertEquals(EmergencyRing(scheduledAt, alarmA.volumePercent), app.runtime.emergency.value)
        assertSlotCarriesAlarmA(app)
    }

    @Test
    fun `a slot carrying an alarm 30 minutes or more past ignores it, logged, and shuts down`() {
        val app = WakeApp()
        upsert(alarmA)
        val stale = AlarmFired(alarmA.id, Instant.fromEpochMilliseconds(System.currentTimeMillis()) - 31.minutes)

        val service = app.startService(WakeService.slotIntent(app.app, stale)).get()
        app.awaitUntil("the service stops") { shadowOf(service).isStoppedBySelf }

        assertEquals(SessionState.Idle, app.engine.state.value)
        assertTrue(app.mediaPlayers.isEmpty(), "nothing rings")
        assertTrue(app.logs().any { it == "FireIgnored kind=SessionSlot alarmId=alarm-a reason=alarm 30m or more past" }, "${app.logs()}")
    }

    @Test
    fun `an alarm whose first session load fails merges into the ringing session the next load finds`() {
        val stored = SessionState.Ringing(aSession())
        val inner = FakeActiveSessionStore().apply { row = SessionJson.encode(stored) }
        val failNextLoad = AtomicBoolean(false)
        val flaky =
            object : ActiveSessionStore by inner {
                override suspend fun load(): Outcome<StoredSession, DomainError> =
                    if (failNextLoad.getAndSet(false)) Outcome.Failure(DomainError.StorageFailure("locked")) else inner.load()
            }
        val app = WakeApp(store = flaky)
        app.koin.get<ApplicationScope>().awaitChildren()
        upsert(alarmA)

        failNextLoad.compareAndSet(false, true)
        app.ring(fired)
        // Story 2.9: the merge is recorded as a session_merge row (it is no longer only logged).
        assertEquals(alarmA.id, app.awaitMerges(stored.session.sessionId).single().alarmId)

        assertEquals(stored.session.sessionId, assertIs<SessionState.Ringing>(app.engine.state.value).session.sessionId)
        assertEquals(1, app.mediaPlayers.size, "one player")
    }

    @Test
    fun `a refused alarm start through the receiver arms the session slot one heartbeat ahead carrying the alarm`() {
        val app =
            WakeApp(
                starter = { context ->
                    WakeServiceStarter(context, GlobalContext.get().get()) { throw ForegroundServiceStartNotAllowedException("background") }
                },
            )

        fireThroughReceiver(app)

        assertSlotCarriesAlarmA(app)
        assertNull(shadowOf(app.app).nextStartedService, "no service started")
    }

    @Test
    fun `a slot start with no ringing notification up posts the quiet one without a full-screen intent`() {
        // The orphan slot shuts the service down once its session load returns, and Robolectric clears the foreground
        // notification on that stop. The load waits for the test, so the notification is read before it, whenever the
        // store answers (with Room it could answer inside the looper idle after startCommand: flaky on fast CI runners).
        val loadAllowed = CompletableDeferred<Unit>()
        val empty = FakeActiveSessionStore()
        val gated =
            object : ActiveSessionStore by empty {
                override suspend fun load(): Outcome<StoredSession, DomainError> {
                    loadAllowed.await()
                    return empty.load()
                }
            }
        val app = WakeApp(store = gated)

        val service = app.startService(WakeService.intent(app.app, WakeService.ACTION_SLOT)).get()

        val notification = assertNotNull(shadowOf(service).lastForegroundNotification, "in the foreground")
        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, notification.channelId)
        assertNull(notification.fullScreenIntent)
        loadAllowed.complete(Unit)
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
