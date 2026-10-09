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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.screen.forwardsToWakeScreen
import com.yawnandpawn.app.core.billing.PurchaseCoordinator
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.WakeStage
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.components.LocalViewfinderFeed
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.FallbackPickerScreen
import com.yawnandpawn.app.ui.wake.FallbackPickerUiState
import com.yawnandpawn.app.ui.wake.PlaceholderStep
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.RingingUiState
import com.yawnandpawn.app.ui.wake.SuccessScreen
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.ui.wake.WakeSurface
import com.yawnandpawn.app.ui.wake.alarmOnlyRingingUiState
import com.yawnandpawn.app.ui.wake.checkPosition
import com.yawnandpawn.app.ui.wake.placeholderStepDue
import com.yawnandpawn.app.ui.wake.ringingUiState
import com.yawnandpawn.app.ui.wake.successUiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The wake screen over the lock screen (AD-5): the approved Ringing screen (`ui/wake/RingingScreen`, Story 1.15) and,
 * in Grace or Loud on a check entry (Math, Word Unscramble, Memory Sequence, QR/Barcode), the approved Check screen
 * (`ui/wake/CheckScreen`, Stories 3.2, 3.7, 3.8 and 3.10, [WakeCheck]).
 * Opened again from the notification or from "Back to alarm" after Home, it shows the current problem. While the
 * fallback is offered, "Can't do this check?" opens the Fallback check picker (Story 3.9, [WakeCheck]). The QR check's
 * camera runs only while the screen is resumed: pausing it (screen off, Home, the shade) releases the camera, and resuming
 * binds it again with the 5 s no-frame watchdog started over (Story 3.11, [WakeQr]).
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
 * - Number pad keys ([WakeCheck.onKey]): the typed digits live only on the screen; "Check" sends
 *   `CheckAnswerSubmitted(Number)` and the engine decides. A new problem or a wrong answer clears the field.
 * - Memory tiles ([WakeCheck.onTile], Story 3.8): on the user's turn each tap sends `CheckAnswerSubmitted(Tile)`; a
 *   wrong one makes the engine restart the round with a new sequence, which plays again. Answers keep their order.
 * - Word Unscramble tiles ([WakeCheck.onWordKey], Story 3.7): the letters live only on the screen; once every slot is
 *   filled the word is sent as `CheckAnswerSubmitted(Word)`. A wrong word clears the slots.
 * - Grace or Loud on a placeholder entry (only a session stored by Epics 1–2): the screen answers it
 *   (`CheckAnswerSubmitted(Placeholder)`), so "I'm up" alone ends that session; a failed dispatch is retried while due.
 *
 * Once a session it showed completes (the engine's [SessionEngine.ended], since its `state` may skip from Completed to
 * Idle), it shows the basic Success screen for that session instead (Story 3.3): UI-only state keyed by `sessionId`,
 * while the engine records the session and goes Idle in the background. Success closes on "Done", after
 * [SUCCESS_TIMEOUT], or when the screen is left (Home), so the next app open shows Home. A new ring replaces it. Any
 * other end (Missed, an emergency ring stopped) finishes the screen.
 *
 * While it is visible (`onStart` to `onStop`) the ringing notification is the quiet on-screen one, so no heads-up covers
 * the countdown; left while the alarm rings, it heads up again as the way back (Epic 3 device check, bug 2).
 */
@Suppress("TooManyFunctions") // One override per platform callback (lifecycle, keys, focus), each a few lines.
class WakeActivity : ComponentActivity() {
    private val engine: SessionEngine by inject()
    private val runtime: WakeRuntime by inject()
    private val appScope: ApplicationScope by inject()
    private val timeZones: TimeZoneProvider by inject()
    private val snoozePolicy: SnoozeAvailabilityPolicy by inject()
    private val timings: WakeTimings by inject()
    private val userLock: UserLockState by inject()
    private val unlockSignals: UnlockSignals by inject()
    private val purchases: PurchaseCoordinator by inject()
    private val monotonicClock: MonotonicClock by inject()

    /** The Check screen's typed answer, grace clock and keys (Story 3.2). */
    private val check by lazy {
        val kept = ViewModelProvider(this, WakeKept.factory(get(), get(), get()))[WakeKept::class.java]
        WakeCheck(get(), get(), get(), get(), kept)
    }

    /** Keeps the events [send] dispatches in order. */
    private val dispatchOrder = Mutex()

    /** "I'm up" was tapped before the session existed; replayed once it rings. */
    private var pendingImUp by mutableStateOf(false)

    /** When the screen shows Success and when it closes (Story 3.3); made in [onCreate]. */
    private lateinit var end: WakeScreenEnd

    /**
     * The volume keys do nothing while this screen is in front with focus and something rings (Story 2.8); the
     * accessibility shortcut passes.
     */
    internal val volumeKeys = VolumeKeyGate(ringing = { forwardsToWakeScreen(engine.state.value, runtime.emergency.value) })

