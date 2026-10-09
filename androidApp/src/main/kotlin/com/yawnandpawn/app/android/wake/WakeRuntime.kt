package com.yawnandpawn.app.android.wake

import android.app.Notification
import com.yawnandpawn.app.android.call.CallState
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.log.WakeStage
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.EntryEffect
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.SessionConfig
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.entryEffects
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The adapters the wake runtime drives. */
data class WakeOutputs(
    val player: AndroidAlarmPlayer,
    val vibrator: AlarmVibrator,
    val notifier: WakeNotifier,
    val volume: AlarmVolume,
    val scheduler: AlarmScheduler,
    val crashReporter: CrashReporter,
)

/** The emergency ring that is playing (store broken or a crash before the session started, NFR-2). */
data class EmergencyRing(
    val alarmAt: Instant,
    val volumePercent: Int,
)

/**
 * The production [EffectRunner] (AD-2 rule 4, AD-5), a Koin single: it carries out what the session wants with one
 * [AndroidAlarmPlayer], the [AlarmVibrator], the session slot ([AlarmScheduler.armSessionSlot]) and the ongoing
 * [WakeNotifier] notification, and it starts [WakeService] when a ringing state needs it and the service is not running
 * (for example a restore at app start). A start the platform refuses is logged by [WakeServiceStarter]; the armed
 * session slot (at most 60 s away) then brings the ring back through `SessionSlotReceiver`.
 *
 * - Entry effects are idempotent: the same effect again changes nothing (same sound and volume, same slot, same
 *   notification). A sound effect also stops the vibration when the state no longer wants it.
 * - Effects whose adapters arrive in later stories (checks UI, billing, motivation, purchase messages) are logged by
 *   type name only.
 * - It never awaits `SessionEngine.dispatch` from inside [run] or [apply] (the engine's Mutex is held there and is not
 *   reentrant). Any event the runtime produces is launched on `ApplicationScope`, outside the effect; in Story 1.14 it
 *   produces none, and [WakeService] dispatches `ProcessRestored` after a crash.
 * - Call contract (Story 2.7): the call adapter (`CallDetector`) sends `CallStarted` again after every `ProcessRestored`
 *   and at every new ring while a call is active; the runtime pauses on `SoundPaused` / `PauseSound`. Until the session
 *   knows, a ring that starts during a call ([calls]) opens silent and without vibration, and [onCallOver] lets it ring
 *   if the call ends first. The emergency ring has no session, so it follows the call directly ([onEmergencyCall]).
 *   Every ring start calls [onRing], so the call adapter follows it even when the wake service could not start. The
 *   effects, [onCallOver] and [onEmergencyCall] run under one lock: a call ending on the main thread never restarts
 *   a vibration that an effect on another thread just stopped.
 *
 * [session] reads the engine's current state (published before the effects run), for the ramp settings, the alarm time
 * and whether the state wants vibration. [now] reads the time ports. (One handler per session effect, hence many small
 * functions; splitting the class would split the one owner of the player.)
 */
