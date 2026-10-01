package com.yawnandpawn.app.android.wake

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import com.yawnandpawn.app.android.AlarmFiredReceiver
import com.yawnandpawn.app.android.ApplicationScope
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
import com.yawnandpawn.app.core.log.diagnostic
import com.yawnandpawn.app.core.session.ConfigResolver
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SeedSource
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.TestAlarmStore
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
 * - `onStartCommand` calls `startForeground` with the ringing notification first, before any suspend work.
 * - **Alarm:** while a session rings or is snoozed it dispatches `OverlapAlarmFired`; otherwise it reads the alarm and
 *   dispatches `AlarmFired` with a new session id, the config resolved now (`GlobalSettings` defaults until Epic 5), seeds
 *   from the [SeedSource] and whether the phone is still locked since boot. A deleted alarm rings nothing.
 * - **Slot:** it dispatches `SlotFired`, then ticks. **Restore:** it loads the stored session (`SessionEngine.restore`).
 * - **Test:** it takes the pending test config and dispatches `TestAlarmFired` (Story 1.18).
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> onCrash(e) })
    private val commands = Mutex()
    private var watching: Job? = null
    private var ticking: Job? = null
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
        runtime.onServiceStarted()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastStartId = startId
        val fired = intent?.toAlarmFired()
        // An alarm start shows its ringing notification at once; a slot or restore start shows the ringing one only when
        // it is already up, else the quiet one (no full-screen intent) until a ringing state posts its own.
        if (!enterForeground(fired?.scheduledAt ?: runtime.shownAlarmAt())) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        stopping = false
        // Main.immediate: the command takes the lock before onStartCommand returns, so a shutdown cannot slip in.
        scope.launch {
            commands.withLock {
                when {
                    fired != null -> onAlarm(fired)
                    intent?.action == ACTION_SLOT -> onSlot()
                    intent?.action == ACTION_TEST -> onTest()
                    else -> onRestore()
                }
            }
            watch()
        }
        // Not sticky: a restart without an intent would post the alarm notification for nothing; the slot revives a ring.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runtime.onServiceStopped()
        super.onDestroy()
    }

    // startForeground throws ForegroundServiceStartNotAllowedException (an IllegalStateException) or SecurityException
    // when the platform refuses; the armed slot brings the ring back.
    private fun enterForeground(alarmAt: Instant?): Boolean =
        try {
            val notification = runtime.foregroundNotification(alarmAt)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(WakeNotifier.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(WakeNotifier.NOTIFICATION_ID, notification)
            }
            runtime.foregroundPosted(alarmAt)
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
        val current = engine.restore().valueOrNull() ?: engine.state.value
        if (current is SessionState.Ring || current is SessionState.Snoozed) {
            val merged = engine.dispatch(SessionEvent.OverlapAlarmFired(fired.alarmId, fired.scheduledAt))
            if (merged is Outcome.Failure) ringIfSilent("merge not saved: ${merged.error.diagnostic()}", fired.scheduledAt)
        } else {
            startSession(fired)
        }
        pending = null
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
        val event =
            SessionEvent.AlarmFired(
                sessionId = ids.newId(),
                config = config,
                seeds = seeds.seedsFor(config.checkPlan),
                beforeFirstUnlock = !getSystemService(UserManager::class.java).isUserUnlocked,
            )
        val started = engine.dispatch(event)
        if (started is Outcome.Failure) {
            runtime.startEmergency(fired.scheduledAt, alarm.volumePercent, "session not started: ${started.error.diagnostic()}")
        }
    }

    private fun alarmUnreadable(
        fired: AlarmFired,
        error: DomainError,
    ) {
        if (error is DomainError.NotFound) {
            logger.log(LogEvent.FireIgnored(FireKind.Alarm, fired.alarmId, "alarm deleted before it rang"))
        } else {
            runtime.startEmergency(fired.scheduledAt, Alarm.DEFAULT_VOLUME_PERCENT, "alarm not readable: ${error.diagnostic()}")
        }
    }

    /**
     * The test alarm fired (Story 1.18): rings the pending test config (the editor's values, `testMode`) as a new session.
     * Nothing pending is logged and rings nothing. While a session is active the engine ignores `TestAlarmFired` and logs
     * it (AD-2); a test never starts the emergency ring.
     */
    private suspend fun onTest() {
        val pending =
            when (val taken = testAlarms.take()) {
                is Outcome.Success -> taken.value
                is Outcome.Failure -> null.also { logger.log(LogEvent.OperationFailed.of("read pending test alarm", taken.error)) }
            }
        if (pending == null) {
            logger.log(LogEvent.FireIgnored(FireKind.TestAlarm, alarmId = null, reason = "no test pending"))
            return
        }
        val event =
            SessionEvent.TestAlarmFired(
                sessionId = ids.newId(),
                config = pending,
                seeds = seeds.seedsFor(pending.checkPlan),
                beforeFirstUnlock = !getSystemService(UserManager::class.java).isUserUnlocked,
            )
        val started = engine.dispatch(event)
        if (started is Outcome.Failure) logger.log(LogEvent.OperationFailed.of("start test session", started.error))
    }

    private suspend fun onSlot() {
        runtime.onSlotFired()
        val slot = engine.dispatch(SessionEvent.SlotFired)
        if (slot is Outcome.Failure) ringIfSilent("slot fire not saved: ${slot.error.diagnostic()}")
        // The slot wakes the phone at least every heartbeat: a deadline the main-thread timer missed in deep sleep is
        // handled here.
        engine.tick()
    }

    /** A restore start: the runtime needs the service for a session, so a session that cannot be loaded still rings. */
    private suspend fun onRestore() {
        val restored = engine.restore()
        if (restored is Outcome.Failure) ringIfSilent("session not loaded: ${restored.error.diagnostic()}")
    }

    /**
     * A step of a session that should ring could not be committed (or loaded): unless the in-memory session rings
     * anyway, the emergency ring plays (NFR-2) for [alarmAt], else the session's alarm time, else now.
     */
    private fun ringIfSilent(
        cause: String,
        alarmAt: Instant? = null,
    ) {
        val state = engine.state.value
        if (state is SessionState.Ring) {
            logger.log(LogEvent.OperationFailed("wake flow", "$cause; the session rings on"))
            return
        }
        val config = (state as? SessionState.Active)?.session?.config
        val at = alarmAt ?: config?.scheduledAt ?: clock.now()
        runtime.startEmergency(at, config?.volumePercent ?: Alarm.DEFAULT_VOLUME_PERCENT, cause)
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

        /** The earliest retry of a tick that changed nothing (for example its commit failed). */
        private val TICK_RETRY = 1.seconds
        private const val MAX_CRASH_RESTARTS = 3

        /** The explicit intent for [action]. */
        fun intent(
            context: Context,
            action: String,
        ): Intent = Intent(context, WakeService::class.java).setAction(action)

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