    /** Visible: the ringing notification goes quiet, so no heads-up covers the countdown (Epic 3 device check, bug 2). */
    override fun onStart() {
        super.onStart()
        runtime.wakeScreenShown(visible = true, ringing = forwardsToWakeScreen(engine.state.value, runtime.emergency.value))
    }

    /** Left (Home, another app, the screen off): while the alarm rings, the notification heads up again as the way back. */
    override fun onStop() {
        // A recreate (rotation, dark mode, font scale) is not leaving: the new instance starts next (PR #41 review).
        runtime.wakeScreenShown(
            visible = false,
            ringing = forwardsToWakeScreen(engine.state.value, runtime.emergency.value),
            changingConfigurations = isChangingConfigurations,
        )
        super.onStop()
    }

    /**
     * Resumed with the user unlocked is an unlock signal (Story 2.4), for example back from the PIN prompt of
     * `requestDismissKeyguard`, or after an unlock while another screen was in front. The screen itself stays.
     */
    override fun onResume() {
        super.onResume()
        volumeKeys.resumed = true
        if (userLock.isUserUnlocked()) unlockSignals.onScreenResumedUnlocked()
        // Story 4.11: back from Play's sheet or the PIN prompt (or opened after a kill): an unlock whose callback was lost
        // is settled by the keyguard, and a recovery query finds a payment whose result never came. Launched, never awaited.
        purchases.onWakeScreenResumed()
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
        end = WakeScreenEnd(this, ended = { engine.ended.value }, elapsedMillis = monotonicClock::elapsedMillis)
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
            val ringingSessionId = (state as? SessionState.Active)?.takeIf { it.isRinging() }?.session?.sessionId
            // Recreated after a kill, the engine is Idle until it restores the stored session: the screen waits for that.
            val restored by engine.restored.collectAsState()
            LaunchedEffect(active, ringingSessionId, restored) { end.follow(active, ringingSessionId, restored) }
            SessionAnswers(state, emergency != null)
            // Once a session ends the screen keeps its last look until it closes, instead of flashing an empty surface.
            val last = remember { LastShown() }
            val success = end.success()
            if (success != null) {
                key(success.sessionId) {
                    SuccessScreen(
                        state = successUiState(success),
                        onIntent = ::onIntent,
                        basic = true,
                        claimHaptic = { end.claimHaptic(success.sessionId) },
                    )
                    // Counted from when Success started, so a recreated screen still closes 60 s after it first showed.
                    LaunchedEffect(Unit) {
                        delay(end.successRemaining(SUCCESS_TIMEOUT))
                        finish()
                    }
                }
            } else {
                // Restored with a Success whose session this process no longer knows (it was killed): nothing to show.
                if (end.successSessionId != null) LaunchedEffect(Unit) { finish() }
                val zone = timeZones.current()
                val session = (state as? SessionState.Active)?.session?.takeIf { state.isRinging() }
                val availability = session?.let { key(unlocked) { snoozePolicy.availability(it) } }
                // The notification's alarm time only stands in while the screen waits for its first session: once one
                // was shown, an ended session (Missed, or Completed before Success shows, with the notification still
                // posted) keeps its last screen, so a check never flips back to the Ringing screen (Story 3.2 review).
                val current =
                    emergency?.let { WakeScreen.Ringing(alarmOnlyRingingUiState(it.alarmAt, zone)) }
                        ?: sessionScreen(state, session, availability, zone, check)
                        ?: runtime
                            .shownAlarmAt()
                            ?.takeIf { !last.sessionShown }
                            ?.let { WakeScreen.Ringing(alarmOnlyRingingUiState(it, zone)) }
                if (session != null) last.sessionShown = true
                if (current != null) last.state = current
                val shown = current ?: last.state
                WakeScreenWithCamera(shown, check.qr, ::onIntent, ::interacted, send = { send(*it.toTypedArray()) })
            }
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
            // "Done" on Success: the session is already over, so it only closes the screen.
            intent == WakeIntent.DoneClicked -> {
                finish()
            }

            WakeCheck.isKey(intent) -> {
                check.onKey(intent, checkPosition(engine.state.value), send = { send(*it.toTypedArray()) }, interacted = ::interacted)
            }

            // Story 3.9: the fallback link, a fallback check card, or "Back to check".
            WakeCheck.isFallback(intent) -> {
                check.onFallback(intent, engine.state.value, send = { send(*it.toTypedArray()) }, interacted = ::interacted)
            }

            // Story 3.8: a Memory Sequence tile.
            intent is WakeIntent.TileTapped -> {
                check.onTile(intent.tile, engine.state.value, send = { send(*it.toTypedArray()) }, interacted = ::interacted)
            }

            // Story 3.7: a Word Unscramble letter, slot, "Shuffle" or "Clear".
            WakeCheck.isWordKey(intent) -> {
                check.onWordKey(intent, engine.state.value, send = { send(*it.toTypedArray()) }, interacted = ::interacted)
            }

            // Story 3.10: the QR viewfinder's torch.
            intent == WakeIntent.TorchToggled -> {
                check.qr.toggleTorch()
                interacted()
            }

            intent != WakeIntent.ImUpClicked -> {
                interacted()
            }

            runtime.emergency.value != null -> {
                runtime.stopEmergency()
            }

            // No session yet (the screen opened just before it): the engine would ignore ImUpTapped, so keep the tap.
            engine.state.value == SessionState.Idle -> {
                pendingImUp = true
            }

            else -> {
                send(SessionEvent.UserInteracted, SessionEvent.ImUpTapped)
            }
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

    /**
     * Sends [events] in the order the taps came: quick taps (Memory tiles, Story 3.8) must reach the engine one after the
     * other. Each send starts on the caller's thread and queues on [dispatchOrder], which is fair (first come, first
     * served).
     */
    private fun send(vararg events: SessionEvent) {
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            dispatchOrder.withLock {
                // The order is fixed once the lock is taken; the dispatch itself runs on the scope's thread, not the main one.
                yield()
                events.forEach { engine.dispatch(it) }
            }
        }
    }

