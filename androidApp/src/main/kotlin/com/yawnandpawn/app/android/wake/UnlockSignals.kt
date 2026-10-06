package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the first unlock after a boot sets off (Story 2.4, AD-15). Every unlock signal comes here:
 * - `ACTION_USER_UNLOCKED`, observed by [WakeService] while a session before the first unlock runs;
 * - `BOOT_COMPLETED` (only delivered after the unlock);
 * - [WakeActivity] resumed with the user unlocked, for example after `requestDismissKeyguard`.
 *
 * Each signal:
 * - initialises billing and crash reporting once per process, outside the AD-2 table, so an unlock in Grace or Loud
 *   (no `UserUnlocked` row) still starts them;
 * - dispatches `UserUnlocked` while the session in memory is still marked before the first unlock. Ringing applies the
 *   AD-2 row; Grace and Loud ignore and log it.
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
) {
    private val initialised = AtomicBoolean(false)

    /** The user has unlocked the phone (from any signal). */
    fun onUnlocked() {
        initialiseAfterUnlock()
        val state = engine.state.value
        if (state is SessionState.Active && state.session.beforeFirstUnlock) {
            scope.launch { engine.dispatch(SessionEvent.UserUnlocked) }
        }
    }

    /** Billing and Firebase, once per process: the AD-2 `InitBilling` effect and every unlock signal end here. */
    fun initialiseAfterUnlock() {
        if (!initialised.compareAndSet(false, true)) return
        billing.init()
        firebase.start()
    }
}