@Suppress("TooManyFunctions")
class WakeRuntime(
    private val outputs: WakeOutputs,
    private val starter: WakeServiceStarter,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val now: () -> TimeSnapshot,
    private val session: () -> SessionState,
    private val timings: WakeTimings = WakeTimings.None,
    private val onInitBilling: () -> Unit = {},
    /** Whether a phone call is in progress (Story 2.7); a ring that starts during one opens silent. */
    private val calls: CallState = NoCalls,
    /** A ring (session or emergency) started or was re-applied: the call adapter follows it (Story 2.7). */
    private val onRing: () -> Unit = {},
    /**
     * A paid snooze committed: settle its payment (Story 4.10, `PurchaseLedger.settle`). It must only launch the work
     * (it runs inside the engine's Mutex) and never wait for Play.
     */
    private val onConsume: (PurchaseToken) -> Unit = {},
    /**
     * The session's billing effects (Story 4.11): `LaunchBilling` and `RequestKeyguardDismiss` go to
     * `PurchaseCoordinator`, which only launches its work on the app scope; nothing here waits for Play or the PIN.
     */
    private val onLaunchBilling: (SessionEffect.LaunchBilling) -> Unit = {},
    private val onKeyguardDismiss: () -> Unit = {},
) : EffectRunner {
    private val player = outputs.player
    private val vibrator = outputs.vibrator
    private val notifier = outputs.notifier

    /** Serializes the effects with the call adapter's calls ([onCallOver], [onEmergencyCall]). */
    private val effectLock = Any()

    @Volatile
    private var serviceRunning = false

    @Volatile
    private var startRequested = false

    /** The slot this process armed last; null when none (or after a cancel, or in a new process). */
    @Volatile
    private var armedSlot: Deadline? = null

    private val emergencyRing = MutableStateFlow<EmergencyRing?>(null)

    /**
     * A one-shot effect of the current step starts a new ring (`StartWakeRuntime`, or the slot armed for one), so its
     * sound may ramp; cleared by the step's sound entry effect. A step with none (a restore) plays without a ramp.
     */
    @Volatile
    private var newRing = false

    /** The ring that plays was restored (no ramp); kept until the next ring starts, so its request never changes. */
    @Volatile
    private var restoredRing = false

    /** The alarm the emergency ring plays for, carried by its backup slot (Story 2.1); null when unknown or none plays. */
    @Volatile
    private var emergencyAlarm: AlarmFired? = null
    private var emergencyLimit: Job? = null

    /** The emergency ring that is playing, or null. [WakeActivity] shows it; "I'm up" calls [stopEmergency]. */
    val emergency: StateFlow<EmergencyRing?> = emergencyRing.asStateFlow()

    private val emergencyPlaying = MutableStateFlow(false)

    /** [emergency] is not null; the session lock (Story 2.6) holds the app while it plays. */
    val emergencyRinging: StateFlow<Boolean> = emergencyPlaying.asStateFlow()

    /** [WakeService] is running in the foreground. */
    val isServiceRunning: Boolean
        get() = serviceRunning

    override suspend fun run(effect: SessionEffect) = guarded(effect) { synchronized(effectLock) { runEffect(effect) } }

    override suspend fun apply(effect: EntryEffect) = guarded(effect) { synchronized(effectLock) { applyEffect(effect) } }

    private fun runEffect(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.StartWakeRuntime -> startRuntime()

            is SessionEffect.ArmSlot -> armSlotForRing(effect.at)

            SessionEffect.CancelSlot -> cancelSlot()

            SessionEffect.InitBilling -> onInitBilling()

            is SessionEffect.Consume -> onConsume(effect.token)

            is SessionEffect.LaunchBilling -> onLaunchBilling(effect)

            is SessionEffect.RequestKeyguardDismiss -> onKeyguardDismiss()

            is SessionEffect.ShowPurchaseOutcome,
            SessionEffect.ShowPaymentPending,
            is SessionEffect.ShowReuseSheet,
            SessionEffect.HideReuseSheet,
            -> backFromPayment(effect)

            is SessionEffect.ClearRuntimeSession -> endSession()

            is SessionEffect.LogIgnored -> logger.log(LogEvent.SessionEventIgnored(effect.eventType, effect.sessionId))

            else -> if (!runSound(effect)) logger.log(LogEvent.SessionEffectLogged(typeName(effect), entry = false))
        }
    }

    /**
     * A payment outcome hands the screen back to the ring (Story 4.11, the Story 2.8 hand-off): the alarm stream is set
     * back to the ring's volume once, since the volume keys may have lowered it under Play's sheet. The message itself is
     * shown by the wake UI (Stories 4.13/4.14); until then it is logged by type name.
     */
    private fun backFromPayment(effect: SessionEffect) {
        reassertRingVolume()
        logger.log(LogEvent.SessionEffectLogged(typeName(effect), entry = false))
    }

    private fun applyEffect(effect: EntryEffect) {
        when (effect) {
            is EntryEffect.SoundAt -> playSound(effect)

            EntryEffect.SoundPaused -> ring { player.pause() }

            EntryEffect.Muted -> ring { player.mute() }

            EntryEffect.SoundOff -> silence()

            // A ring that started during a call vibrates only once the session knows about the call (Story 2.7).
            EntryEffect.Vibrating -> if (!calls.inCall()) vibrator.start()

            EntryEffect.HeartbeatSlotArmed -> keepHeartbeat()

            is EntryEffect.SlotArmedAt -> if (armedSlot != effect.at) armSlot(effect.at)

            EntryEffect.WakeUiShown -> showWakeUi()

            // The engine writes history itself and never hands this to a runner (SessionPorts).
            is EntryEffect.HistoryWriteRequested -> logger.log(LogEvent.SessionEffectLogged(typeName(effect), entry = true))
        }
    }

    /** [WakeService] started (`onCreate`). */
    fun onServiceStarted() {
        serviceRunning = true
        startRequested = false
    }

    /** [WakeService] stopped (`onDestroy`). */
    fun onServiceStopped() {
        serviceRunning = false
        startRequested = false
    }

    /**
     * The foreground notification [WakeService] posts with `startForeground`: the ringing one for the alarm scheduled at
     * [alarmAt] (without its full-screen intent unless [fullScreen]), or the quiet one when there is no alarm to show
     * yet. [foregroundPosted] once a full-screen one is posted.
     */
    fun foregroundNotification(
        alarmAt: Instant?,
        fullScreen: Boolean = true,
    ): Notification = alarmAt?.let { notifier.build(it, fullScreen) } ?: notifier.buildQuiet()

    /** `startForeground` posted [foregroundNotification] for [alarmAt] (null for the quiet one). */
    fun foregroundPosted(alarmAt: Instant?) {
        alarmAt?.let { notifier.shownByService(it) }
    }

    /**
     * The user swiped the ringing notification away (its delete intent, Story 2.5): the next `WakeUiShown` posts it
     * again, even when the start that re-posts it cannot enter the foreground.
     */
    fun notificationSwiped() = notifier.forget()

    /**
     * The wake screen became visible or was left (Epic 3 device check, bug 2): while it is visible the notification is
     * the quiet on-screen one, so no heads-up covers its countdown; left while [ringing], it heads up again as the way
     * back ([WakeNotifier.wakeScreenShown]).
     */
    fun wakeScreenShown(
        visible: Boolean,
        ringing: Boolean,
        changingConfigurations: Boolean = false,
    ) = notifier.wakeScreenShown(visible, ringing, changingConfigurations)

    /** The alarm time the posted ringing notification shows, or null when none is posted. */
    fun shownAlarmAt(): Instant? = notifier.shownFor

    /**
     * The session slot fired, so nothing is armed any more: the next slot entry effect arms it again (for example the
     * snooze end, when the slot fired early because the wall clock jumped forward).
     */
    fun onSlotFired() {
        armedSlot = null
    }

    /** The alarm time the wake screen and the notification show: the emergency ring's, else the session's; null when Idle. */
    fun alarmAt(): Instant? = emergency.value?.alarmAt ?: configOrNull()?.scheduledAt

    /**
     * The session ended (`ClearRuntimeSession`, or [WakeService] saw Idle, Completed or Missed): no sound, no vibration,
     * the user's alarm volume back, no notification, no slot. Idempotent. A slot that carries another alarm (a refused
     * start, [AlarmScheduler.sessionSlotAlarm]) is armed again one heartbeat from now with it (Story 2.1 review), so
     * that alarm still rings.
     */
    fun endSession() {
        player.stop(restoreVolume = true)
        vibrator.stop()
        notifier.cancel()
        val now = now()
        val foreign = outputs.scheduler.sessionSlotAlarm()?.takeIf { it != emergencyAlarm && it.isFresh(now) }
        // Always (Story 2.1): a slot armed by an earlier process, or outside the engine, is not in armedSlot.
        cancelSlot()
        foreign?.let { armSlot(Deadline.after(now, SessionReducer.HEARTBEAT), it) }
        timings.sessionEnded()
    }

    private fun AlarmFired.isFresh(now: TimeSnapshot): Boolean =
        now.wallMillis - scheduledAt.toEpochMilliseconds() < SessionReducer.NO_INTERACTION_TIMEOUT.inWholeMilliseconds

    /** The player is held paused (a ring that opened during a call, or the session's call pause). */
    val isRingHeld: Boolean
        get() = player.isPaused

    /**
     * No call is in progress and the session is not paused for one (Story 2.7): a ring held silent because it started
     * during a call that ended before the session paused rings now (focus, full gain) with its vibration. Nothing
     * otherwise: the session state is read again under the effects' lock, so a state that just moved on (grace, an
     * ended session) is never given its sound or vibration back.
     */
    fun onCallOver() =
        synchronized(effectLock) {
            val ring = (session() as? SessionState.Ring)?.takeUnless { it.session.paused } ?: return@synchronized
            if (emergency.value != null || !player.isPaused) return@synchronized
            player.resume()
            if (EntryEffect.Vibrating in entryEffects(ring)) vibrator.start()
        }

    /**
     * The emergency ring follows the call itself (Story 2.7 review; it has no session to pause): silent and still during
     * a call ([inCall]), ringing and vibrating again after it. Nothing when no emergency ring plays.
     */
    fun onEmergencyCall(inCall: Boolean) =
        synchronized(effectLock) {
            if (emergency.value == null) return@synchronized
            if (inCall) {
                player.pause()
                vibrator.stop()
            } else if (player.isPaused) {
                player.resume()
                vibrator.start()
            }
        }

    /** At app start with no session: a volume a crashed session left saved is put back (AD-5), unless a ring started. */
    fun restoreVolumeIfIdle() {
        if (session() == SessionState.Idle && emergency.value == null) player.restoreVolumeIfSilent()
    }

    /**
     * Sets the alarm stream back to the ring's volume once, while the session rings loud (Ringing or Loud, not paused by
     * a call, no emergency ring); otherwise nothing (Story 2.8).
     *
     * Spike S1: while Google Play's purchase sheet is on top, its activity gets the volume keys and they change the alarm
     * stream; the wake screen cannot consume them then, and FR-SES-6 forbids re-applying the volume continuously. The
     * gap is accepted, and the purchase orchestration calls this once on every payment outcome that hands the screen
     * back to the ring ("alarm at full volume" again; Story 4.11: outcome, pending and reuse-sheet effects).
     */
    fun reassertRingVolume() {
        val state = session()
        val loud = state is SessionState.Ringing || state is SessionState.Loud
        if (loud && emergency.value == null && !(state as SessionState.Ring).session.paused) player.reassertVolume()
    }

    /**
     * The session could not start (or the wake flow failed before it did): the default sound at [volumePercent] with
     * vibration, the notification and the wake screen for the alarm at [alarmAt], until [stopEmergency] ("I'm up") or
     * after [EMERGENCY_LIMIT]. Logged with [cause]. Nothing when one already plays.
     *
     * Backstop (Story 2.1): with no session ringing or snoozed, the session slot is armed one heartbeat away carrying
     * [alarm] (the alarm it rings for, when known), and every slot fire while it plays arms it again
     * ([keepEmergencySlot]). If the process dies, the slot's fire handles [alarm] again in a new process: a real session
     * when storage works by then, else the emergency ring again (with a fresh limit). A slot carrying no alarm brings it
     * back only while the stored session cannot be read.
     */
    fun startEmergency(
        alarmAt: Instant,
        volumePercent: Int,
        cause: String,
        alarm: AlarmFired? = null,
    ) {
        synchronized(emergencyRing) {
            if (emergencyRing.value != null) return
            logger.log(LogEvent.EmergencyRingStarted(cause))
            emergencyRing.value = EmergencyRing(alarmAt, volumePercent)
            emergencyAlarm = alarm
            emergencyPlaying.value = true
            emergencyLimit =
                scope.launch {
                    delay(EMERGENCY_LIMIT)
                    stopEmergency(STOPPED_BY_LIMIT)
                }
        }
        // During a call it opens silent and still, like a session ring; the call adapter rings it when the call ends.
        val inCall = calls.inCall()
        player.playDefault(volumePercent, paused = inCall)
        if (!inCall) vibrator.start()
        notifier.show(alarmAt)
        ensureService()
        keepEmergencySlot()
        onRing()
    }

    /**
     * Arms the emergency ring's backup slot one heartbeat from now, carrying its alarm (Story 2.1). Nothing when no
     * emergency ring plays, or a session rings or is snoozed (its own slot stays).
     */
    fun keepEmergencySlot() {
        val state = session()
        if (emergency.value == null || state is SessionState.Ring || state is SessionState.Snoozed) return
        armSlot(Deadline.after(now(), SessionReducer.HEARTBEAT), emergencyAlarm)
    }

    /** Stops the emergency ring ([reason] is logged); a session that took over keeps ringing. */
    fun stopEmergency(reason: String = STOPPED_BY_IM_UP) {
        synchronized(emergencyRing) {
            if (emergencyRing.value == null) return
            emergencyLimit?.cancel()
            emergencyLimit = null
            when (session()) {
                is SessionState.Ring -> Unit

                // A snooze goes on: its slot stays armed and the user's volume stays saved until the session ends.
                is SessionState.Snoozed -> silence()

                else -> endSession()
            }
            emergencyRing.value = null
            emergencyAlarm = null
            emergencyPlaying.value = false
        }
        logger.log(LogEvent.EmergencyRingStopped(reason))
    }

    /**
     * After an uncaught exception in the wake flow (AD-12, NFR-2), once it is reported: a ringing session switches to the
     * default sound; otherwise (no session, snoozed or ended) the alarm being handled ([pending]) gets the emergency
     * ring. [WakeService] then dispatches `ProcessRestored`.
     */
    fun ringDefaultAfterCrash(pending: AlarmFired?) {
        when {
            session() is SessionState.Ring -> {
                player.switchToDefault()
            }

            pending != null -> {
                startEmergency(pending.scheduledAt, Alarm.DEFAULT_VOLUME_PERCENT, CRASHED, pending)
            }
        }
    }

    /**
     * Runs one effect of this runtime. The engine would only log what it throws, so it is reported here, and a ringing
     * session is never left silent: its sound switches to the default (or the default starts at the set volume).
     * Cancellation still propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun guarded(
        effect: Any,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            outputs.crashReporter.report(e)
            logger.log(LogEvent.OperationFailed("wake effect ${typeName(effect)}", e::class.simpleName.orEmpty()))
            ringDefaultAfterEffectFailure()
        }
    }

    private fun ringDefaultAfterEffectFailure() {
        val state = session() as? SessionState.Ring ?: return
        if (state is SessionState.Grace || state.session.paused) return
        player.switchToDefault()
        if (player.sound == null) player.playDefault(state.session.config.volumePercent)
    }

    /** One-shot sound and vibration effects; false when [effect] is none of them. */
    private fun runSound(effect: SessionEffect): Boolean {
        when (effect) {
            SessionEffect.Mute -> player.mute()
            is SessionEffect.UnmuteToVolume -> player.unmuteTo(effect.volumePercent)
            SessionEffect.StrongHaptic -> vibrator.strongHaptic()
            SessionEffect.StopSound -> silence()
            SessionEffect.PauseSound -> player.pause().also { vibrator.stop() }
            SessionEffect.ResumeSound -> player.resume()
            else -> return false
        }
        return true
    }

    /** A ringing entry effect: the service runs, a real session replaces an emergency ring, vibration only if wanted. */
    private inline fun ring(sound: () -> Unit) {
        ensureService()
        if (emergency.value != null) clearEmergencyForSession()
        sound()
        newRing = false
        if (EntryEffect.Vibrating !in entryEffects(session())) vibrator.stop()
        onRing()
    }

    private fun playSound(effect: EntryEffect.SoundAt) {
        // A sound starts when nothing plays, or when it replaces the emergency ring (review); read before ring() clears it.
        val starts = player.sound == null || emergency.value != null
        ring { playFor(effect, starts) }
        timings.stage(WakeStage.SoundRequested)
    }

    /**
     * Plays the session's sound. A restored ring (Story 2.1, UX-DR78) plays at the set volume with no ramp: nothing plays
     * yet and no one-shot effect of this step started a new ring (`ProcessRestored` runs entry effects only). A sound
     * already open keeps the ramp setting it started with, so the same request stays the same and never restarts the ring.
     * The emergency default sound is not the session's: a session sound that replaces it [starts] too.
     */
    private fun playFor(
        effect: EntryEffect.SoundAt,
        starts: Boolean,
    ) {
        val ring = ringConfig()
        // Decided only when a sound starts: a heartbeat (ArmSlot on SlotFired) while it plays must not change the request.
        if (starts) restoredRing = !newRing
        // A ring that starts during a call opens silent and still until the session pauses or the call ends (Story 2.7).
        val inCall = calls.inCall()
        player.play(effect.soundRef, effect.volumePercent, ring.gradual && !restoredRing, ring.rampStart, paused = inCall)
        if (inCall) vibrator.stop()
    }

    private fun clearEmergencyForSession() {
        synchronized(emergencyRing) {
            emergencyLimit?.cancel()
            emergencyLimit = null
            emergencyRing.value = null
            emergencyAlarm = null
            // The backup slot carried the emergency's alarm: the session's heartbeat arms its own slot instead.
            armedSlot = null
            emergencyPlaying.value = false
        }
        logger.log(LogEvent.EmergencyRingStopped("a session took over"))
    }

    private fun silence() {
        player.stop(restoreVolume = false)
        vibrator.stop()
        newRing = false
    }

    private fun showWakeUi() {
        ensureService()
        alarmAt()?.let { notifier.show(it) }
    }

    /** The first effect of a new session, so its state is committed (timed for the ring start). */
    private fun startRuntime() {
        timings.stage(WakeStage.SessionCommitted)
        newRing = true
        ensureService()
    }

    /** The one-shot `ArmSlot`: the heartbeat of a ring, or the slot of a ring that starts now (it may ramp). */
    private fun armSlotForRing(at: Deadline) {
        newRing = true
        armSlot(at)
    }

    private fun ensureService() {
        if (serviceRunning || startRequested) return
        startRequested = starter.startRestore()
    }

    /**
     * The heartbeat slot (Story 2.1): armed one heartbeat from now when this process has none armed, or when the one it
     * armed is already due (it fired, but this process never handled that fire), so a ringing session always has one.
     */
    private fun keepHeartbeat() {
        val now = now()
        if (armedSlot?.isDue(now) != false) armSlot(Deadline.after(now, SessionReducer.HEARTBEAT))
    }

    /**
     * Arms the slot at [at], carrying [alarm] when given; a failure (logged) records nothing, so the next slot entry
     * effect tries again.
     */
    private fun armSlot(
        at: Deadline,
        alarm: AlarmFired? = null,
    ) {
        val armed = outputs.scheduler.armSessionSlot(at, alarm)
        armedSlot = if (armed is Outcome.Success) at else null
        if (armed is Outcome.Failure) logger.log(LogEvent.OperationFailed.of("arm session slot", armed.error))
    }

    private fun cancelSlot() {
        val cancelled = outputs.scheduler.cancelSessionSlot()
        if (cancelled is Outcome.Failure) logger.log(LogEvent.OperationFailed.of("cancel session slot", cancelled.error))
        armedSlot = null
    }

    private fun configOrNull(): SessionConfig? = (session() as? SessionState.Active)?.session?.config

    private fun ringConfig(): RingConfig =
        configOrNull()?.let { RingConfig(it.gradualVolume, it.rampStartPercent) } ?: RingConfig(gradual = false, rampStart = FULL_PERCENT)

    private fun typeName(effect: Any): String = effect::class.simpleName ?: "Effect"

    private data class RingConfig(
        val gradual: Boolean,
        val rampStart: Int,
    )

    companion object {
        /** The emergency ring stops by itself after this long, like a forgotten alarm (FR-ALM-9). */
        val EMERGENCY_LIMIT: Duration = 30.minutes

        const val STOPPED_BY_IM_UP = "I'm up"
        const val STOPPED_BY_LIMIT = "30-minute limit"
        private const val CRASHED = "the wake flow crashed before the session started"
        private const val FULL_PERCENT = 100
    }
}

/** No call detection (tests, and before the call adapter is wired): never in a call. */
private object NoCalls : CallState {
    override fun inCall(): Boolean = false
}