    companion object {
        /** How often a placeholder answer whose dispatch failed is sent again. */
        val PLACEHOLDER_RETRY: Duration = 2.seconds

        /** Success closes by itself after this long without "Done" (owner-approved default 2026-09-26). */
        val SUCCESS_TIMEOUT: Duration = 60.seconds

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

/**
 * The last screen shown, and whether it ever showed a session ([sessionShown]); not snapshot state, so keeping it never
 * recomposes.
 */
private class LastShown {
    var state: WakeScreen? = null
    var sessionShown: Boolean = false
}

/**
 * The screen of a ringing or snoozed [session] (null otherwise): the Fallback check picker while open (Story 3.9); in
 * Grace or Loud on a check entry the Check screen
 * (Story 3.2), whichever way the wake screen was opened; else its Ringing screen.
 */
@Composable
private fun sessionScreen(
    state: SessionState,
    session: SessionData?,
    availability: SnoozeAvailability?,
    zone: TimeZone,
    check: WakeCheck,
): WakeScreen? {
    if (session == null || availability == null) return null
    val checkScreen = check.screen(state, availability)
    return check.picker(state)?.let(WakeScreen::Fallback)
        ?: checkScreen?.let(WakeScreen::Check)
        ?: WakeScreen.Ringing(ringingUiState(session, availability, zone))
}

/**
 * What the wake screen shows: the Ringing screen, the Check screen of a Math entry (Story 3.2), or the Fallback check
 * picker (Story 3.9).
 */
private sealed interface WakeScreen {
    data class Ringing(
        val state: RingingUiState,
    ) : WakeScreen

    data class Check(
        val state: CheckUiState,
    ) : WakeScreen

    data class Fallback(
        val state: FallbackPickerUiState,
    ) : WakeScreen
}

/**
 * [WakeContent] for [shown], with the QR check's camera ([qr], Stories 3.10 and 3.11). The viewfinder shows the camera's
 * picture, one for as long as the camera shows (3.10 review: the grace countdown's redraws must not rebuild it). The
 * camera itself runs next to the screen while it is resumed on a QR check with the permission, also under the
 * camera-unavailable message, so a camera that comes back is noticed. Paused (screen off, Home, the shade) it is
 * released. Each stable code is sent as an answer through [send].
 */
@Composable
private fun WakeScreenWithCamera(
    shown: WakeScreen?,
    qr: WakeQr,
    onIntent: (WakeIntent) -> Unit,
    onInteracted: () -> Unit,
    send: (List<SessionEvent>) -> Unit,
) {
    val content = (shown as? WakeScreen.Check)?.state?.content
    val camera = qr.showsCamera(content)
    val feed = remember(camera) { if (camera) qr.preview() else null }
    CompositionLocalProvider(LocalViewfinderFeed provides feed) {
        WakeContent(state = shown, onIntent = onIntent, onInteracted = onInteracted)
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (content is CheckContent.QrBarcode && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && qr.permitted()) {
        qr.Scan(send)
    }
}

/**
 * The Ringing or Check screen for [state], or the plain Sunrise wake surface while there is nothing to show yet. A tap
 * that no action takes (the clock, the disabled snooze, empty space) is [onInteracted].
 */
@Composable
private fun WakeContent(
    state: WakeScreen?,
    onIntent: (WakeIntent) -> Unit,
    onInteracted: () -> Unit,
) {
    val taps = Modifier.pointerInput(Unit) { detectTapGestures { onInteracted() } }
    when (state) {
        null -> WakeSurface(modifier = taps) {}
        is WakeScreen.Ringing -> RingingScreen(state = state.state, is24Hour = is24HourClock(), onIntent = onIntent, modifier = taps)
        is WakeScreen.Check -> CheckScreen(state = state.state, onIntent = onIntent, modifier = taps)
        is WakeScreen.Fallback -> FallbackPickerScreen(state = state.state, onIntent = onIntent, modifier = taps)
    }
}
