package com.yawnandpawn.app.android.wake

import android.app.AlarmManager
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.provider.Settings
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.CheckAnswer
import com.yawnandpawn.app.core.session.CheckPlan
import com.yawnandpawn.app.core.session.CheckStep
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.PurchaseVerdict
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakeSnoozeAvailability
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.junit.After
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Story 2.10: every PRD §6.4 conflict rule as one named scenario through the real wake runtime: `WakeService`, the real
 * `SessionEngine`, Room `runtime.db` and `app.db` (device-protected paths), the call adapter on Robolectric's audio
 * manager, with `FakeBilling`, a fake policy that offers a snooze, the fake lock state and fake clocks. Billing is not
 * wired before Epic 4, so its results are dispatched as the billing adapter will. Every scenario ends with the history
 * row, the merge rows and an empty `runtime.db`.
 */
@RunWith(RobolectricTestRunner::class)
class SessionConflictScenariosTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val clock = FakeClock(Instant.parse("2027-03-08T06:00:00Z"))
    private val monotonic = FakeMonotonicClock(elapsedMillis = 5_000_000)
    private val billing = FakeBilling()
    private val lock = FakeUserLockState(unlocked = true)
    private val testAlarms = FakeTestAlarmStore()

    /** The Epic 4 policy's shape: unavailable before the first unlock (2.3 precedence), otherwise a snooze on sale. */
    private val policy =
        object : SnoozeAvailabilityPolicy {
            private val onSale = FakeSnoozeAvailability()

            override fun availability(session: SessionData): SnoozeAvailability =
                if (lock.isUserUnlocked()) {
                    onSale.availability(session)
                } else {
                    SnoozeAvailability.Unavailable(UnavailableReason.BeforeFirstUnlock)
                }
        }

    private var app = newProcess()
    private val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")
    private val alarmA = anAlarm(id = "alarm-a", requestCode = 1000, repeatDays = DayOfWeek.entries.toSet())
    private val alarmB =
        anAlarm(
            id = "alarm-b",
            requestCode = 1001,
            repeatDays = DayOfWeek.entries.toSet(),
        ).copy(snoozeLengthMinutes = 20, graceSeconds = 60)
    private val audio: AudioManager get() = app.app.getSystemService(AudioManager::class.java)

    @After
    fun normalMode() {
        audio.mode = AudioManager.MODE_NORMAL
    }

    /** A process over the same storage, the same phone clocks and the same billing. */
    private fun newProcess(userLock: UserLockState? = lock) =
        WakeApp(clock = clock, monotonic = monotonic, billing = billing, userLock = userLock, policy = policy, testAlarms = testAlarms)

    // ----- driving the phone -----

    private fun store(vararg alarms: Alarm) =
        alarms.forEach { assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(it) }) }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        app.koin.get<ApplicationScope>().awaitChildren()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Real time passes: both phone clocks and the main looper (the service's timers) move by [duration]. */
    private fun pass(duration: Duration) {
        clock.advanceBy(duration)
        monotonic.advanceBy(duration)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(duration.inWholeMilliseconds))
        tick()
    }

    /** The service's deadline tick, run now (the looper above already lets it run; this makes it deterministic). */
    private fun tick() {
        val job = app.koin.get<ApplicationScope>().launch { app.engine.tick() }
        app.awaitUntil("the tick") { job.isCompleted }
    }

    private fun state(): SessionState = app.engine.state.value

    private fun session(): SessionData = assertIs<SessionState.Active>(state()).session

    private fun ringA(): ServiceController<WakeService> {
        store(alarmA, alarmB)
        val service = app.ring(AlarmFired(alarmA.id, scheduledAt))
        app.awaitRinging()
        return service
    }

    private fun imUpToGrace() {
        app.dispatch(SessionEvent.ImUpTapped)
        assertIs<SessionState.Grace>(state())
    }

    private fun graceToLoud() {
        pass(20.seconds)
        app.awaitUntil("Loud") { state() is SessionState.Loud }
    }

    private fun grantSnooze(seeds: List<Long> = listOf(42L, 43L)) {
        val offer = (policy.availability(session()) as SnoozeAvailability.Available).offer
        val token = PurchaseToken("token-${session().snoozesGranted}")
        app.dispatch(SessionEvent.PurchaseGranted(offer.productId, token, PurchaseVerdict.Grant, seeds))
        assertIs<SessionState.Snoozed>(state())
    }

    /**
     * The phone (Robolectric's audio manager) enters audio [mode], as the dialer does. Only the test sets it; the app never
     * switches the mode (NoHostageApis), as in `CallDetectorTest`.
     */
    private fun phoneMode(mode: Int) {
        audio.mode = mode
    }

    private fun phoneCall(on: Boolean) {
        phoneMode(if (on) AudioManager.MODE_IN_CALL else AudioManager.MODE_NORMAL)
        app.awaitUntil("the call ${if (on) "pauses" else "resumes"} the ring") { (state() as? SessionState.Ring)?.session?.paused == on }
    }

    private fun slot(): Intent =
        Intent(
            shadowOf(
                assertNotNull(
                    shadowOf(app.app.getSystemService(AlarmManager::class.java)).scheduledAlarms.singleOrNull {
                        shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT
                    },
                    "one session slot",
                ).operation,
            ).savedIntent,
        )

    /** The system fires the armed session slot; its receiver starts the wake service, which the system starts. */
    private fun deliverSlot(running: ServiceController<WakeService>? = null): ServiceController<WakeService> {
        app.app.sendBroadcast(slot())
        idle()
        val start = assertNotNull(shadowOf(app.app).nextStartedService, "the slot starts the wake service")
        return running?.withIntent(start)?.startCommand(0, 9) ?: app.startService(start)
    }

    /** The process dies (no cleanup) and a new one boots; [reboot] also restarts the phone, which was off for [off]. */
    private fun kill(
        service: ServiceController<WakeService>,
        reboot: Boolean = false,
        off: Duration = Duration.ZERO,
    ) {
        service.destroy()
        shadowOf(app.app).clearStartedServices()
        app = newProcess()
        if (reboot) {
            clock.advanceBy(off)
            monotonic.reboot()
            monotonic.advanceBy(30.seconds)
            Settings.Global.putInt(app.app.contentResolver, Settings.Global.BOOT_COUNT, 2)
            app.app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.app.packageName))
            idle()
        }
    }

    /** "I'm up" if ringing, then every placeholder step answered: the session completes and is recorded. */
    private fun finish(): String {
        val sessionId = session().sessionId
        if (state() is SessionState.Ringing) app.dispatch(SessionEvent.ImUpTapped)
        val stepsLeft =
            session()
                .checkRun.plan.steps.size - session().checkRun.step
        repeat(stepsLeft) { app.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)) }
        app.awaitUntil("the session is recorded and Idle") { state() == SessionState.Idle }
        return sessionId
    }

    private fun assertRecorded(
        sessionId: String,
        outcome: SessionOutcome,
        snoozes: Int,
        directBoot: Boolean = false,
        merged: List<String> = emptyList(),
    ) {
        val read = runBlocking { app.koin.get<SessionHistoryRepository>().find(sessionId) }
        val row: SessionHistoryRow? = assertIs<Outcome.Success<SessionHistoryRow?>>(read).value
        assertNotNull(row, "the session_history row")
        assertEquals(outcome, row.outcome)
        assertEquals(snoozes, row.snoozeCount, "snooze_count")
        assertEquals(directBoot, row.directBoot, "direct_boot")
        assertEquals(merged, app.merges(sessionId).map { it.alarmId }, "session_merge rows")
        val stored = runBlocking { app.koin.get<ActiveSessionStore>().load() }
        assertEquals(Outcome.Success(StoredSession.Empty), stored, "runtime.db is empty after Recorded")
    }

    private fun alarmStreamAtSetVolume(): Boolean {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        return audio.getStreamVolume(AudioManager.STREAM_ALARM) == Math.round(max * alarmA.volumePercent / 100f)
    }

    // ----- PRD §6.4 rows -----

    @Test
    fun `1 payment in progress when the grace window ends - Loud at the set volume while paying, then cancelled and Loud rings on`() {
        ringA()
        imUpToGrace()
        app.dispatch(SessionEvent.SnoozeTapped, SessionEvent.PayConfirmed(PurchaseIntentId("intent-1")))
        assertEquals(PurchaseIntentId("intent-1"), session().paying)

        graceToLoud()

        assertEquals(PurchaseIntentId("intent-1"), session().paying, "the payment is still in flight")
        assertFalse(app.player.isMuted)
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
        assertTrue(alarmStreamAtSetVolume(), "full set volume")
        app.dispatch(SessionEvent.PurchaseCancelled)
        assertIs<SessionState.Loud>(state())
        assertNull(session().paying)
        assertTrue(app.lastMediaPlayer().isReallyPlaying, "Loud keeps ringing")
        assertRecorded(finish(), SessionOutcome.OnTime, snoozes = 0)
    }

    @Test
    fun `2 purchase granted during a check - Snoozed with progress discarded, the next ring, and I'm up starts a new Grace`() {
        store(alarmA)
        val twoSteps =
            aSessionConfig(alarmId = alarmA.id, scheduledAt = scheduledAt)
                .copy(checkPlan = CheckPlan(listOf(CheckStep.Placeholder, CheckStep.Placeholder)))
        app.dispatch(SessionEvent.AlarmFired("session-2", twoSteps, listOf(1L, 2L), beforeFirstUnlock = false))
        val service = app.startService(assertNotNull(shadowOf(app.app).nextStartedService))
        app.awaitRinging()
        imUpToGrace()
        app.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
        graceToLoud()
        assertEquals(1, session().checkRun.step, "Loud at step 2")

        grantSnooze(seeds = listOf(42L, 43L))

        assertNull(app.player.sound, "sound off")
        assertEquals(0, session().checkRun.step, "check progress discarded")
        assertEquals(listOf(42L, 43L), session().checkRun.seeds, "new seeds")
        pass(9.minutes)
        deliverSlot(service)
        app.awaitRinging()
        assertEquals(2, session().ringIndex)
        imUpToGrace()
        assertRecorded(finish(), SessionOutcome.Snoozed, snoozes = 1)
    }

    @Test
    fun `3 an overlapping alarm during Loud merges and changes nothing, during Snoozed re-rings now with no fee and no grace`() {
        val service = ringA()
        imUpToGrace()
        graceToLoud()
        val loud = state()

        service.withIntent(WakeService.alarmIntent(app.app, AlarmFired(alarmB.id, scheduledAt + 1.minutes))).startCommand(0, 2)
        app.awaitMerges(session().sessionId)
        assertEquals(loud, state(), "merged: nothing changes")

        grantSnooze()
        service.withIntent(WakeService.alarmIntent(app.app, AlarmFired(alarmB.id, scheduledAt + 2.minutes))).startCommand(0, 3)
        app.awaitRinging()
        assertEquals(2, session().ringIndex)
        assertTrue(session().noGraceThisRing)
        assertEquals(1, session().snoozesGranted, "no fee")
        assertEquals(emptyList(), billing.launched)
        app.dispatch(SessionEvent.ImUpTapped)
        assertIs<SessionState.Loud>(state(), "no grace window")
        assertRecorded(finish(), SessionOutcome.Snoozed, snoozes = 1, merged = listOf(alarmB.id, alarmB.id))
    }

    @Test
    fun `4 a call during Grace freezes the countdown and it resumes with the same seconds left`() {
        ringA()
        imUpToGrace()
        pass(5.seconds)

        phoneCall(on = true)
        pass(2.minutes)
        assertIs<SessionState.Grace>(state(), "frozen during the call")
        phoneCall(on = false)

        pass(14.seconds)
        assertIs<SessionState.Grace>(state(), "15 s were left")
        pass(1.seconds)
        app.awaitUntil("Loud after the same 20 s") { state() is SessionState.Loud }
        assertRecorded(finish(), SessionOutcome.OnTime, snoozes = 0)
    }

    @Test
    fun `5 after a reboot through a snooze end it rings at once, a restored ring gets a fresh timer and paying is cleared`() {
        val service = ringA()
        grantSnooze()
        kill(service, reboot = true, off = 12.minutes)

        val restored = deliverSlot()
        app.awaitRinging()
        assertEquals(2, session().ringIndex, "the snooze end passed while the phone was off: it rings at once")
        app.dispatch(SessionEvent.SnoozeTapped, SessionEvent.PayConfirmed(PurchaseIntentId("intent-2")))
        assertEquals(PurchaseIntentId("intent-2"), session().paying)
        val before = session().interactionDeadline

        // Process death during the payment: restored with paying cleared, billing never relaunched, a fresh timer.
        pass(10.minutes)
        kill(restored)
        deliverSlot()
        app.awaitRinging()
        assertNull(session().paying)
        assertTrue(session().interactionDeadline != before, "a fresh 30-minute timer")
        assertEquals(emptyList(), billing.launched, "billing is never relaunched")
        assertRecorded(finish(), SessionOutcome.Snoozed, snoozes = 1)
    }

    @Test
    fun `6 before the first unlock - default sound and a locked snooze, then the unlock opens snooze in place and the plan stays`() {
        lock.unlocked = false
        store(alarmA.copy(soundRef = "system:Morning|content://media/internal/audio/media/7"))
        app.ring(AlarmFired(alarmA.id, scheduledAt))
        app.awaitRinging()
        assertEquals(AlarmSound.Default, app.player.sound, "default sound before the unlock")
        val locked = app.koin.get<SnoozeAvailabilityPolicy>().availability(session())
        assertEquals(SnoozeAvailability.Unavailable(UnavailableReason.BeforeFirstUnlock), locked, "Unlock your phone to snooze")
        val plan = session().checkRun.plan

        lock.unlock()
        app.koin.get<UnlockSignals>().onUnlocked()
        app.awaitUntil("the unlock is applied") { !session().beforeFirstUnlock }

        assertIs<SnoozeAvailability.Available>(app.koin.get<SnoozeAvailabilityPolicy>().availability(session()), "snooze changes in place")
        assertTrue(session().directBootRing, "the substitutions stay for this ring")
        assertEquals(plan, session().checkRun.plan)
        assertEquals(AlarmSound.Default, app.player.sound, "the sound is not switched mid-ring")
        assertEquals(1, billing.initCalls)
        assertRecorded(finish(), SessionOutcome.OnTime, snoozes = 0, directBoot = true)
    }

    // ----- combined conflicts -----

    @Test
    fun `a call during payment freezes grace and the timeout and keeps paying until the billing result`() {
        ringA()
        imUpToGrace()
        app.dispatch(SessionEvent.SnoozeTapped, SessionEvent.PayConfirmed(PurchaseIntentId("intent-1")))
        val grace = session().graceEnd
        val timeout = session().interactionDeadline

        phoneCall(on = true)
        pass(3.minutes)
        assertIs<SessionState.Grace>(state())
        assertEquals(PurchaseIntentId("intent-1"), session().paying, "kept until the billing result")
        app.dispatch(SessionEvent.PurchaseCancelled)
        assertNull(session().paying)
        phoneCall(on = false)

        val callMillis = 3.minutes.inWholeMilliseconds
        assertEquals(grace!!.elapsedMillis + callMillis, session().graceEnd!!.elapsedMillis, "grace shifted by the call")
        assertEquals(timeout!!.elapsedMillis + callMillis, session().interactionDeadline!!.elapsedMillis, "timeout shifted by the call")
        assertRecorded(finish(), SessionOutcome.OnTime, snoozes = 0)
    }

    @Test
    fun `an overlap during a call is merged and the ring stays paused until the call ends`() {
        val service = ringA()
        phoneCall(on = true)

        service.withIntent(WakeService.alarmIntent(app.app, AlarmFired(alarmB.id, scheduledAt + 1.minutes))).startCommand(0, 2)
        app.awaitMerges(session().sessionId)

        assertTrue(session().paused, "still paused")
        assertTrue(app.player.isPaused)
        phoneCall(on = false)
        app.awaitUntil("the ring resumes") { !app.player.isPaused }
        assertRecorded(finish(), SessionOutcome.OnTime, snoozes = 0, merged = listOf(alarmB.id))
    }

    @Test
    fun `a reboot while paused for a call that is over by then rings again`() {
        val service = ringA()
        phoneCall(on = true)
        phoneMode(AudioManager.MODE_NORMAL)
        kill(service, reboot = true, off = 5.minutes)

        deliverSlot()
        app.awaitRinging()

        assertFalse(session().paused, "the restore cleared the pause and no call is on")
        assertTrue(app.lastMediaPlayer().isReallyPlaying)
        assertRecorded(finish(), SessionOutcome.OnTime, snoozes = 0)
    }

    @Test
    fun `a clock set 2 h forward during a snooze still re-rings on monotonic time`() {
        val service = ringA()
        grantSnooze()
        pass(3.minutes)

        clock.advanceBy(2.hours)
        app.app.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED).setPackage(app.app.packageName))
        idle()
        val slotAlarm =
            shadowOf(app.app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single {
                shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT
            }
        assertEquals(clock.now().toEpochMilliseconds() + 6.minutes.inWholeMilliseconds, slotAlarm.triggerAtTime, "new wall time + 6 min")
        app.dispatch(SessionEvent.SlotFired)
        assertIs<SessionState.Snoozed>(state(), "an early slot does not end the snooze")

        pass(6.minutes)
        deliverSlot(service)
        app.awaitRinging()
        assertEquals(2, session().ringIndex)
        assertRecorded(finish(), SessionOutcome.Snoozed, snoozes = 1)
    }

    // ----- across stories -----

    @Test
    fun `a real alarm during a test session ends the test as Test and rings as its own session (Story 1_18 rule kept by 2_9)`() {
        store(alarmA)
        testAlarms.pending = aSessionConfig(label = "test", scheduledAt = scheduledAt, testMode = true)
        val service = app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST))
        app.awaitRinging()
        val test = session().sessionId

        service.withIntent(WakeService.alarmIntent(app.app, AlarmFired(alarmA.id, scheduledAt))).startCommand(0, 2)
        app.awaitUntil("the real session rings") { (state() as? SessionState.Ringing)?.session?.config?.testMode == false }

        val testRow = assertIs<Outcome.Success<SessionHistoryRow?>>(runBlocking { app.koin.get<SessionHistoryRepository>().find(test) })
        assertEquals(SessionOutcome.Test, testRow.value?.outcome)
        assertEquals(emptyList(), app.merges(test), "not merged into the test")
        assertRecorded(finish(), SessionOutcome.OnTime, snoozes = 0)
    }

    @Test
    fun `an alarm during a ring before the first unlock merges into it and the ring keeps its Direct Boot sound`() {
        lock.unlocked = false
        val service = ringA()
        assertEquals(AlarmSound.Default, app.player.sound)

        service.withIntent(WakeService.alarmIntent(app.app, AlarmFired(alarmB.id, scheduledAt + 1.minutes))).startCommand(0, 2)
        app.awaitMerges(session().sessionId)

        assertTrue(session().beforeFirstUnlock)
        assertEquals(AlarmSound.Default, app.player.sound, "the merged alarm's sound is ignored")
        assertEquals(1, app.mediaPlayers.size, "one player")
        lock.unlock()
        app.koin.get<UnlockSignals>().onUnlocked()
        app.awaitUntil("the unlock is applied") { !session().beforeFirstUnlock }
        assertRecorded(finish(), SessionOutcome.OnTime, snoozes = 0, directBoot = true, merged = listOf(alarmB.id))
    }
}
