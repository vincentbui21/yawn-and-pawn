package com.yawnandpawn.app.android.wake

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.yawnandpawn.app.android.AlarmFiredReceiver
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.alarmFiredOrNull
import com.yawnandpawn.app.android.putAlarmFired
import com.yawnandpawn.app.android.putRetrySince
import com.yawnandpawn.app.android.retrySinceOrNull
import com.yawnandpawn.app.android.screen.forwardsToWakeScreen
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.log.FireKind
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.log.WakeStage
import com.yawnandpawn.app.core.log.diagnostic
import com.yawnandpawn.app.core.session.CheckAnswer
import com.yawnandpawn.app.core.session.CheckPlan
import com.yawnandpawn.app.core.session.ConfigResolver
import com.yawnandpawn.app.core.session.DirectBootSubstitution
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SeedSource
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionSlotRearm
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.TestAlarmStore
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.session.nextTickIn
import com.yawnandpawn.app.core.time.BootCounter
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The wake runtime's foreground service (AD-5): type `mediaPlayback` [ASSUMPTION pending Spike S2], directly booted.
 * The alarm receiver starts it for a ringing alarm and for the session slot; [WakeRuntime] starts it when a restored
 * session needs it.
 *
 * - `onStartCommand` calls `startForeground` with the ringing notification first, before any suspend work, then tells
 *   [WakeServiceStarts] (the alarm receiver keeps its broadcast open until then). Alarm and test starts log their
 *   ring-start timing ([WakeTimings]).
 * - **Alarm:** while a session rings or is snoozed it dispatches `OverlapAlarmFired` (unless the fire is not merged, see
 *   [mergeIgnoredBecause]); otherwise it reads the alarm and
 *   dispatches `AlarmFired` with a new session id, the config resolved now (`GlobalSettings` defaults until Epic 5), seeds
 *   from the [SeedSource] and whether the phone is still locked since boot. A deleted alarm rings nothing.
 * - **Slot (Story 2.1):** the kill-recovery path; see [onSlot]. **Restore:** it loads the stored session
 *   (`SessionEngine.restore`).
 * - **Test:** it takes the pending test config and dispatches `TestAlarmFired` (Story 1.18).
 * - A dispatch that may start a session (`AlarmFired`, `TestAlarmFired`) runs in [SessionLockGuard.startingSession], so
 *   it never lands in the middle of a guarded alarm write (Story 2.6).
 * - **Repost** (the ringing notification's delete intent, Story 2.5): entering the foreground posts the notification
 *   again, without its full-screen intent unless the alarm rings (a snooze never opens the wake screen). An Idle engine
 *   (a new process) loads the stored session first, as a restore does; otherwise nothing is dispatched.
 * - **Order (Story 2.1):** `startForeground` first, then `SessionEngine.restore()` (with a stored session that is
 *   `ProcessRestored`), and only then any event from the broadcast, so an alarm that fires into a killed process with a
 *   stored session becomes `OverlapAlarmFired`, never a second session. A refused `startForeground` re-arms the session
 *   slot one heartbeat later ([SessionSlotRearm.afterRefusedStart]); a test is never retried. Swiping the app from
 *   Recents stops nothing ([onTaskRemoved]).
 * - While a session is active it calls `SessionEngine.tick` when the next deadline (grace end or the 30-minute
 *   interaction timeout, FR-ALM-9) is due on the monotonic clock (Story 1.16). A forgotten alarm becomes Missed there.
 * - When the state is Idle, Completed or Missed and no emergency ring plays, it stops the sound and vibration, restores
 *   the alarm volume, removes the notification and stops itself: no service is left running (NFR-8).
 * - **Never silent:** if `AlarmFired` cannot be committed (or the alarm cannot be read), or a slot fire, merge or
 *   restore fails while the session is not ringing, [WakeRuntime.startEmergency] rings the default sound. An uncaught
 *   exception in this flow reaches the scope's handler: it is reported to the [CrashReporter], the player switches to
 *   the default sound and `ProcessRestored` is dispatched (AD-12, NFR-2); after three, it only keeps the sound.
 * - Not sticky; a start without an alarm shows the quiet notification until a ringing state posts the ringing one.
 *
 * One small function per step of the flow above, hence the many functions.
 */
@Suppress("TooManyFunctions")
class WakeService :
    Service(),
    KoinComponent {
    private val engine: SessionEngine by inject()
    private val runtime: WakeRuntime by inject()
    private val repository: AlarmRepository by inject()
    private val ids: IdGenerator by inject()
    private val seeds: SeedSource by inject()
    private val testAlarms: TestAlarmStore by inject()
    private val crashReporter: CrashReporter by inject()
    private val clock: Clock by inject()
    private val monotonicClock: MonotonicClock by inject()
    private val bootCounter: BootCounter by inject()
    private val appScope: ApplicationScope by inject()
    private val logger: Logger by inject()
    private val starts: WakeServiceStarts by inject()
    private val timings: WakeTimings by inject()
    private val rearm: SessionSlotRearm by inject()
    private val userLock: UserLockState by inject()
    private val sessionLock: SessionLockGuard by inject()
    private val unlockSignals: UnlockSignals by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> onCrash(e) })
    private val commands = Mutex()
    private var watching: Job? = null
    private var ticking: Job? = null

    /** The unlock watch of a session before the first unlock (Story 2.4); null or done otherwise. */
    private var unlockWatch: Job? = null
    private var stopping = false

    /** The latest start, so a stop never ends a start that arrived after it (`stopSelfResult`). */
    private var lastStartId = 0

    /** Uncaught exceptions so far; after [MAX_CRASH_RESTARTS] the flow is not restarted (no report flood). */
    private var crashes = 0

    /** The alarm whose fire is being handled, for the emergency ring if the flow crashes before the session starts. */
    @Volatile
    private var pending: AlarmFired? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        timings.stage(WakeStage.ServiceCreated)
        runtime.onServiceStarted()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastStartId = startId
        val fired = intent?.toAlarmFired()
        // Only alarm and test starts are timed, never the heartbeat slot or a restore.
        val timed = fired != null || intent?.action == ACTION_TEST
        fired?.let { timings.fired(it.scheduledAt) }
        if (timed) timings.stage(WakeStage.StartCommand)
        // An alarm start shows its ringing notification at once; a slot or restore start shows the ringing one only when
        // it is already up, else the quiet one (no full-screen intent) until a ringing state posts its own.
        val repost = intent?.action == ACTION_REPOST
        val inForeground = enterForegroundFor(fired, repost)
        if (timed && inForeground) timings.stage(WakeStage.InForeground)
        // The alarm receiver that asked for this start may finish its broadcast now (WakeServiceStarts).
        intent?.takeIf { it.hasExtra(EXTRA_START_TOKEN) }?.let { starts.onStartCommandReached(it.getLongExtra(EXTRA_START_TOKEN, 0)) }
        if (!inForeground) {
            retryLater(intent, fired)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        stopping = false
        // Main.immediate: the command takes the lock before onStartCommand returns, so a shutdown cannot slip in.
        scope.launch {
            commands.withLock {
                when {
                    fired != null -> onAlarm(fired)

                    intent?.action == ACTION_SLOT -> onSlot(intent.alarmFiredOrNull())

                    intent?.action == ACTION_TEST -> onTest()

                    // The ringing notification was swiped away: entering the foreground above posted it again.
                    repost -> onRepost()

                    else -> onRestore()
                }
            }
            watch()
        }
        // Not sticky: a restart without an intent would post the alarm notification for nothing; the slot revives a ring.
        return START_NOT_STICKY
    }

    /**
     * The platform refused `startForeground` (Story 2.1): the session slot tries again one heartbeat later, carrying the
     * alarm of an alarm or slot start ([SessionSlotRearm.afterRefusedStart]), and since when a slot's starts are refused.
     * A test is never retried.
     */
    private fun retryLater(
        intent: Intent?,
        fired: AlarmFired?,
    ) {
        if (intent?.action == ACTION_TEST) return
        val slot = intent?.takeIf { it.action == ACTION_SLOT }
        val alarm = fired ?: slot?.alarmFiredOrNull()
        appScope.launch { rearm.afterRefusedStart(alarm, slot?.retrySinceOrNull()) }
    }

    /**
     * The user swiped the app from Recents (Story 2.1): the session, the player and the foreground notification keep
     * running. The manifest never sets `stopWithTask`, so the service is not stopped with the task.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        runtime.onServiceStopped()
        super.onDestroy()
    }

    /**
     * Enters the foreground for an alarm start ([fired]) with its ringing notification, else with the one already shown
     * (or the quiet one). A [repost] start (the notification was swiped, Story 2.5) posts it again; during a snooze it
     * has no full-screen intent, which would open the wake screen with nothing ringing.
     */
    private fun enterForegroundFor(
        fired: AlarmFired?,
        repost: Boolean,
    ): Boolean {
        if (!repost) return enterForeground(fired?.scheduledAt ?: runtime.shownAlarmAt())
        val shown = runtime.shownAlarmAt()
        // The next WakeUiShown posts it again, even if this start cannot enter the foreground.
        runtime.notificationSwiped()
        return enterForeground(shown, fullScreen = forwardsToWakeScreen(engine.state.value, runtime.emergency.value))
    }

    // startForeground throws ForegroundServiceStartNotAllowedException (an IllegalStateException) or SecurityException
    // when the platform refuses; the armed slot brings the ring back.
    private fun enterForeground(
        alarmAt: Instant?,
        fullScreen: Boolean = true,
    ): Boolean =
        try {
            val notification = runtime.foregroundNotification(alarmAt, fullScreen)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(WakeNotifier.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(WakeNotifier.NOTIFICATION_ID, notification)
            }
            // One without its full-screen intent is not the ringing one: the next WakeUiShown replaces it.
            if (fullScreen) runtime.foregroundPosted(alarmAt)
            true
        } catch (e: IllegalStateException) {
            refused(e)
        } catch (e: SecurityException) {
            refused(e)
        }

    private fun refused(e: RuntimeException): Boolean {
        val cause = "${e::class.simpleName}; the session slot brings the ring back"
        logger.log(LogEvent.OperationFailed("start wake service in the foreground", cause))
        return false
    }

    private suspend fun onAlarm(fired: AlarmFired) {
        pending = fired
        // Load the stored session first, so a fire during a restored session merges into it instead of being ignored.
        route(fired, engine.restore().valueOrNull() ?: engine.state.value)
        pending = null
    }

    /**
     * Merges [fired] into the session in [current] when one rings or is snoozed, else starts a session for it. A fire
     * that is not merged ([mergeIgnoredBecause]) is logged and rings nothing.
     */
    private suspend fun route(
        fired: AlarmFired,
        current: SessionState,
    ) {
        val notMerged = (current as? SessionState.Active)?.takeIf { it.isOngoing() }?.let { mergeIgnoredBecause(it.session, fired) }
        if (notMerged != null) {
            logger.log(LogEvent.FireIgnored(FireKind.Alarm, fired.alarmId, notMerged))
            return
        }
        // A real alarm never merges into a test (Story 1.18): the test ends (logged Test) and the real session starts.
        val state = if (current is SessionState.Ring && current.session.config.testMode) endTestSession(current) else current
        if (state.isOngoing()) {
            val merged = engine.dispatch(SessionEvent.OverlapAlarmFired(fired.alarmId, fired.scheduledAt))
            if (merged is Outcome.Failure) ringIfSilent("merge not saved: ${merged.error.diagnostic()}", fired)
        } else {
            // After any guarded alarm write in flight, so a session never starts in the middle of one (Story 2.6).
            sessionLock.startingSession { startSession(fired) }
        }
    }

    /**
     * Why [fired] is not merged into the ringing or snoozed [session] (Story 2.9), or null when it merges:
     * - the session's own occurrence (same alarm and scheduled time, for example the backup slot of the alarm that
     *   started it): never merged into itself;
     * - the alarm was deleted before the service handled it;
     * - a repeating alarm switched off before the service handled it. A disabled one-time alarm still merges:
     *   `RearmOnFire` switches a fired one-time alarm off before the service reads it, and the receiver already refused
     *   one that was off when it fired.
     *
     * The read is bounded by [ALARM_READ_TIMEOUT]: a read that fails or takes longer merges (never silent).
     */
    private suspend fun mergeIgnoredBecause(
        session: SessionData,
        fired: AlarmFired,
    ): String? {
        val config = session.config
        if (!config.testMode && config.alarmId == fired.alarmId && config.scheduledAt == fired.scheduledAt) {
            return "the session's own occurrence"
        }
        val read = withTimeoutOrNull(ALARM_READ_TIMEOUT) { repository.get(fired.alarmId) }
        val alarm = (read as? Outcome.Success)?.value
        return when {
            read is Outcome.Failure && read.error is DomainError.NotFound -> "alarm deleted before it rang"
            alarm != null && !alarm.enabled && alarm.repeatDays.isNotEmpty() -> "repeating alarm switched off before it rang"
            else -> null
        }
    }

    /**
     * Ends the ringing test [test] through its normal end path (Epic 1's placeholder check: "I'm up", then the answer),
     * so it reaches Completed, is recorded as Test and returns to Idle. Returns the state after: Idle, or the test still
     * ringing when a step could not be saved (the real alarm then merges into it, so it still rings).
     */
    private suspend fun endTestSession(test: SessionState.Ring): SessionState {
        logger.log(LogEvent.OperationFailed("finish test session", "a real alarm rang; the test ends as Test"))
        if (test is SessionState.Ringing) engine.dispatch(SessionEvent.ImUpTapped)
        engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
        return engine.state.value
    }

    private suspend fun startSession(fired: AlarmFired) {
        when (val stored = repository.get(fired.alarmId)) {
            is Outcome.Success -> dispatchAlarmFired(fired, stored.value)
            is Outcome.Failure -> alarmUnreadable(fired, stored.error)
        }
    }

    private suspend fun dispatchAlarmFired(
        fired: AlarmFired,
        alarm: Alarm,
    ) {
        val config = ConfigResolver.resolve(alarm, GlobalSettings(), testMode = false, scheduledAt = fired.scheduledAt)
        val locked = !userLock.isUserUnlocked()
        val event =
            SessionEvent.AlarmFired(
                sessionId = ids.newId(),
                config = config,
                seeds = seedsFor(config.checkPlan, locked),
                beforeFirstUnlock = locked,
            )
        when (val started = engine.dispatch(event)) {
            is Outcome.Failure -> {
                runtime.startEmergency(fired.scheduledAt, alarm.volumePercent, "session not started: ${started.error.diagnostic()}", fired)
            }

            // The restore failed, but the load inside dispatch found a stored session, which ignored AlarmFired (review):
            // the alarm merges into it instead.
            is Outcome.Success -> {
                val found = started.value
                val foundId = (found as? SessionState.Active)?.session?.sessionId
                if (found.isOngoing() && foundId != event.sessionId) route(fired, found)
            }
        }
    }

    /** Seeds for the plan the first ring really runs: before the first unlock, with the Direct Boot substitutions (Story 2.3). */
    private fun seedsFor(
        plan: CheckPlan,
        locked: Boolean,
    ): List<Long> = seeds.seedsFor(DirectBootSubstitution.plan(plan, locked))

    private fun alarmUnreadable(
        fired: AlarmFired,
        error: DomainError,
    ) {
        if (error is DomainError.NotFound) {
            logger.log(LogEvent.FireIgnored(FireKind.Alarm, fired.alarmId, "alarm deleted before it rang"))
        } else {
            runtime.startEmergency(fired.scheduledAt, Alarm.DEFAULT_VOLUME_PERCENT, "alarm not readable: ${error.diagnostic()}", fired)
        }
    }

    /**
     * The test alarm fired (Story 1.18): rings the pending test config (the editor's values, `testMode`) as a new session.
     * Nothing pending is logged and rings nothing. While a session is active the engine ignores `TestAlarmFired` and logs
     * it (AD-2); a test never starts the emergency ring.
     */
    private suspend fun onTest() {
        // The stored session first (Story 2.1): a test that fires into a killed session is ignored by it, never a second one.
        engine.restore()
        val pending =
            when (val taken = testAlarms.take()) {
                is Outcome.Success -> taken.value
                is Outcome.Failure -> null.also { logger.log(LogEvent.OperationFailed.of("read pending test alarm", taken.error)) }
            }
        if (pending == null) {
            logger.log(LogEvent.FireIgnored(FireKind.TestAlarm, alarmId = null, reason = "no test pending"))
            return
        }
        val locked = !userLock.isUserUnlocked()
        val event =
            SessionEvent.TestAlarmFired(
                sessionId = ids.newId(),
                config = pending,
                seeds = seedsFor(pending.checkPlan, locked),
                beforeFirstUnlock = locked,
            )
        val started = sessionLock.startingSession { engine.dispatch(event) }
        if (started is Outcome.Failure) {
            logger.log(LogEvent.OperationFailed.of("start test session", started.error))
            // Not lost: the config goes back, so a later test fire (or "Test alarm" again) still has it.
            val restored = testAlarms.put(pending)
            if (restored is Outcome.Failure) logger.log(LogEvent.OperationFailed.of("put back pending test alarm", restored.error))
        }
    }

    /**
     * The session slot fired (Story 2.1), standing also for [alarm] when it carried one. The stored session is restored
     * first (after a kill that is `ProcessRestored`: entry effects only, same step), then [alarm] is handled like an
     * alarm start (merged into the session, or a new one), unless it is [SessionReducer.NO_INTERACTION_TIMEOUT] or more
     * past (logged and ignored: it would be Missed anyway). Then:
     * - a session: `SlotFired` (the heartbeat re-arms), then a tick;
     * - no session and the emergency ring plays: its backup slot is armed again (also when the store cannot be read, so
     *   the backstop never lapses);
     * - no session and the store could not be read: the emergency ring (NFR-2);
     * - otherwise an orphan slot: logged, and the service shuts down (slot cancelled, notification removed, NFR-8).
     */
    private suspend fun onSlot(alarm: AlarmFired?) {
        runtime.onSlotFired()
        val restored = engine.restore()
        alarm?.let { if (isFresh(it)) onAlarm(it) else logStale(it) }
        when {
            engine.state.value is SessionState.Active -> {
                val slot = engine.dispatch(SessionEvent.SlotFired)
                if (slot is Outcome.Failure) ringIfSilent("slot fire not saved: ${slot.error.diagnostic()}")
                // The slot wakes the phone at least every heartbeat: a deadline the main-thread timer missed in deep sleep
                // is handled here.
                engine.tick()
            }

            runtime.emergency.value != null -> {
                runtime.keepEmergencySlot()
            }

            restored is Outcome.Failure -> {
                ringIfSilent("session not loaded: ${restored.error.diagnostic()}")
            }

            else -> {
                logger.log(LogEvent.FireIgnored(FireKind.SessionSlot, alarmId = null, reason = "no session stored"))
            }
        }
    }

    /**
     * A repost start (Story 2.5): in a new process the engine is still Idle, so the stored session is loaded first, as
     * a restore does; [watch] then keeps the service only while a session or an emergency ring goes on.
     */
    private suspend fun onRepost() {
        if (engine.state.value == SessionState.Idle && runtime.emergency.value == null) onRestore()
    }

    /** The slot's alarm is less than [SessionReducer.NO_INTERACTION_TIMEOUT] past its scheduled time. */
    private fun isFresh(alarm: AlarmFired): Boolean = clock.now() - alarm.scheduledAt < SessionReducer.NO_INTERACTION_TIMEOUT

    private fun logStale(alarm: AlarmFired) =
        logger.log(LogEvent.FireIgnored(FireKind.SessionSlot, alarm.alarmId, "alarm ${SessionReducer.NO_INTERACTION_TIMEOUT} or more past"))

    /** A restore start: the runtime needs the service for a session, so a session that cannot be loaded still rings. */
    private suspend fun onRestore() {
        val restored = engine.restore()
        if (restored is Outcome.Failure) ringIfSilent("session not loaded: ${restored.error.diagnostic()}")
    }

    /**
     * A step of a session that should ring could not be committed (or loaded): unless the in-memory session rings
     * anyway, the emergency ring plays (NFR-2) for [alarm], else the session's alarm time, else now. Its backup slot
     * carries [alarm] (Story 2.1).
     */
    private fun ringIfSilent(
        cause: String,
        alarm: AlarmFired? = null,
    ) {
        val state = engine.state.value
        if (state is SessionState.Ring) {
            logger.log(LogEvent.OperationFailed("wake flow", "$cause; the session rings on"))
            return
        }
        val config = (state as? SessionState.Active)?.session?.config
        val at = alarm?.scheduledAt ?: config?.scheduledAt ?: clock.now()
        runtime.startEmergency(at, config?.volumePercent ?: Alarm.DEFAULT_VOLUME_PERCENT, cause, alarm)
    }

    /**
     * Ticks while a session is active and stops the service once it is over: checks the current state now (a command
     * may have changed nothing), then follows every change. Idempotent; started again after a crash.
     */
    private fun watch() {
        evaluate(engine.state.value, runtime.emergency.value)
        if (watching?.isActive == true) return
        watching =
            scope.launch {
                combine(engine.state, runtime.emergency) { state, emergency -> state to emergency }
                    .collect { (state, emergency) -> evaluate(state, emergency) }
            }
    }

    private fun evaluate(
        state: SessionState,
        emergency: EmergencyRing?,
    ) {
        when {
            state.isOngoing() -> startTicking()
            emergency == null -> shutdown()
        }
        watchUnlock(state)
    }

    /**
     * While a session before the first unlock runs (Story 2.4), listen for the unlock: [UserLockState.observe] registers a
     * context receiver for `ACTION_USER_UNLOCKED` while collected (a manifest receiver never gets it) and unregisters it
     * when this watch is cancelled, at the session's end or when the service stops. The unlock goes to [UnlockSignals].
     */
    private fun watchUnlock(state: SessionState) {
        val locked = state is SessionState.Active && state.session.beforeFirstUnlock && state.isOngoing()
        when {
            !locked -> {
                unlockWatch?.cancel()
                unlockWatch = null
            }

            // One unlock per boot: once seen, the signal is not sent again (Grace and Loud ignore it, AD-2).
            unlockWatch == null -> {
                unlockWatch =
                    scope.launch {
                        userLock.observe().first { it }
                        unlockSignals.onUnlocked()
                    }
            }
        }
    }

    private fun startTicking() {
        if (ticking?.isActive == true) return
        ticking = scope.launch { tickOnDeadlines() }
    }

    /**
     * Ticks the engine when the session's next deadline is due (Story 1.16): [nextTickIn] gives the time left to the grace
     * end or the 30-minute interaction deadline on the monotonic clock, and the loop waits that long, or until the state
     * changes, whichever comes first. With no deadline (snoozed, paused by a call) it only waits for the next state, so it
     * never spins; a tick that changed nothing (its commit failed) is retried after [TICK_RETRY] at the earliest. Neither
     * the wait nor the service stopping cancels a running tick (it runs on ApplicationScope). In deep sleep the
     * main-thread timer can lag: the 60 s heartbeat slot wakes the phone and [onSlot] ticks too.
     */
    private suspend fun tickOnDeadlines() {
        var unchangedByTick: SessionState? = null
        while (true) {
            val state = engine.state.value
            val due = nextTickIn(state, TimeSnapshot.of(clock, monotonicClock, bootCounter))
            val wait = if (due != null && state == unchangedByTick) maxOf(due, TICK_RETRY) else due
            val changed =
                if (wait == null) {
                    engine.state.first { it != state }
                } else {
                    withTimeoutOrNull(wait) { engine.state.first { it != state } }
                }
            if (changed == null) {
                // On ApplicationScope: the Missed it may produce stops the service, which cancels this loop, and the tick
                // must still write history and reach Idle.
                appScope.async { engine.tick() }.await()
                unchangedByTick = state.takeIf { engine.state.value == it }
            }
        }
    }

    /** Stops the service, unless a command is running (it calls [watch] when done) or a newer start arrived. */
    private fun shutdown() {
        if (stopping || commands.isLocked) return
        stopping = true
        ticking?.cancel()
        unlockWatch?.cancel()
        runtime.endSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    private fun onCrash(e: Throwable) {
        crashes++
        runtime.ringDefaultAfterCrash(pending)
        pending = null
        if (crashes > MAX_CRASH_RESTARTS) {
            if (crashes == MAX_CRASH_RESTARTS + 1) {
                logger.log(LogEvent.OperationFailed("wake flow", "crashed $crashes times; not restarted, the default sound rings on"))
            }
            return
        }
        crashReporter.report(e)
        appScope.launch { engine.dispatch(SessionEvent.ProcessRestored) }
        // The crashed job may have been the watcher or the tick loop: start them again.
        scope.launch { watch() }
    }

    companion object {
        const val ACTION_ALARM = "com.yawnandpawn.app.action.WAKE_ALARM"
        const val ACTION_SLOT = "com.yawnandpawn.app.action.WAKE_SLOT"
        const val ACTION_RESTORE = "com.yawnandpawn.app.action.WAKE_RESTORE"
        const val ACTION_TEST = "com.yawnandpawn.app.action.WAKE_TEST"

        /** The ringing notification's delete intent (Story 2.5): post it again, nothing else. */
        const val ACTION_REPOST = "com.yawnandpawn.app.action.WAKE_REPOST"

        /** The alarm receiver's `WakeServiceStarts` token on a start it waits for. */
        const val EXTRA_START_TOKEN = "startToken"

        /** The earliest retry of a tick that changed nothing (for example its commit failed). */
        private val TICK_RETRY = 1.seconds

        /** The bound on the alarm read before a merge (Story 2.9 review): a slower read counts as a failure and merges. */
        internal val ALARM_READ_TIMEOUT = 3.seconds
        private const val MAX_CRASH_RESTARTS = 3

        /** The explicit intent for [action]. */
        fun intent(
            context: Context,
            action: String,
        ): Intent = Intent(context, WakeService::class.java).setAction(action)

        /** The intent that posts the swiped-away ringing notification again ([ACTION_REPOST]). */
        fun repostIntent(context: Context): Intent = intent(context, ACTION_REPOST)

        /**
         * The intent of a session slot fire, standing also for [alarm] when the slot carried one (Story 2.1), and carrying
         * since when its starts are refused ([retrySince]).
         */
        fun slotIntent(
            context: Context,
            alarm: AlarmFired?,
            retrySince: Instant? = null,
        ): Intent = intent(context, ACTION_SLOT).putAlarmFired(alarm).putRetrySince(retrySince)

        /** The intent that rings the stored alarm [fired]. */
        fun alarmIntent(
            context: Context,
            fired: AlarmFired,
        ): Intent =
            intent(context, ACTION_ALARM)
                .putExtra(AlarmFiredReceiver.EXTRA_ALARM_ID, fired.alarmId)
                .putExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, fired.scheduledAt.toEpochMilliseconds())

        private fun Intent.toAlarmFired(): AlarmFired? {
            val alarmId = getStringExtra(AlarmFiredReceiver.EXTRA_ALARM_ID)
            if (action != ACTION_ALARM || alarmId == null || !hasExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT)) return null
            return AlarmFired(alarmId, Instant.fromEpochMilliseconds(getLongExtra(AlarmFiredReceiver.EXTRA_SCHEDULED_AT, 0)))
        }

        /** A session that rings or is snoozed: the service keeps running and ticking. */
        private fun SessionState.isOngoing(): Boolean = this is SessionState.Ring || this is SessionState.Snoozed
    }
}
