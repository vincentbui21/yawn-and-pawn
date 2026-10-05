package com.yawnandpawn.app.android.screen

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yawnandpawn.app.android.wake.EmergencyRing
import com.yawnandpawn.app.android.wake.WakeActivity
import com.yawnandpawn.app.android.wake.WakeRuntime
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Whether the app, opened by the user, hands over to the wake screen (Story 2.5, FR-SES-4). It does while the alarm
 * rings: Ringing, Grace or Loud, or an emergency ring. It does not in Snoozed (Story 2.6 shows the app's own "Alarm in
 * progress" screen), nor when no session is active.
 */
fun forwardsToWakeScreen(
    state: SessionState,
    emergency: EmergencyRing?,
): Boolean = emergency != null || state is SessionState.Ring

/**
 * While [activity] is resumed, starts [WakeActivity] as soon as [forwardsToWakeScreen] holds: when the user opens the
 * app from the launcher or Recents during a ring, or when a ring starts while the app is in front. At most once per
 * resume; the start comes from the activity in front, never from the background (AD-5).
 */
fun <T> forwardToWakeScreenWhileResumed(
    activity: T,
    engine: SessionEngine,
    runtime: WakeRuntime,
) where T : Activity, T : LifecycleOwner {
    activity.lifecycleScope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            combine(engine.state, runtime.emergency, ::forwardsToWakeScreen).first { it }
            activity.startActivity(WakeActivity.intent(activity))
        }
    }
}
