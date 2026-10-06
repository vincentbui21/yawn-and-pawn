package com.yawnandpawn.app.android.call

import android.os.Build
import com.yawnandpawn.app.android.wake.WakeRuntime
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * Each [check] reconciles, serialized and on [scope] (never inside an engine effect):
 * - the session rings (Ringing, Grace or Loud), the phone is in a call and the ring is not paused: `CallStarted`;
 * - the ring is paused and the call is over: `CallEnded`;
 * - no call and not paused: the runtime resumes a player it held silent for a call that ended before the session
 *   paused ([WakeRuntime.onCallOver]).
 * Snoozed, Idle and ended sessions are left alone (calls change nothing while snoozed).
 *
 * Checks run on every session state change (a new ring, a restore: the reducer clears the pause on restore, so a call
 * still going on is paused again), on the alarm player's audio-focus changes ([onFocusChange]), on audio-mode changes
 * (API 31+) and, on API 26 to 30 where there is no mode listener, every [pollEvery] while a call pauses or is about to
 * pause the ring. The wake service runs it ([start] / [stop]).
 */
class CallDetector(
    private val calls: CallState,
    private val engine: SessionEngine,
    private val runtime: WakeRuntime,
    private val scope: CoroutineScope,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val pollEvery: Duration = 1.seconds,
) {
    private val reconciling = Mutex()
    private var job: Job? = null
    private var unwatch: (() -> Unit)? = null

    /** Starts following the session and the audio mode; idempotent. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        unwatch = calls.watch(::check)
        job =
            scope.launch {
                launch { engine.state.collect { check() } }
                if (sdkInt < Build.VERSION_CODES.S) launch { poll() }
            }
    }

    /** Stops following; idempotent. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        unwatch?.invoke()
        unwatch = null
    }

    /** The alarm player's audio focus changed: a call takes focus, and gives it back when it ends. */
    @Suppress("UNUSED_PARAMETER")
    fun onFocusChange(focusChange: Int) = check()

    /** Reconciles the session with the phone's call state, once, on [scope]. */
    fun check() {
        scope.launch { reconciling.withLock { reconcile() } }
    }

    private suspend fun reconcile() {
        val ring = engine.state.value as? SessionState.Ring ?: return
        val inCall = calls.inCall()
        when {
            inCall && !ring.session.paused -> engine.dispatch(SessionEvent.CallStarted)
            !inCall && ring.session.paused -> engine.dispatch(SessionEvent.CallEnded)
            !inCall -> runtime.onCallOver()
        }
    }

    private suspend fun poll() {
        while (currentCoroutineContext().isActive) {
            delay(pollEvery)
            val ring = engine.state.value as? SessionState.Ring
            if (ring != null && (ring.session.paused || calls.inCall())) check()
        }
    }
}
