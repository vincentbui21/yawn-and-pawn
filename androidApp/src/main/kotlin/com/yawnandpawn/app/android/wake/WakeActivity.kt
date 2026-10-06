package com.yawnandpawn.app.android.wake

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.screen.forwardsToWakeScreen
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.WakeStage
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.wake.PlaceholderStep
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.RingingUiState
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.ui.wake.WakeSurface
import com.yawnandpawn.app.ui.wake.alarmOnlyRingingUiState
import com.yawnandpawn.app.ui.wake.placeholderStepDue
import com.yawnandpawn.app.ui.wake.ringingUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The wake screen over the lock screen (AD-5): the approved Ringing screen (`ui/wake/RingingScreen`, Story 1.15).
 *
 * It opens only from the ringing notification (its full-screen intent or a tap on it), never from the background. It
 * shows over the lock screen and turns the screen on (`setShowWhenLocked` / `setTurnScreenOn` on API 27+, window flags
 * on API 26), keeps the screen on, and Back does nothing (Home and Recents still work). While it is in front with focus
 * and the alarm rings, the volume keys do nothing either ([VolumeKeyGate], Story 2.8), except the accessibility
 * shortcut.
 *
 * It renders from in-memory state only, with no loading state and no repository call: the engine's `state` mapped by
 * [ringingUiState], with the snooze the [SnoozeAvailabilityPolicy] allows ("Prices not loaded yet",
 * or "Test · no charge" for a test session, or "Unlock your phone to snooze" before the first unlock). The availability
 * follows the live [UserLockState] too (Story 2.4), so an unlock re-renders the snooze control in place, without
 * finishing or recreating the screen. The emergency ring shows its own alarm time. Opened just before the
 * session starts (the service posts the ringing notification first), it shows the notification's alarm time and waits.
 *
 * Every dispatch is launched on [ApplicationScope], outside composition and outside any engine effect:
 * - "I'm up" sends `UserInteracted`, then `ImUpTapped`. In the emergency ring it stops the ring instead. Tapped while
 *   the screen still waits for the session, it is kept and sent once the session (or an emergency ring) rings.
 * - Any other tap sends `UserInteracted`.
 * - Grace or Loud on the Epic 1 placeholder check step: the screen answers it (`CheckAnswerSubmitted(Placeholder)`), so
 *   "I'm up" alone ends the session; a failed dispatch is retried while the step is due. Epic 3 shows the real check
 *   here instead.
 *
 * It finishes once a session (or emergency ring) it showed is over: Idle, Completed or Missed, with no emergency ring.
 */
class WakeActivity : ComponentActivity() {
    private val engine: SessionEngine by inject()
    private val runtime: WakeRuntime by inject()
    private val appScope: ApplicationScope by inject()
    private val timeZones: TimeZoneProvider by inject()
    private val snoozePolicy: SnoozeAvailabilityPolicy by inject()
    private val timings: WakeTimings by inject()
    private val userLock: UserLockState by inject()
    private val unlockSignals: UnlockSignals by inject()

    /** "I'm up" was tapped before the session existed; replayed once it rings. */
    private var pendingImUp by mutableStateOf(false)

    /**
     * The volume keys do nothing while this screen is in front with focus and something rings (Story 2.8); the
     * accessibility shortcut passes.
     */
    internal val volumeKeys = VolumeKeyGate(ringing = { forwardsToWakeScreen(engine.state.value, runtime.emergency.value) })

    /**
     * Resumed with the user unlocked is an unlock signal (Story 2.4), for example back from the PIN prompt of
     * `requestDismissKeyguard`, or after an unlock while another screen was in front. The screen itself stays.
     */
    override fun onResume() {
        super.onResume()
        volumeKeys.resumed = true
        if (userLock.isUserUnlocked()) unlockSignals.onScreenResumedUnlocked()
    }

    override fun onPause() {
        volumeKeys.resumed = false
        super.onPause()
    }

