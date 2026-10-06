package com.yawnandpawn.app.android.call

import android.os.Build
import com.yawnandpawn.app.android.wake.WakeRuntime
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The call adapter (Story 2.7, FR-SES-8, AD-2): keeps the session's call pause in step with the phone, using only the
 * audio mode ([CallState]); never `READ_PHONE_STATE` or telephony (NFR-13, `NoHostageApis`).
 *
 * Each [check] reconciles, serialized and on [scope] (`WakeScope`, the main thread; never inside an engine effect):
 * - the session rings (Ringing, Grace or Loud), the phone is in a call and the ring is not paused: `CallStarted`;
 * - the ring is paused and the call is over: `CallEnded`;
 * - no call, not paused, and the runtime holds the player silent for a call that ended before the session paused:
 *   [WakeRuntime.onCallOver] lets it ring;
 * - an emergency ring (no session) follows the call itself ([WakeRuntime.onEmergencyCall]).
 * Snoozed, Idle and ended sessions are left alone (calls change nothing while snoozed). A dispatch that fails (the
 * session could not be committed) is checked again after [pollEvery].
 *
 * A pause never lasts forever: a call mode that keeps the ring paused for [pauseCap] (an app that left the mode set)
 * is logged, ignored until the phone leaves the call modes ([StuckCallGuard]) and the ring resumes (`CallEnded`).
 *
 * Checks run on every session state change (a new ring, a restore: the reducer clears the pause on restore, so a call
 * still going on is paused again), at every ring start ([follow]), on the alarm player's audio-focus changes
 * ([onFocusChange]), on audio-mode changes (API 31+) and, on API 26 to 30 where there is no mode listener, every
 * [pollEvery] while a call pauses or is about to pause a ring. The wake service and every ring start it ([start] is
 * idempotent); the service stops it. Checks only run while it is started.
 */
class CallDetector(
    private val calls: StuckCallGuard,
    private val engine: SessionEngine,
    private val runtime: WakeRuntime,
    private val scope: CoroutineScope,
    private val monotonicClock: MonotonicClock,
    private val logger: Logger,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val pollEvery: Duration = 1.seconds,
    private val pauseCap: Duration = SessionReducer.NO_INTERACTION_TIMEOUT,
) {
    private val reconciling = Mutex()

    @Volatile
    private var job: Job? = null
    private var unwatch: (() -> Unit)? = null

    /** When the detector first saw the ring paused for the current call (monotonic); null when not paused. */
    private var pausedSince: Long? = null

    /** Starts following the session and the audio mode; idempotent. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        val running = SupervisorJob(scope.coroutineContext[Job])
        job = running
        unwatch = calls.watch(::check)
        scope.launch(running) { engine.state.collect { check() } }
        if (sdkInt < Build.VERSION_CODES.S) scope.launch(running) { poll() }
    }

    /** Stops following; idempotent. Pending checks are cancelled and later ones do nothing. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        unwatch?.invoke()
        unwatch = null
    }

    /** A ring (session or emergency) is on: follow the calls, even when the wake service could not start. */
    fun follow() {
        start()
        check()
    }

    /** The alarm player's audio focus changed: a call takes focus, and gives it back when it ends. */
    @Suppress("UNUSED_PARAMETER")
    fun onFocusChange(focusChange: Int) = check()

    /** Reconciles the session with the phone's call state, once, on [scope]; nothing when not started. */
    fun check() {
        val running = job?.takeIf { it.isActive } ?: return
        scope.launch(running) { reconciling.withLock { reconcile() } }
    }

    private suspend fun reconcile() {
        val inCall = calls.inCall()
        if (runtime.emergency.value != null) return runtime.onEmergencyCall(inCall)
        val ring = engine.state.value as? SessionState.Ring
        if (ring?.session?.paused != true) pausedSince = null
        if (ring == null) return
        when {
            inCall && !ring.session.paused -> dispatch(SessionEvent.CallStarted)
            inCall -> capPause()
            ring.session.paused -> dispatch(SessionEvent.CallEnded)
            runtime.isRingHeld -> runtime.onCallOver()
        }
    }

    /** The ring is paused for a call: after [pauseCap] of it the call counts as over (a stuck mode never silences it). */
    private suspend fun capPause() {
        val now = monotonicClock.elapsedMillis()
        val since =
            pausedSince ?: now.also {
                pausedSince = it
                recheckAfter(pauseCap)
            }
        if (now - since < pauseCap.inWholeMilliseconds) return
        logger.log(LogEvent.OperationFailed(PAUSE_FOR_CALL, "the call mode lasted $pauseCap; the alarm rings again"))
        calls.ignoreCurrentCall()
        pausedSince = null
        dispatch(SessionEvent.CallEnded)
    }

    /** Dispatches [event]; a failure (logged by the engine) is checked again after [pollEvery], whatever the API. */
    private suspend fun dispatch(event: SessionEvent) {
        if (engine.dispatch(event) is Outcome.Failure) recheckAfter(pollEvery)
    }

    private fun recheckAfter(wait: Duration) {
        val running = job?.takeIf { it.isActive } ?: return
        scope.launch(running) {
            delay(wait)
            check()
        }
    }

    private suspend fun poll() {
        while (currentCoroutineContext().isActive) {
            delay(pollEvery)
            val ring = engine.state.value as? SessionState.Ring
            val watched = runtime.emergency.value != null || (ring != null && (ring.session.paused || calls.inCall()))
            if (watched) check()
        }
    }

    private companion object {
        const val PAUSE_FOR_CALL = "pause the alarm for a call"
    }
}
