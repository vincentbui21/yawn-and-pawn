package com.yawnandpawn.app.android.wake

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.call.CallState
import com.yawnandpawn.app.android.sound.LibrarySoundResolver
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.CheckAnswer
import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.entryEffects
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeCrashReporter
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.SchedulerCall
import com.yawnandpawn.app.testing.aSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.datetime.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Story 1.14: the wake runtime carries out the session's effects with the real adapters over a fake playback. */
@RunWith(RobolectricTestRunner::class)
class WakeRuntimeTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val vibratorShadow = shadowOf(context.getSystemService(VibratorManager::class.java).defaultVibrator as Vibrator)
    private val logger = FakeLogger()
    private val crashReporter = FakeCrashReporter()
    private val playbacks = FakePlaybackFactory()
    private val scheduler = FakeAlarmScheduler()
    private val dispatcher = StandardTestDispatcher()
    private val volume = AlarmVolume(context, logger)
    private val other = AlarmSound.File("content://sounds/other")
    private val resolver = SoundResolver { ref -> if (ref == "test:other") other else LibrarySoundResolver().resolve(ref) }
    private val player = AndroidAlarmPlayer(playbacks, resolver, volume, FakeMonotonicClock(), CoroutineScope(dispatcher), logger)
    private val vibrator = AlarmVibrator(context)
    private val notifier = WakeNotifier(context, FakeTimeZoneProvider(TimeZone.UTC))
    private val started = mutableListOf<Intent>()
    private var refuseStart = false
    private val starter =
        WakeServiceStarter(context, logger) { intent ->
            if (refuseStart) throw ForegroundServiceStartNotAllowedException("background")
            started += intent
        }
    private var now = TimeSnapshot(wallMillis = 1_000_000, elapsedMillis = 5_000, bootCount = 1)
    private var state: SessionState = SessionState.Idle
    private var inCall = false
    private val runtime =
        WakeRuntime(
            WakeOutputs(player, vibrator, notifier, volume, scheduler, crashReporter),
            starter,
            CoroutineScope(dispatcher),
            logger,
            { now },
            { state },
            calls =
                object : CallState {
                    override fun inCall(): Boolean = inCall
                },
        )

    private val session: SessionData = aSession()
    private val ringing = SessionState.Ringing(session)

    /**
     * This test's [volume] shares the device-protected "wake_runtime" preferences with the app's own player. The app's
     * start (session restore, then `restoreVolumeIfIdle` when Idle) runs on ApplicationScope threads; on a slow machine it
     * could restore and forget the user volume this test's ring saved, so the ring's end then had nothing to restore.
     */
    @Before
    fun awaitAppStart() = GlobalContext.get().get<ApplicationScope>().awaitChildren()

    private fun enter(next: SessionState) {
        state = next
        runBlocking { entryEffects(next).forEach { runtime.apply(it) } }
    }

    private fun run(vararg effects: SessionEffect) = runBlocking { effects.forEach { runtime.run(it) } }

    private fun alarmStream() = audio.getStreamVolume(AudioManager.STREAM_ALARM)

    /** [from] plus [seconds] of real time, wall and monotonic together. */
    private fun later(
        from: TimeSnapshot,
        seconds: Int,
    ) = TimeSnapshot(from.wallMillis + seconds * 1_000L, from.elapsedMillis + seconds * 1_000L, from.bootCount)

    @Test
    fun `a ringing state plays at the set volume, vibrates, arms the heartbeat slot, posts the notification and starts the service`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)

        // A new session's step: its one-shot StartWakeRuntime, then the entry effects.
        run(SessionEffect.StartWakeRuntime(session.sessionId))
        enter(ringing)

        assertEquals(AlarmSound.Default, player.sound)
        assertEquals(0.2f, player.gain, 1e-6f, "the ramp starts at 20 percent of the set volume")
        assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.8).roundToInt(), alarmStream())
        assertTrue(vibrator.isVibrating)
        val attributes = vibratorShadow.vibrationAttributesFromLastVibration as VibrationAttributes
        assertEquals(VibrationAttributes.USAGE_ALARM, attributes.usage)
        assertEquals(listOf<SchedulerCall>(SchedulerCall.ArmSessionSlot(Deadline.after(now, SessionReducer.HEARTBEAT))), scheduler.calls)
        assertEquals(session.config.scheduledAt, notifier.shownFor)
        assertEquals(1, shadowOf(notifications).size())
        assertEquals(listOf(WakeService.ACTION_RESTORE), started.map { it.action })
    }

    @Test
    fun `applying the same entry effects again changes nothing`() {
        enter(ringing)
        val calls = scheduler.calls

        enter(ringing)
        enter(SessionState.Loud(session))

        assertEquals(1, playbacks.opened.size, "one player, never restarted")
        assertEquals(calls, scheduler.calls, "the slot is not re-armed")
        assertEquals(1, started.size, "the service start is asked once until it runs")
        assertEquals(1, shadowOf(notifications).size())
    }

    @Test
    fun `an alarm without vibration never starts the vibrator`() {
        enter(SessionState.Ringing(session.copy(config = session.config.copy(vibration = false))))

        assertFalse(vibrator.isVibrating)
        assertFalse(vibratorShadow.isVibrating)
        assertNotNull(player.sound)
    }

    @Test
    fun `grace mutes the sound and stops a vibration it does not want, and its end unmutes with a strong pulse`() {
        enter(ringing)
        run(SessionEffect.Mute)

        enter(SessionState.Grace(session))

        assertTrue(player.isMuted)
        assertFalse(vibrator.isVibrating, "vibrate in grace is off")
        run(SessionEffect.UnmuteToVolume(80), SessionEffect.StrongHaptic)
        enter(SessionState.Loud(session))
        assertFalse(player.isMuted)
        assertEquals(1f, player.gain)
        assertTrue(vibrator.isVibrating)
    }

    @Test
    fun `with vibrate in grace on the grace window keeps vibrating, and Loud vibrates again either way (Story 2-8)`() {
        val inGrace = session.copy(config = session.config.copy(vibrateInGrace = true))
        enter(SessionState.Ringing(inGrace))
        run(SessionEffect.Mute)

        enter(SessionState.Grace(inGrace))

        assertTrue(player.isMuted)
        assertTrue(vibrator.isVibrating, "vibrate in grace is on")
        run(SessionEffect.UnmuteToVolume(80))
        enter(SessionState.Loud(inGrace))
        assertTrue(vibrator.isVibrating)
    }

    @Test
    fun `the alarm stream is set at the ring start and the grace end, never in between, and the user volume comes back (Story 2-8)`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val setVolume = (audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.8).roundToInt()

        enter(ringing)
        assertEquals(setVolume, alarmStream(), "ring start")
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        // A heartbeat or a tap re-runs the entry effects: the volume is not enforced.
        enter(ringing)
        assertEquals(1, alarmStream())
        run(SessionEffect.Mute)
        enter(SessionState.Grace(session))
        assertEquals(1, alarmStream())

        run(SessionEffect.UnmuteToVolume(80))
        enter(SessionState.Loud(session))
        assertEquals(setVolume, alarmStream(), "grace end")
        assertEquals(1f, player.gain, "full gain once the grace window ends")
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        enter(SessionState.Loud(session))
        assertEquals(1, alarmStream(), "not re-applied during Loud")

        runtime.endSession()
        assertEquals(2, alarmStream(), "the user's own volume is back")
    }

    @Test
    fun `the purchase hand-back hook re-asserts the ring volume once, only while the session rings loud (Story 2-8)`() {
        val setVolume = (audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.8).roundToInt()
        enter(ringing)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)

        runtime.reassertRingVolume()

        assertEquals(setVolume, alarmStream(), "ringing")
        enter(SessionState.Loud(session))
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)

        runtime.reassertRingVolume()

        assertEquals(setVolume, alarmStream(), "loud")
        // Muted (grace), paused by a call or snoozed: nothing.
        run(SessionEffect.Mute)
        enter(SessionState.Grace(session))
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        runtime.reassertRingVolume()
        assertEquals(1, alarmStream(), "grace")
        run(SessionEffect.PauseSound)
        enter(SessionState.Loud(session.copy(pausedAt = now)))
        runtime.reassertRingVolume()
        assertEquals(1, alarmStream(), "paused by a call")
        enter(SessionState.Snoozed(session.copy(snoozeEnd = Deadline.after(now, 9.minutes), interactionDeadline = null)))
        runtime.reassertRingVolume()
        assertEquals(1, alarmStream(), "snoozed")
    }

    @Test
    fun `a ring that starts during a call opens silent, never starts the sound and does not vibrate (Story 2-7)`() {
        inCall = true

        enter(ringing)

        val playback = checkNotNull(playbacks.current)
        assertEquals(0, playback.started, "not one audible frame")
        assertTrue(player.isPaused)
        assertFalse(vibrator.isVibrating)
        // The heartbeat re-applies the entry effects: still silent while the call lasts.
        enter(ringing)
        assertEquals(0, playback.started)
    }

    @Test
    fun `a call that ends before the session paused lets the held ring play and vibrate (Story 2-7)`() {
        inCall = true
        enter(ringing)
        inCall = false

        runtime.onCallOver()

        assertEquals(1, checkNotNull(playbacks.current).started)
        assertFalse(player.isPaused)
        assertTrue(vibrator.isVibrating)
    }

    @Test
    fun `onCallOver changes nothing while the session is paused for a call or snoozed (Story 2-7)`() {
        enter(ringing)
        run(SessionEffect.PauseSound)
        enter(SessionState.Ringing(session.copy(pausedAt = now)))

        runtime.onCallOver()

        assertTrue(player.isPaused, "the session still pauses for the call")
        enter(SessionState.Snoozed(session.copy(snoozeEnd = Deadline.after(now, 9.minutes), interactionDeadline = null)))
        runtime.onCallOver()
        assertNull(player.sound)
    }

    @Test
    fun `a call pauses sound and vibration and its end resumes them`() {
        enter(ringing)
        val paused = SessionState.Ringing(session.copy(pausedAt = now))

        run(SessionEffect.PauseSound)
        enter(paused)

        assertTrue(player.isPaused)
        assertFalse(vibrator.isVibrating)
        run(SessionEffect.ResumeSound)
        enter(ringing)
        assertFalse(player.isPaused)
        assertTrue(vibrator.isVibrating)
    }

    @Test
    fun `a snooze silences the ring and arms the slot at the snooze end`() {
        enter(ringing)
        val snoozeEnd = Deadline.after(now, 9.minutes)

        enter(SessionState.Snoozed(session.copy(snoozeEnd = snoozeEnd, interactionDeadline = null)))

        assertNull(player.sound)
        assertFalse(vibrator.isVibrating)
        assertEquals(SchedulerCall.ArmSessionSlot(snoozeEnd), scheduler.calls.last())
    }

    @Test
    fun `the session end stops everything, restores the user volume, removes the notification and cancels the slot`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        enter(ringing)

        run(SessionEffect.StopSound, SessionEffect.CancelSlot, SessionEffect.ClearRuntimeSession(session.sessionId))

        assertNull(player.sound)
        assertFalse(vibrator.isVibrating)
        assertEquals(2, alarmStream())
        assertEquals(0, shadowOf(notifications).size())
        assertEquals(SchedulerCall.CancelSessionSlot, scheduler.calls.last())
        assertNull(scheduler.armed[RequestCodes.SESSION_SLOT])
    }

    @Test
    fun `effects of later stories are logged by type name only, and ignored events by type and session id`() {
        run(
            SessionEffect.StartCheckStep(0),
            SessionEffect.WrongAnswerFeedback,
            SessionEffect.PlayMotivation,
            SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Failed),
            SessionEffect.RecordMergedOccurrence(session.sessionId, "alarm-b", Instant.fromEpochMilliseconds(0)),
            SessionEffect.LogIgnored(CheckAnswer.Placeholder::class.simpleName.orEmpty(), session.sessionId),
        )

        assertEquals(
            listOf(
                LogEvent.SessionEffectLogged("StartCheckStep", entry = false),
                LogEvent.SessionEffectLogged("WrongAnswerFeedback", entry = false),
                LogEvent.SessionEffectLogged("PlayMotivation", entry = false),
                LogEvent.SessionEffectLogged("ShowPurchaseOutcome", entry = false),
                LogEvent.SessionEffectLogged("RecordMergedOccurrence", entry = false),
                LogEvent.SessionEventIgnored("Placeholder", session.sessionId),
            ),
            logger.events,
        )
        assertTrue(logger.events.none { "alarm-b" in it.toString() }, "no effect contents are logged")
    }

    @Test
    fun `a refused service start is logged, and the ring still plays with its slot armed`() {
        refuseStart = true

        enter(ringing)

        assertTrue(
            logger.events.any { it is LogEvent.OperationFailed && it.operation == "start wake service" },
            "${logger.events}",
        )
        assertNotNull(player.sound)
        assertTrue(scheduler.calls.any { it is SchedulerCall.ArmSessionSlot }, "the slot (at most 60 s) brings the ring back")
    }

    @Test
    fun `while the service runs the runtime never starts it again`() {
        runtime.onServiceStarted()

        enter(ringing)

        assertTrue(started.isEmpty())
        assertTrue(runtime.isServiceRunning)
    }

    @Test
    fun `the emergency ring plays the default with vibration and the notification until I am up`() {
        val alarmAt = Instant.fromEpochMilliseconds(7_000_000)

        runtime.startEmergency(alarmAt, volumePercent = 60, cause = "commit failed")

        assertEquals(EmergencyRing(alarmAt, 60), runtime.emergency.value)
        assertEquals(AlarmSound.Default, player.sound)
        assertEquals(1f, player.gain)
        assertTrue(vibrator.isVibrating)
        assertEquals(alarmAt, notifier.shownFor)
        assertEquals(alarmAt, runtime.alarmAt())
        runtime.stopEmergency()
        assertNull(runtime.emergency.value)
        assertNull(player.sound)
        assertFalse(vibrator.isVibrating)
        assertEquals(0, shadowOf(notifications).size())
        assertEquals(
            listOf<LogEvent>(LogEvent.EmergencyRingStarted("commit failed"), LogEvent.EmergencyRingStopped("I'm up")),
            logger.events.filter { it is LogEvent.EmergencyRingStarted || it is LogEvent.EmergencyRingStopped },
        )
    }

    @Test
    fun `the emergency ring stops by itself after 30 minutes`() {
        runtime.startEmergency(Instant.fromEpochMilliseconds(0), volumePercent = 60, cause = "commit failed")

        dispatcher.scheduler.advanceTimeBy(30.minutes - 1.minutes)
        dispatcher.scheduler.runCurrent()
        assertNotNull(runtime.emergency.value)
        dispatcher.scheduler.advanceTimeBy(2.minutes)
        dispatcher.scheduler.runCurrent()

        assertNull(runtime.emergency.value)
        assertNull(player.sound)
        assertEquals(LogEvent.EmergencyRingStopped("30-minute limit"), logger.events.last())
    }

    @Test
    fun `after a crash a ringing session switches to the default sound and an alarm not yet started gets the emergency ring`() {
        enter(SessionState.Ringing(session.copy(config = session.config.copy(soundRef = "test:other"))))
        assertEquals(other, player.sound)
        runtime.ringDefaultAfterCrash(pending = null)
        assertEquals(AlarmSound.Default, player.sound)

        runtime.endSession()
        state = SessionState.Idle
        runtime.ringDefaultAfterCrash(pending = AlarmFired("alarm-a", Instant.fromEpochMilliseconds(42)))

        assertEquals(Instant.fromEpochMilliseconds(42), runtime.emergency.value?.alarmAt)
        assertEquals(AlarmSound.Default, player.sound)
    }

    @Test
    fun `a session that starts during an emergency ring takes it over`() {
        runtime.startEmergency(Instant.fromEpochMilliseconds(0), volumePercent = 60, cause = "commit failed")

        run(SessionEffect.StartWakeRuntime(session.sessionId))
        enter(ringing)

        assertNull(runtime.emergency.value)
        assertEquals(AlarmSound.Default, player.sound)
        assertEquals(0.2f, player.gain, 1e-6f, "the session's own ramp")
    }

    @Test
    fun `a restored session that takes over the emergency ring plays with no ramp`() {
        runtime.startEmergency(Instant.fromEpochMilliseconds(0), volumePercent = 60, cause = "session not loaded")

        // ProcessRestored: entry effects only, while the emergency default sound plays.
        enter(ringing)

        assertNull(runtime.emergency.value)
        assertEquals(1f, player.gain, 1e-6f, "no ramp on a restored ring")
    }

    @Test
    fun `a new session after a restored ring and an emergency ring ramps again`() {
        enter(ringing)
        assertEquals(1f, player.gain, 1e-6f, "restored")
        state = SessionState.Idle
        runtime.endSession()
        runtime.startEmergency(Instant.fromEpochMilliseconds(0), volumePercent = 60, cause = "commit failed")

        run(SessionEffect.StartWakeRuntime(session.sessionId))
        enter(ringing)

        assertEquals(0.2f, player.gain, 1e-6f, "the restored flag of the earlier ring is not kept")
    }

    @Test
    fun `at app start with no session a saved user volume is put back`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        volume.setForRing(100)

        runtime.restoreVolumeIfIdle()

        assertEquals(2, alarmStream())
        assertNull(volume.saved)
    }

    @Test
    fun `the foreground notification is the one entry effects would post`() {
        val alarmAt = session.config.scheduledAt

        val notification = runtime.foregroundNotification(alarmAt)
        runtime.foregroundPosted(alarmAt)
        runtime.onServiceStarted()
        enter(ringing)

        assertEquals(WakeNotifier.CHANNEL_ID, notification.channelId)
        // The system lists a startForeground post only after a while: WakeUiShown right after it posts nothing more.
        assertEquals(0, shadowOf(notifications).size(), "already shown by startForeground, not posted again")
    }

    @Test
    fun `a ringing notification the user swiped away is posted again by the next WakeUiShown`() {
        val alarmAt = session.config.scheduledAt
        notifications.notify(WakeNotifier.NOTIFICATION_ID, runtime.foregroundNotification(alarmAt))
        runtime.foregroundPosted(alarmAt)
        runtime.onServiceStarted()

        // The swipe removes it and its delete intent tells the runtime (WakeService.ACTION_REPOST).
        notifications.cancel(WakeNotifier.NOTIFICATION_ID)
        runtime.notificationSwiped()
        enter(ringing)

        assertEquals(1, shadowOf(notifications).size(), "posted again")
    }

    @Test
    fun `a ringing notification built but not posted does not stop WakeUiShown from posting it`() {
        runtime.foregroundNotification(session.config.scheduledAt)

        enter(ringing)

        assertEquals(1, shadowOf(notifications).size())
    }

    @Test
    fun `a start with no alarm to show uses the quiet notification without a full-screen intent`() {
        val quiet = runtime.foregroundNotification(null)
        runtime.foregroundPosted(null)

        assertEquals(WakeNotifier.QUIET_CHANNEL_ID, quiet.channelId)
        assertNull(quiet.fullScreenIntent)
        assertEquals(NotificationManager.IMPORTANCE_LOW, notifications.getNotificationChannel(WakeNotifier.QUIET_CHANNEL_ID).importance)
        assertNull(runtime.shownAlarmAt())
    }

    @Test
    fun `a slot that fired early during a snooze is armed again at the snooze end`() {
        val snoozeEnd = Deadline.after(now, 9.minutes)
        val snoozed = SessionState.Snoozed(session.copy(snoozeEnd = snoozeEnd, interactionDeadline = null))
        enter(snoozed)
        enter(snoozed)
        assertEquals(1, scheduler.calls.count { it == SchedulerCall.ArmSessionSlot(snoozeEnd) }, "idempotent")

        // The wall clock jumped forward: the slot fired before the snooze ended, and SlotFired was ignored.
        runtime.onSlotFired()
        enter(snoozed)

        assertEquals(2, scheduler.calls.count { it == SchedulerCall.ArmSessionSlot(snoozeEnd) })
        assertEquals(snoozeEnd.wallMillis, scheduler.armed[RequestCodes.SESSION_SLOT])
    }

    @Test
    fun `an ArmSlot one-shot re-arms even while a slot is armed, and a failed arming is logged and retried`() {
        enter(ringing)
        val next = Deadline.after(now, 2.minutes)

        run(SessionEffect.ArmSlot(next))
        assertEquals(SchedulerCall.ArmSessionSlot(next), scheduler.calls.last())

        scheduler.failure = DomainError.SchedulerFailure("limit")
        run(SessionEffect.ArmSlot(next))
        assertEquals(LogEvent.OperationFailed("arm session slot", "scheduler failure: limit"), logger.events.last())
        scheduler.failure = null
        enter(ringing)
        assertEquals(SchedulerCall.ArmSessionSlot(Deadline.after(now, SessionReducer.HEARTBEAT)), scheduler.calls.last(), "retried")
    }

    @Test
    fun `an effect that throws is reported and the ring switches to the default sound`() {
        playbacks.crashing += other

        enter(SessionState.Ringing(session.copy(config = session.config.copy(soundRef = "test:other"))))

        assertEquals("decoder missing for $other", crashReporter.reported.single().message)
        assertEquals(AlarmSound.Default, player.sound, "never silent")
        assertTrue(logger.events.contains(LogEvent.OperationFailed("wake effect SoundAt", "NotImplementedError")))
    }

    @Test
    fun `a crash while snoozed with an alarm being handled rings the emergency default`() {
        state = SessionState.Snoozed(session.copy(snoozeEnd = Deadline.after(now, 9.minutes), interactionDeadline = null))

        runtime.ringDefaultAfterCrash(pending = AlarmFired("alarm-b", Instant.fromEpochMilliseconds(42)))

        assertEquals(Instant.fromEpochMilliseconds(42), runtime.emergency.value?.alarmAt)
        assertEquals(AlarmSound.Default, player.sound)
    }

    @Test
    fun `a restored ring plays at the set volume with no ramp, and later steps keep the same ring`() {
        // ProcessRestored: entry effects only, nothing playing yet.
        enter(ringing)
        assertEquals(1f, player.gain, 1e-6f, "no ramp on a restored ring")

        enter(ringing)
        enter(SessionState.Loud(session))
        assertEquals(1, playbacks.opened.size, "the same request: never restarted")

        // The next ring (after a snooze) is announced by its one-shot slot arming: it ramps again.
        enter(SessionState.Snoozed(session.copy(snoozeEnd = Deadline.after(now, 9.minutes), interactionDeadline = null)))
        run(SessionEffect.ArmSlot(Deadline.after(now, SessionReducer.HEARTBEAT)))
        enter(SessionState.Ringing(session.copy(ringIndex = 2)))
        assertEquals(0.2f, player.gain, 1e-6f)
    }

    @Test
    fun `a restored grace stays muted and its end plays the loud ring at the set volume with no ramp`() {
        enter(SessionState.Grace(session))
        assertNull(player.sound, "nothing plays during a restored grace window")

        run(SessionEffect.UnmuteToVolume(80), SessionEffect.StrongHaptic)
        enter(SessionState.Loud(session))

        assertNotNull(player.sound)
        assertEquals(1f, player.gain, 1e-6f)
    }

    @Test
    fun `a heartbeat slot that is already due (its fire never handled here) is armed again`() {
        enter(ringing)
        val first = Deadline.after(now, SessionReducer.HEARTBEAT)
        assertEquals(SchedulerCall.ArmSessionSlot(first), scheduler.calls.last())

        now = later(now, 61)
        enter(ringing)

        assertEquals(SchedulerCall.ArmSessionSlot(Deadline.after(now, SessionReducer.HEARTBEAT)), scheduler.calls.last())
        assertEquals(2, scheduler.calls.count { it is SchedulerCall.ArmSessionSlot })
    }

    @Test
    fun `the session end cancels the slot even when this process armed none`() {
        runtime.endSession()

        assertEquals(listOf<SchedulerCall>(SchedulerCall.CancelSessionSlot), scheduler.calls)
    }

    @Test
    fun `the session end keeps a slot that carries another alarm, armed again one heartbeat from now`() {
        val refused = AlarmFired("alarm-b", Instant.fromEpochMilliseconds(now.wallMillis - 60_000))
        scheduler.armSessionSlot(Deadline.after(now, 20.seconds), refused)
        scheduler.clearCalls()

        runtime.endSession()

        val again = SchedulerCall.ArmSessionSlot(Deadline.after(now, SessionReducer.HEARTBEAT), refused)
        assertEquals(listOf(SchedulerCall.CancelSessionSlot, again), scheduler.calls)
    }

    @Test
    fun `the session end drops a carried alarm 30 minutes past`() {
        val stale = AlarmFired("alarm-b", Instant.fromEpochMilliseconds(now.wallMillis - 30 * 60_000))
        scheduler.armSessionSlot(Deadline.after(now, 20.seconds), stale)
        scheduler.clearCalls()

        runtime.endSession()

        assertEquals(listOf<SchedulerCall>(SchedulerCall.CancelSessionSlot), scheduler.calls)
    }

    @Test
    fun `the emergency ring arms a backup slot carrying its alarm, re-armed on each slot fire, cancelled by I'm up`() {
        val alarm = AlarmFired("alarm-b", Instant.fromEpochMilliseconds(42))

        runtime.startEmergency(alarm.scheduledAt, volumePercent = 60, cause = "commit failed", alarm = alarm)
        val backup = SchedulerCall.ArmSessionSlot(Deadline.after(now, SessionReducer.HEARTBEAT), alarm)
        assertEquals(listOf<SchedulerCall>(backup), scheduler.calls)

        now = later(now, 60)
        runtime.onSlotFired()
        runtime.keepEmergencySlot()
        assertEquals(SchedulerCall.ArmSessionSlot(Deadline.after(now, SessionReducer.HEARTBEAT), alarm), scheduler.calls.last())

        runtime.stopEmergency()
        assertEquals(SchedulerCall.CancelSessionSlot, scheduler.calls.last())
        assertNull(scheduler.armed[RequestCodes.SESSION_SLOT])
        runtime.keepEmergencySlot()
        assertEquals(SchedulerCall.CancelSessionSlot, scheduler.calls.last(), "nothing once it stopped")
    }

    @Test
    fun `a session that takes over the emergency ring arms its own heartbeat slot without the alarm`() {
        val alarm = AlarmFired("alarm-b", Instant.fromEpochMilliseconds(42))
        runtime.startEmergency(alarm.scheduledAt, volumePercent = 60, cause = "commit failed", alarm = alarm)

        enter(ringing)

        assertNull(runtime.emergency.value)
        assertEquals(SchedulerCall.ArmSessionSlot(Deadline.after(now, SessionReducer.HEARTBEAT)), scheduler.calls.last())
    }

    @Test
    fun `stopping an emergency ring during a snooze keeps the snooze slot`() {
        val snoozeEnd = Deadline.after(now, 9.minutes)
        enter(SessionState.Snoozed(session.copy(snoozeEnd = snoozeEnd, interactionDeadline = null)))
        runtime.startEmergency(Instant.fromEpochMilliseconds(0), volumePercent = 60, cause = "merge not saved")

        runtime.stopEmergency()

        assertNull(player.sound)
        assertEquals(snoozeEnd.wallMillis, scheduler.armed[RequestCodes.SESSION_SLOT], "the snooze still ends")
    }
}
