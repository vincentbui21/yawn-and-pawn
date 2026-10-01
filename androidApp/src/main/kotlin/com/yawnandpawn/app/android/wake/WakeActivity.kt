package com.yawnandpawn.app.android.wake

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.core.session.CheckAnswer
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.RingingUiState
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.ui.wake.WakeSurface
import com.yawnandpawn.app.ui.wake.alarmOnlyRingingUiState
import com.yawnandpawn.app.ui.wake.placeholderStepDue
import com.yawnandpawn.app.ui.wake.ringingUiState
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * The wake screen over the lock screen (AD-5): the approved Ringing screen (`ui/wake/RingingScreen`, Story 1.15).
 *
 * It opens only from the ringing notification (its full-screen intent or a tap on it), never from the background. It
 * shows over the lock screen and turns the screen on (`setShowWhenLocked` / `setTurnScreenOn` on API 27+, window flags
 * on API 26), keeps the screen on, and Back does nothing (Home and Recents still work).
 *
 * It renders from in-memory state only, with no loading state and no repository call: the engine's `state` mapped by
 * [ringingUiState], with the snooze the [SnoozeAvailabilityPolicy] allows ("Snooze unavailable: prices not loaded yet",
 * or "Test · no charge" for a test session). The emergency ring shows its own alarm time. Opened just before the
 * session starts (the service posts the ringing notification first), it shows the notification's alarm time and waits.
 *
 * Every dispatch is launched on [ApplicationScope], outside composition and outside any engine effect:
 * - "I'm up" sends `UserInteracted`, then `ImUpTapped`. In the emergency ring it stops the ring instead.
 * - Any other tap sends `UserInteracted`.
 * - Grace or Loud on the Epic 1 placeholder check step: the screen answers it (`CheckAnswerSubmitted(Placeholder)`), so
 *   "I'm up" alone ends the session. Epic 3 shows the real check here instead.
 *
 * It finishes once a session (or emergency ring) it showed is over: Idle, Completed or Missed, with no emergency ring.
 */
class WakeActivity : ComponentActivity() {
    private val engine: SessionEngine by inject()
    private val runtime: WakeRuntime by inject()
    private val appScope: ApplicationScope by inject()
    private val timeZones: TimeZoneProvider by inject()
    private val snoozePolicy: SnoozeAvailabilityPolicy by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                // The wake screen is left only by ending the session (EXPERIENCE.md: Back does nothing).
                override fun handleOnBackPressed() = Unit
            },
        )
        setContent {
            val state by engine.state.collectAsState()
            val emergency by runtime.emergency.collectAsState()
            // The full-screen intent can open the screen just before the session starts (the service posts the ringing
            // notification first): it waits for a session or an emergency ring, and closes only once that is over.
            val active = state.isRinging() || emergency != null
            var seen by remember { mutableStateOf(false) }
            LaunchedEffect(active) {
                if (active) {
                    seen = true
                } else if (seen) {
                    finish()
                }
            }
            // The Epic 1 check is a placeholder: answered once per session, ring and step (repeats are ignored).
            val placeholder = if (emergency == null) placeholderStepDue(state) else null
            LaunchedEffect(placeholder) {
                if (placeholder != null) send(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
            }
            val zone = timeZones.current()
            val session = (state as? SessionState.Active)?.session
            val current =
                emergency?.let { alarmOnlyRingingUiState(it.alarmAt, zone) }
                    ?: session?.let { ringingUiState(it, snoozePolicy.availability(it), zone) }
                    ?: runtime.shownAlarmAt()?.let { alarmOnlyRingingUiState(it, zone) }
            // Once a session ends the screen keeps its last look until it closes, instead of flashing an empty surface.
            val last = remember { LastShown() }
            if (current != null) last.state = current
            WakeContent(state = current ?: last.state, onIntent = ::onIntent, onInteracted = ::interacted)
        }
    }

    private fun onIntent(intent: WakeIntent) {
        when {
            intent != WakeIntent.ImUpClicked -> interacted()
            runtime.emergency.value != null -> runtime.stopEmergency()
            else -> send(SessionEvent.UserInteracted, SessionEvent.ImUpTapped)
        }
    }

    /** Any tap but "I'm up" (buying a snooze arrives in Epic 4): resets the interaction deadline of the session. */
    private fun interacted() {
        if (runtime.emergency.value == null) send(SessionEvent.UserInteracted)
    }

    private fun send(vararg events: SessionEvent) {
        appScope.launch { events.forEach { engine.dispatch(it) } }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        /** The intent of the ringing notification. */
        fun intent(context: Context): Intent =
            Intent(context, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

        /** A session the wake screen is for: ringing, quiet, loud or snoozed. */
        private fun SessionState.isRinging(): Boolean = this is SessionState.Ring || this is SessionState.Snoozed
    }
}

/** The last Ringing state the screen showed; not snapshot state, so keeping it never recomposes. */
private class LastShown {
    var state: RingingUiState? = null
}

/**
 * The Ringing screen for [state], or the plain Sunrise wake surface while there is nothing to show yet. A tap that no
 * action takes (the clock, the disabled snooze, empty space) is [onInteracted].
 */
@Composable
private fun WakeContent(
    state: RingingUiState?,
    onIntent: (WakeIntent) -> Unit,
    onInteracted: () -> Unit,
) {
    val taps = Modifier.pointerInput(Unit) { detectTapGestures { onInteracted() } }
    if (state == null) {
        WakeSurface(modifier = taps) {}
    } else {
        RingingScreen(state = state, is24Hour = is24HourClock(), onIntent = onIntent, modifier = taps)
    }
}
