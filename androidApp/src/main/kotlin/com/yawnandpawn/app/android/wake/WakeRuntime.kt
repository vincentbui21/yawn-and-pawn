package com.yawnandpawn.app.android.wake

import android.app.Notification
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
 * session slot (at most 60 s away) then brings the ring back through the alarm receiver.
 *
 * - Entry effects are idempotent: the same effect again changes nothing (same sound and volume, same slot, same
 *   notification). A sound effect also stops the vibration when the state no longer wants it.
 * - Effects whose adapters arrive in later stories (checks UI, billing, motivation, purchase messages) are logged by
 *   type name only.
 * - It never awaits `SessionEngine.dispatch` from inside [run] or [apply] (the engine's Mutex is held there and is not
 *   reentrant). Any event the runtime produces is launched on `ApplicationScope`, outside the effect; in Story 1.14 it
 *   produces none, and [WakeService] dispatches `ProcessRestored` after a crash.
 * - Call contract (Story 2.7): the runtime never detects calls. The call adapter sends `CallStarted` again after every
 *   `ProcessRestored` and at every new ring while a call is active; the runtime pauses on `SoundPaused` / `PauseSound`.
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
) : EffectRunner {
    private val player = outputs.player
    private val vibrator = outputs.vibrator
    private val notifier = outputs.notifier

    @Volatile
    private var serviceRunning = false

    @Volatile
    private var startRequested = false

    /** The slot this process armed last; null when none (or after a cancel, or in a new process). */
    @Volatile
    private var armedSlot: Deadline? = null

    private val emergencyRing = MutableStateFlow<EmergencyRing?>(null)
    private var emergencyLimit: Job? = null

    /** The emergency ring that is playing, or null. [WakeActivity] shows it; "I'm up" calls [stopEmergency]. */
    val emergency: StateFlow<EmergencyRing?> = emergencyRing.asStateFlow()

    /** [WakeService] is running in the foreground. */
    val isServiceRunning: Boolean
        get() = serviceRunning

    override suspend fun run(effect: SessionEffect) = guarded(effect) { runEffect(effect) }

    override suspend fun apply(effect: EntryEffect) = guarded(effect) { applyEffect(effect) }

    private fun runEffect(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.StartWakeRuntime -> startRuntime()
            is SessionEffect.ArmSlot -> armSlot(effect.at)
            SessionEffect.CancelSlot -> cancelSlot()
            is SessionEffect.ClearRuntimeSession -> endSession()
            is SessionEffect.LogIgnored -> logger.log(LogEvent.SessionEventIgnored(effect.eventType, effect.sessionId))
            else -> if (!runSound(effect)) logger.log(LogEvent.SessionEffectLogged(typeName(effect), entry = false))
        }
    }

    private fun applyEffect(effect: EntryEffect) {
        when (effect) {
            is EntryEffect.SoundAt -> ring { playFor(effect) }.also { timings.stage(WakeStage.SoundRequested) }

            EntryEffect.SoundPaused -> ring { player.pause() }

            EntryEffect.Muted -> ring { player.mute() }

            EntryEffect.SoundOff -> silence()

            EntryEffect.Vibrating -> vibrator.start()

            EntryEffect.HeartbeatSlotArmed -> if (armedSlot == null) armSlot(Deadline.after(now(), SessionReducer.HEARTBEAT))

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
     * [alarmAt], or the quiet one when there is no alarm to show yet. [foregroundPosted] once it is posted.
     */
    fun foregroundNotification(alarmAt: Instant?): Notification = alarmAt?.let { notifier.build(it) } ?: notifier.buildQuiet()

    /** `startForeground` posted [foregroundNotification] for [alarmAt] (null for the quiet one). */
    fun foregroundPosted(alarmAt: Instant?) {
        alarmAt?.let { notifier.shownByService(it) }
    }

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
     * the user's alarm volume back, no notification, no slot. Idempotent.
     */
    fun endSession() {
        player.stop(restoreVolume = true)
        vibrator.stop()
        notifier.cancel()
        if (armedSlot != null) cancelSlot()
    }

    /** At app start with no session: a volume a crashed session left saved is put back (AD-5), unless a ring started. */
    fun restoreVolumeIfIdle() {
        if (session() == SessionState.Idle && emergency.value == null) player.restoreVolumeIfSilent()
    }

    /**
     * The session could not start (or the wake flow failed before it did): the default sound at [volumePercent] with
     * vibration, the notification and the wake screen for the alarm at [alarmAt], until [stopEmergency] ("I'm up") or
     * after [EMERGENCY_LIMIT]. Logged with [cause]. Nothing when one already plays.
     */
    fun startEmergency(
        alarmAt: Instant,
        volumePercent: Int,
        cause: String,
    ) {
        synchronized(emergencyRing) {
            if (emergencyRing.value != null) return
            logger.log(LogEvent.EmergencyRingStarted(cause))
            emergencyRing.value = EmergencyRing(alarmAt, volumePercent)
            emergencyLimit =
                scope.launch {
                    delay(EMERGENCY_LIMIT)
                    stopEmergency(STOPPED_BY_LIMIT)
                }
        }
        player.playDefault(volumePercent)
        vibrator.start()
        notifier.show(alarmAt)
        ensureService()
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
                startEmergency(pending.scheduledAt, Alarm.DEFAULT_VOLUME_PERCENT, CRASHED)
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
            is SessionEffect.UnmuteToVolume -> player.unmute()
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
        if (EntryEffect.Vibrating !in entryEffects(session())) vibrator.stop()
    }

    private fun playFor(effect: EntryEffect.SoundAt) {
        val ring = ringConfig()
        player.play(effect.soundRef, effect.volumePercent, ring.gradual, ring.rampStart)
    }

    private fun clearEmergencyForSession() {
        synchronized(emergencyRing) {
            emergencyLimit?.cancel()
            emergencyLimit = null
            emergencyRing.value = null
        }
        logger.log(LogEvent.EmergencyRingStopped("a session took over"))
    }

    private fun silence() {
        player.stop(restoreVolume = false)
        vibrator.stop()
    }

    private fun showWakeUi() {
        ensureService()
        alarmAt()?.let { notifier.show(it) }
    }

    /** The first effect of a new session, so its state is committed (timed for the ring start). */
    private fun startRuntime() {
        timings.stage(WakeStage.SessionCommitted)
        ensureService()
    }

    private fun ensureService() {
        if (serviceRunning || startRequested) return
        startRequested = starter.startRestore()
    }

    /** Arms the slot at [at]; a failure (logged) records nothing, so the next slot entry effect tries again. */
    private fun armSlot(at: Deadline) {
        val armed = outputs.scheduler.armSessionSlot(at)
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