    // Resumed is not enough: with the notification shade down or in split screen the keys belong to the focused window.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        volumeKeys.focused = hasFocus
    }

    // The window's own volume handling runs only when the activity does not consume the key, so returning true here
    // keeps the alarm stream where it is.
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = volumeKeys.consumes(KeyEvent.ACTION_DOWN, keyCode, event.deviceId) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = volumeKeys.consumes(KeyEvent.ACTION_UP, keyCode, event.deviceId) || super.onKeyUp(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        timings.stage(WakeStage.WakeScreenCreated)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        // A restore entry point (Story 2.1): opened after a kill, the screen takes over the stored session and shows the
        // same step from memory (nothing when the engine already holds it).
        appScope.launch { engine.restore() }
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
            // Story 2.4: the lock state is part of the snooze availability, so an unlock re-renders the control in place.
            // The availability below is never remembered (the policy reads live inputs: the lock state now, the
            // catalogue and connectivity in Epic 4); keyed on this observed state, an unlock recomposes it.
            val unlocked by remember { userLock.observe() }.collectAsState(initial = userLock.isUserUnlocked())
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
            SessionAnswers(state, emergency != null)
            val zone = timeZones.current()
            val session = (state as? SessionState.Active)?.session
            val current =
                emergency?.let { alarmOnlyRingingUiState(it.alarmAt, zone) }
                    ?: session?.let { ringingUiState(it, key(unlocked) { snoozePolicy.availability(it) }, zone) }
                    ?: runtime.shownAlarmAt()?.let { alarmOnlyRingingUiState(it, zone) }
            // Once a session ends the screen keeps its last look until it closes, instead of flashing an empty surface.
            val last = remember { LastShown() }
            if (current != null) last.state = current
            WakeContent(state = current ?: last.state, onIntent = ::onIntent, onInteracted = ::interacted)
        }
    }

    /** What the screen sends by itself for [state]: the placeholder answer, and an "I'm up" kept from before the session. */
    @Composable
    private fun SessionAnswers(
        state: SessionState,
        emergencyRinging: Boolean,
    ) {
        // The Epic 1 check is a placeholder: answered once per session, ring and step (repeats are ignored).
        val placeholder = if (emergencyRinging) null else placeholderStepDue(state)
        LaunchedEffect(placeholder) { placeholder?.let(::answerPlaceholder) }
        // "I'm up" tapped while the screen waited for the session: replayed once the session (or emergency) rings.
        val ringing = state is SessionState.Ringing
        LaunchedEffect(pendingImUp, ringing, emergencyRinging) {
            if (pendingImUp && (ringing || emergencyRinging)) {
                pendingImUp = false
                onIntent(WakeIntent.ImUpClicked)
            }
        }
    }

    private fun onIntent(intent: WakeIntent) {
        when {
            intent != WakeIntent.ImUpClicked -> interacted()

            runtime.emergency.value != null -> runtime.stopEmergency()

            // No session yet (the screen opened just before it): the engine would ignore ImUpTapped, so keep the tap.
            engine.state.value == SessionState.Idle -> pendingImUp = true

            else -> send(SessionEvent.UserInteracted, SessionEvent.ImUpTapped)
        }
    }

    /**
     * Answers the placeholder [step] on [ApplicationScope]. A dispatch that fails (the session could not be committed)
     * is retried every [PLACEHOLDER_RETRY] while that step is still due: in Loud "I'm up" no longer helps, so a lost
     * answer would leave the alarm ringing until it is Missed.
     */
    private fun answerPlaceholder(step: PlaceholderStep) {
        appScope.launch {
            while (placeholderStepDue(engine.state.value) == step) {
                if (engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)) is Outcome.Success) return@launch
                delay(PLACEHOLDER_RETRY)
            }
        }
    }

    /** Any tap but "I'm up" (buying a snooze arrives in Epic 4): resets the interaction deadline of the session. */
    private fun interacted() {
        if (runtime.emergency.value == null) send(SessionEvent.UserInteracted)
    }

    private fun send(vararg events: SessionEvent) {
        appScope.launch { events.forEach { engine.dispatch(it) } }
    }

    companion object {
        /** How often a placeholder answer whose dispatch failed is sent again. */
        val PLACEHOLDER_RETRY: Duration = 2.seconds

        /** The intent of the ringing notification. */
        fun intent(context: Context): Intent =
            Intent(context, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

        /** A session the wake screen is for: ringing, quiet, loud or snoozed. */
        private fun SessionState.isRinging(): Boolean = this is SessionState.Ring || this is SessionState.Snoozed
    }
}

/** Shows the wake screen over the lock screen, turns the screen on and keeps it on. */
private fun ComponentActivity.showOverLockScreen() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    } else {
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
