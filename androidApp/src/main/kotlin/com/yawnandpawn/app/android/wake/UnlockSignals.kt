package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What the first unlock after a boot sets off (Story 2.4, AD-15). Every unlock signal comes here:
 * - `ACTION_USER_UNLOCKED`, observed by [WakeService] while a session before the first unlock runs;
 * - `BOOT_COMPLETED` (only delivered after the unlock);
 * - [WakeActivity] resumed with the user unlocked, for example after `requestDismissKeyguard` ([onScreenResumedUnlocked]).
 *
 * Each signal:
 * - initialises billing and crash reporting once per process, outside the AD-2 table, so an unlock in Grace or Loud
 *   (no `UserUnlocked` row) still starts them. Each is marked done only once it succeeded, so a throw is logged and the
 *   next signal tries again;
 * - dispatches `UserUnlocked` while the session in memory is still marked before the first unlock. Ringing applies the
 *   AD-2 row; Grace and Loud ignore and log it. A dispatch whose commit failed is retried, at most [MAX_ATTEMPTS] times
 *   [retry] apart, while the ring still waits for the unlock. While one dispatch runs, other signals dispatch nothing.
 *
 * Signals after that change nothing and never throw. It never restores the session (a receiver may call it): a session
 * the engine has not loaded is left alone. The dispatch is launched on [ApplicationScope], never awaited inside an engine
 * effect.
 */
class UnlockSignals(
    private val engine: SessionEngine,
    private val billing: Billing,
    private val firebase: FirebaseStartup,
    private val scope: ApplicationScope,
    private val logger: Logger,
    private val retry: Duration = RETRY,
) {
    private var billingStarted = false

    /** A `UserUnlocked` dispatch (with its retries) is running. */
    private val dispatching = AtomicBoolean(false)

    /** The user has unlocked the phone (the broadcast or `BOOT_COMPLETED`). */
    fun onUnlocked() = signal(ringingOnly = false)

    /**
     * The wake screen resumed with the user unlocked. It resumes often (after every dialog, the shade, a call), so it
     * dispatches only to a ring that still waits for the unlock, never the ignored event to Snoozed, Grace or Loud.
     */
    fun onScreenResumedUnlocked() = signal(ringingOnly = true)

    private fun signal(ringingOnly: Boolean) {
        initialiseAfterUnlock()
        val state = engine.state.value
        val wanted = if (ringingOnly) state.waitsForUnlockRinging() else state is SessionState.Active && state.session.beforeFirstUnlock
        if (!wanted || !dispatching.compareAndSet(false, true)) return
        scope.launch {
            try {
                dispatchUnlocked()
            } finally {
                dispatching.set(false)
            }
        }
    }

    private suspend fun dispatchUnlocked() {
        repeat(MAX_ATTEMPTS) { attempt ->
            if (engine.dispatch(SessionEvent.UserUnlocked) is Outcome.Success) return
            if (!engine.state.value.waitsForUnlockRinging()) return
            if (attempt < MAX_ATTEMPTS - 1) delay(retry)
        }
        logger.log(LogEvent.OperationFailed(UNLOCK, "UserUnlocked not committed after $MAX_ATTEMPTS attempts"))
    }

    /**
     * Billing and Firebase, once per process: the AD-2 `InitBilling` effect and every unlock signal end here. Never
     * throws: a failure is logged, and the part that failed is tried again on the next call.
     */
    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    fun initialiseAfterUnlock() {
        if (!billingStarted) {
            try {
                billing.init()
                billingStarted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogEvent.OperationFailed("start billing", e::class.simpleName.orEmpty()))
            }
        }
        // Application.onCreate starts Firebase too: started (or waiting for the unlock), it is not asked again.
        if (!firebase.started) {
            try {
                firebase.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogEvent.OperationFailed("start crash reporting", e::class.simpleName.orEmpty()))
            }
        }
    }

    companion object {
        /** How often a `UserUnlocked` whose commit failed is sent at most. */
        const val MAX_ATTEMPTS = 5

        /** The wait between two attempts. */
        val RETRY: Duration = 1.seconds

        private const val UNLOCK = "apply the unlock"

        private fun SessionState.waitsForUnlockRinging(): Boolean = this is SessionState.Ringing && session.beforeFirstUnlock
    }
}
