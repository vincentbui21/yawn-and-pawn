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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.screen.forwardsToWakeScreen
import com.yawnandpawn.app.core.billing.BillingCountry
import com.yawnandpawn.app.core.billing.PurchaseCoordinator
import com.yawnandpawn.app.core.billing.SnoozeConditions
import com.yawnandpawn.app.core.billing.TaxNote
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.log.WakeStage
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.stats.CelebrationStreaks
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.components.LocalViewfinderFeed
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.FallbackPickerScreen
import com.yawnandpawn.app.ui.wake.FallbackPickerUiState
import com.yawnandpawn.app.ui.wake.LocalWakeClock
import com.yawnandpawn.app.ui.wake.PlaceholderStep
import com.yawnandpawn.app.ui.wake.PriceLookup
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.RingingUiState
import com.yawnandpawn.app.ui.wake.SnoozeSheet
import com.yawnandpawn.app.ui.wake.SnoozedScreen
import com.yawnandpawn.app.ui.wake.SnoozedUiState
import com.yawnandpawn.app.ui.wake.SuccessScreen
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.ui.wake.WakeMessage
import com.yawnandpawn.app.ui.wake.WakeSurface
import com.yawnandpawn.app.ui.wake.WallTime
import com.yawnandpawn.app.ui.wake.alarmOnlyRingingUiState
import com.yawnandpawn.app.ui.wake.checkPosition
import com.yawnandpawn.app.ui.wake.placeholderStepDue
import com.yawnandpawn.app.ui.wake.rememberWallTime
import com.yawnandpawn.app.ui.wake.ringingUiState
import com.yawnandpawn.app.ui.wake.snoozedUiState
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
import kotlin.time.Instant

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
 * [ringingUiState], with the snooze the [SnoozeAvailabilityPolicy] allows (Story 4.7: every reason, or "Snooze ·
 * {price}"). The availability follows the live env of [SnoozeConditions] (the [UserLockState], connectivity, the cached
 * prices; Stories 2.4 and 4.7), so an unlock, a connection or a price load re-renders the snooze control in place,
 * without finishing or recreating the screen. "I'm up" never depends on it. The emergency ring shows its own alarm
 * time. Opened just before the session starts (the service posts the ringing notification first), it shows the
 * notification's alarm time and waits.
 *
 * Every dispatch is launched on [ApplicationScope], outside composition and outside any engine effect:
 * - "I'm up" sends `UserInteracted`, then `ImUpTapped`. In the emergency ring it stops the ring instead. Tapped while
 *   the screen still waits for the session, it is kept and sent once the session (or an emergency ring) rings.
 * - Snooze sends `SnoozeTapped`; the engine's `ShowSnoozeConfirm` opens the confirm sheet over the Ringing or Check
 *   screen ([ConfirmSheetHost], Story 4.13), which pays with the live price, asks to unlock first on a locked phone and
 *   always closes back to the ring with no charge on "I'll get up", Back or a swipe.
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
 * Snoozed (Story 4.15), it shows "Snoozed. Next ring at {time}." for [SNOOZED_SCREEN_TIME], then stops keeping the
 * screen on and closes; the next ring opens a new wake screen through the ringing notification (Epic 2).
 *
 * Once a session it showed completes (the engine's [SessionEngine.ended], since its `state` may skip from Completed to
 * Idle), it shows the basic Success screen for that session instead (Story 3.3): UI-only state keyed by `sessionId`,
 * while the engine records the session and goes Idle in the background. Success closes on "Done", after
 * [SUCCESS_TIMEOUT], or when the screen is left (Home), so the next app open shows Home. A new ring replaces it. Any
 * other end (Missed, an emergency ring stopped) finishes the screen.
 *
 * The zero-snooze streak on Success (Story 6.4) comes from [CelebrationStreaks]: its one history read starts after the
 * first drawn frame of a real session's screen, on [ApplicationScope] and never awaited (NFR-7), and Success reads the
 * result synchronously, so no screen ever waits for it. Success clears `FLAG_KEEP_SCREEN_ON`; a new ring sets it again.
 *
 * While it is visible (`onStart` to `onStop`) the ringing notification is the quiet on-screen one, so no heads-up covers
 * the countdown; left while the alarm rings, it heads up again as the way back (Epic 3 device check, bug 2).
 *
 * "Bright wake screen" (Story 5.2, option C): while a session's Ringing screen shows (the confirm sheet over it too) and
 * the session's frozen config has it on, the window is at full brightness; every other screen, and the screen once
 * left, is at the phone's own level ([WakeBrightness]).
 */
@Suppress("TooManyFunctions") // One override per platform callback (lifecycle, keys, focus), each a few lines.
class WakeActivity : ComponentActivity() {
    private val engine: SessionEngine by inject()
    private val runtime: WakeRuntime by inject()
    private val appScope: ApplicationScope by inject()
    private val timeZones: TimeZoneProvider by inject()
    private val snoozePolicy: SnoozeAvailabilityPolicy by inject()
    private val snoozeConditions: SnoozeConditions by inject()
    private val timings: WakeTimings by inject()
    private val userLock: UserLockState by inject()
    private val unlockSignals: UnlockSignals by inject()
    private val purchases: PurchaseCoordinator by inject()
    private val monotonicClock: MonotonicClock by inject()

    /** The history snapshot behind the zero-snooze celebration (Story 6.4), read after the first frame. */
    private val celebrations: CelebrationStreaks by inject()

    /** The wall clock the Ringing screen's clock shows (Epic 4 device follow-up 1): the app's, so tests control it. */
    private val clock: Clock by inject()
    private val timeChanges: TimeChangeSignal by inject()

    /** The window's brightness (Story 5.2): full only on a ringing session's Ringing screen, while visible. */
    private val brightness =
        WakeBrightness(
            window = { window },
            extraDim = {
                // A failed read counts as Extra dim off and is logged at most once per process (Story 5.13 device fix).
                WakeBrightness.extraDimOn(contentResolver) { cause -> logger.log(LogEvent.OperationFailed(READ_EXTRA_DIM, cause)) }
            },
        )
    private val logger: Logger by inject()

    /** The snooze confirm sheet (Story 4.13); process-wide, so a recreated screen shows the same sheet. */
    private val sheet: ConfirmSheetHost by inject()
    private val billingCountry: BillingCountry by inject()

    /** The confirm sheet on screen now: "Pay" confirms the price the user saw. */
    private var shownSheet: SnoozeSheet? = null

    /** The Check screen's typed answer, grace clock and keys (Story 3.2). */
    private val check by lazy {
        val kept = ViewModelProvider(this, WakeKept.factory(get(), get(), get()))[WakeKept::class.java]
        WakeCheck(get(), get(), get(), get(), kept)
    }

    /** Keeps the events [send] dispatches in order. */
    private val dispatchOrder = Mutex()

    /** "I'm up" was tapped before the session existed; replayed once it rings. */
    private var pendingImUp by mutableStateOf(false)

    /**
     * The ring ([ringKey]) whose "I'm up", tapped before the session existed, was replayed: it stays at the phone's level
     * until that "I'm up" lands (Story 5.2, PR #80 review), so the screen never flashes bright in between.
     */
    private var imUpReplayedFor by mutableStateOf<String?>(null)

    /** "I'm up" was tapped before the session existed and has not landed yet on the ringing [state]. */
    private fun imUpWaiting(state: SessionState): Boolean =
        pendingImUp || (imUpReplayedFor != null && (state as? SessionState.Ringing)?.session?.let(::ringKey) == imUpReplayedFor)

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
        // Story 5.2: the level from the session now, not the one shown before the stop (it may have moved on meanwhile);
        // a ringing session's Ringing screen is what the composition shows for it (Success only after it ends).
        val state = engine.state.value
        brightness.show(full = end.success() == null && ringsBright(runtime.emergency.value != null, state, true, imUpWaiting(state)))
        brightness.onStart()
    }

    /** Left (Home, another app, the screen off): while the alarm rings, the notification heads up again as the way back. */
    override fun onStop() {
        // A recreate (rotation, dark mode, font scale) is not leaving: the new instance starts next (PR #41 review).
        runtime.wakeScreenShown(
            visible = false,
            ringing = forwardsToWakeScreen(engine.state.value, runtime.emergency.value),
            changingConfigurations = isChangingConfigurations,
            // Story 4.15 review: the Snoozed note closed just as the snooze ended, so the ring needs its full-screen intent.
            finishing = isFinishing,
        )
        // Story 5.2: Home, Recents, the screen off or finish: back to the phone's own brightness.
        brightness.onStop()
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
        // The confirm sheet sends no unlock result of its own (Story 4.13).
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
        // A restore entry point (Story 2.1): opened after a kill, it takes over the stored session (no-op once held).
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
            // Stories 2.4 and 4.7: the snooze availability follows the live env (the lock state, connectivity, the cached
            // prices, stranded payments), so an unlock, a connection or a price load re-renders the control in place.
            // The availability below is never remembered (the policy reads the env's latest value); keyed on this
            // observed env, every change recomposes it. Collecting it is also what keeps the reducer's view current.
            val env by remember { snoozeConditions.observe() }.collectAsState(initial = snoozeConditions.current())
            // The full-screen intent can open the screen just before the session starts (the service posts the ringing
            // notification first): it waits for a session or an emergency ring, and closes only once that is over.
            val active = state.isRinging() || emergency != null
            val ringingSessionId = (state as? SessionState.Active)?.takeIf { it.isRinging() }?.session?.sessionId
            // Recreated after a kill, the engine is Idle until it restores the stored session: the screen waits for that.
            val restored by engine.restored.collectAsState()
            // Opened by a full-screen intent whose service then stopped with nothing ringing: it closes (Story 5.13 device check).
            val serviceStopped by runtime.serviceStopped.collectAsState()
            LaunchedEffect(active, ringingSessionId, restored, serviceStopped) {
                end.follow(active, ringingSessionId, restored, serviceStopped)
            }
            SessionAnswers(state, emergency != null)
            // Once a session ends the screen keeps its last look until it closes, instead of flashing an empty surface.
            val last = remember { LastShown() }
            val success = end.success()
            if (success != null) {
                SuccessShown(success)
            } else {
                // Restored with a Success whose session this process no longer knows (it was killed): nothing to show.
                if (end.successSessionId != null) LaunchedEffect(Unit) { finish() }
                // The clock shows the time now, turning over at each minute (Epic 4 device follow-up 1).
                val time = rememberWallTime(clock, timeZones, timeChanges)
                val session = (state as? SessionState.Active)?.session?.takeIf { state.isRinging() }
                val availability = session?.let { key(env) { snoozePolicy.availability(it) } }
                // Story 4.13: the confirm sheet and its message over the session's Ringing or Check screen.
                val overlay = sheetOverlay(session, availability)
                // The notification's alarm time only stands in while the screen waits for its first session: once one
                // was shown, an ended session (Missed, or Completed before Success shows, with the notification still
                // posted) keeps its last screen, so a check never flips back to the Ringing screen (Story 3.2 review).
                val current =
                    emergency?.let { WakeScreen.Ringing(alarmOnlyRingingUiState(time.now, time.zone)) }
                        ?: sessionScreen(state, session, availability, time, check, overlay)
                        ?: runtime
                            .shownAlarmAt()
                            ?.takeIf { !last.sessionShown }
                            ?.let { WakeScreen.Ringing(alarmOnlyRingingUiState(time.now, time.zone)) }
                if (session != null) last.sessionShown = true
                CelebrationAndScreenOn(session, current)
                // Story 5.2: full brightness only on a ringing session's Ringing screen, as its frozen config says.
                val waiting = imUpWaiting(state)
                SideEffect { brightness.show(full = ringsBright(emergency != null, state, current is WakeScreen.Ringing, waiting)) }
                if (current != null) last.state = current
                val shown = current ?: last.state
                // Story 4.15: the Snoozed note shows for 3 s, then the screen closes; the next ring opens a new one.
                (current as? WakeScreen.Snoozed)?.let { SnoozedClose(it.key) }
                // The confirm sheet's 500 ms guard reads the monotonic clock (Story 4.13), never animation time.
                CompositionLocalProvider(LocalWakeClock provides monotonicClock::elapsedMillis) {
                    WakeScreenWithCamera(shown, check.qr, ::onIntent, ::screenTapped, send = ::sendAll)
                }
            }
        }
    }

    /**
     * Story 6.4 on a screen other than Success: the celebration's history read for the ringing real [session] (never a
     * test), and `FLAG_KEEP_SCREEN_ON` set again when a ring replaces Success ([current]; Snoozed clears it itself when
     * it closes, and a finishing screen is left alone).
     */
    @Composable
    private fun CelebrationAndScreenOn(
        session: SessionData?,
        current: WakeScreen?,
    ) {
        session?.takeIf { !it.config.testMode }?.let { CelebrationRead(it.sessionId) }
        SideEffect { if (current !is WakeScreen.Snoozed && !isFinishing) keepScreenOn(true) }
    }

    /**
     * Starts the celebration's history read for [sessionId] (Story 6.4) once this screen has drawn its first frame
     * (NFR-7): on the app scope, so it outlives the screen, and never awaited. [CelebrationStreaks] reads once per id.
     */
    @Composable
    private fun CelebrationRead(sessionId: String) {
        LaunchedEffect(sessionId) {
            // Memory only: a Success before the read starts shows "Up on time." (Pending), not the missing-snapshot text.
            celebrations.request(sessionId)
            // The first callback runs inside the first frame, before it is drawn; the second one is the next frame's.
            withFrameNanos { }
            withFrameNanos { }
            appScope.launch { celebrations.prepare(sessionId) }
        }
    }

    /**
     * The Success screen of the [success] session (Story 3.3), at the phone's own brightness (Story 5.2) and without
     * `FLAG_KEEP_SCREEN_ON`, so the system timeout applies (Story 6.4). It closes by itself [SUCCESS_TIMEOUT] after it
     * first showed, also when the screen was recreated meanwhile.
     *
     * A zero-snooze real session shows its streak (Story 6.4): decided once per session from the snapshot read after the
     * ring's first frame ([CelebrationStreaks]), and celebrated (count-up, confetti) only at its first showing.
     */
    @Composable
    private fun SuccessShown(success: SessionData) {
        SideEffect {
            brightness.show(full = false)
            keepScreenOn(false)
        }
        // Story 4.15 review: the pending note only while Play's latest list still holds this session's pending payment.
        val pendingSessions by purchases.pendingSessions.collectAsState()
        key(success.sessionId) {
            val streak = remember { celebrations.streakFor(success) }
            val celebrate = remember { end.claimCelebration(success.sessionId) }
            SuccessScreen(
                state =
                    successUiState(
                        success,
                        pendingStillListed = success.sessionId in pendingSessions,
                        streak = streak,
                        celebrate = celebrate,
                    ),
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
    }

    /**
     * The confirm sheet, its payment message and the prices to show over [session]'s screen with [availability] (Story
     * 4.13). A sheet that no longer belongs (another ring, snooze no longer Available for its offer) closes for good. The
     * payment message stays at least [MESSAGE_MINIMUM], then until the next tap (Story 4.14).
     */
    @Composable
    private fun sheetOverlay(
        session: SessionData?,
        availability: SnoozeAvailability?,
    ): SheetOverlay {
        val request by sheet.request.collectAsState()
        val livePrices by sheet.livePrices.collectAsState()
        val message by sheet.message.collectAsState()
        val sentPrices by sheet.sentPrices.collectAsState()
        val shown =
            if (session != null && availability != null) {
                sheet.sheet(session, availability, request, livePrices, sentPrices, TaxNote.shows(billingCountry.countryCode()))
            } else {
                null
            }
        SideEffect {
            shownSheet = shown
            if (session != null && availability != null) sheet.follow(session, availability)
        }
        val ringMessage = message?.takeIf { session != null && sheet.messageFor(session, it) != null }
        // Keyed on the showing, not on its tap state: a tap during the minimum must not restart it.
        LaunchedEffect(ringMessage?.serial) {
            ringMessage ?: return@LaunchedEffect
            delay(MESSAGE_MINIMUM)
            sheet.onMinimumShown(ringMessage)
        }
        return SheetOverlay(shown, ringMessage?.message, sheet.priceOf(livePrices))
    }

    /**
     * Closes the Snoozed note [key] after [SNOOZED_SCREEN_TIME] (Story 4.15): it stops keeping the screen on, so the system
     * turns it off (the app never forces it off), and finishes, only if that snooze still goes on. Leaving Snoozed first
     * (a merged alarm rings early) leaves composition and cancels it. The slot armed at the snooze end is the service's,
     * so nothing here can stop the next ring.
     */
    @Composable
    private fun SnoozedClose(key: SnoozedKey) {
        LaunchedEffect(key) {
            delay(SNOOZED_SCREEN_TIME)
            val still = (engine.state.value as? SessionState.Snoozed)?.session?.let { SnoozedKey.of(it) } == key
            if (still) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                finish()
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
                // Story 5.2: this ring stays at the phone's level until the replayed "I'm up" lands.
                imUpReplayedFor = (engine.state.value as? SessionState.Ringing)?.session?.let(::ringKey)
                pendingImUp = false
                onIntent(WakeIntent.ImUpClicked)
            }
        }
    }

    private fun onIntent(intent: WakeIntent) {
        // Any tap ends a payment message once it has shown for 10 s (Story 4.14); the tap's action goes on at once.
        sheet.onTap()
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

            // Story 4.13: snooze and the confirm sheet.
            intent in SHEET_INTENTS -> {
                onSheetIntent(intent)
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
                // A Pay still asking Play for its price is dropped: "I'm up" always wins.
                sheet.onImUp()
                send(SessionEvent.UserInteracted, SessionEvent.ImUpTapped)
            }
        }
    }

    /**
     * Snooze opens the confirm sheet through the engine (`SnoozeTapped` → `ShowSnoozeConfirm`); the sheet's upper
     * action pays or uses the earlier payment, and its dismiss path closes it with no charge (Story 4.13). Without a
     * ringing session (an emergency ring, a stale frame) a tap is only an interaction, and the sheet closes.
     */
    private fun onSheetIntent(intent: WakeIntent) {
        val session = (engine.state.value as? SessionState.Ring)?.session
        when {
            session == null || runtime.emergency.value != null -> {
                sheet.onImUp()
                interacted()
            }

            intent == WakeIntent.SnoozeClicked -> {
                send(SessionEvent.SnoozeTapped)
            }

            intent == WakeIntent.SheetUpperClicked -> {
                sheet.onUpper(session, snoozePolicy.availability(session), (shownSheet as? SnoozeSheet.Confirm)?.price, ::sendAll)
            }

            else -> {
                sheet.onDismiss(session, ::sendAll)
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

    /** A tap no action takes (the clock, the message, empty space): it counts for the payment message too (Story 4.14). */
    private fun screenTapped() {
        sheet.onTap()
        interacted()
    }

    /** Any tap but "I'm up" and the snooze actions: resets the interaction deadline of the session. */
    private fun interacted() {
        if (runtime.emergency.value == null) send(SessionEvent.UserInteracted)
    }

    /** [send] for a list. */
    private fun sendAll(events: List<SessionEvent>) = send(*events.toTypedArray())

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

        /** A payment message stays at least this long, then until the next tap (EXPERIENCE.md wake snackbar, Story 4.14). */
        val MESSAGE_MINIMUM: Duration = 10.seconds

        /** The operation logged when the system Extra dim setting cannot be read (Story 5.2). */
        const val READ_EXTRA_DIM = "read extra dim"

        /** How long the Snoozed note shows before the screen closes (EXPERIENCE.md State Patterns, Story 4.15). */
        val SNOOZED_SCREEN_TIME: Duration = 3.seconds

        /** The intents of snooze and the confirm sheet (Story 4.13). */
        private val SHEET_INTENTS: Set<WakeIntent> =
            setOf(WakeIntent.SnoozeClicked, WakeIntent.SheetUpperClicked, WakeIntent.SheetDismissed)

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
 * Sets or clears `FLAG_KEEP_SCREEN_ON` (Story 6.4: cleared while Success shows), only when it changes: called from a
 * `SideEffect` on every recomposition, it must not send the window manager an unchanged layout each time.
 */
private fun ComponentActivity.keepScreenOn(on: Boolean) {
    val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    val isOn = window.attributes.flags and flag != 0
    when {
        on && !isOn -> window.addFlags(flag)
        !on && isOn -> window.clearFlags(flag)
        else -> Unit
    }
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
 * The screen of a ringing or snoozed [session] (null otherwise): the Snoozed note while snoozed (Story 4.15); the
 * Fallback check picker while open (Story 3.9); in Grace or Loud on a check entry the Check screen
 * (Story 3.2), whichever way the wake screen was opened; else its Ringing screen.
 */
@Composable
private fun sessionScreen(
    state: SessionState,
    session: SessionData?,
    availability: SnoozeAvailability?,
    time: WallTime,
    check: WakeCheck,
    overlay: SheetOverlay,
): WakeScreen? {
    if (session == null || availability == null) return null
    return when (state) {
        is SessionState.Snoozed -> {
            snoozedUiState(session, time.zone)?.let { WakeScreen.Snoozed(it, SnoozedKey.of(session)) }
        }

        else -> {
            val checkScreen = check.screen(state, availability, overlay.priceOf)
            check.picker(state)?.let(WakeScreen::Fallback)
                ?: checkScreen?.let { WakeScreen.Check(it.copy(sheet = overlay.sheet, message = overlay.message)) }
                ?: WakeScreen.Ringing(
                    ringingUiState(session, availability, time.zone, time.now, overlay.priceOf)
                        .copy(sheet = overlay.sheet, message = overlay.message),
                )
        }
    }
}

/**
 * Whether the screen is at full brightness (Story 5.2, option C): only a session still in its Ringing step ([state],
 * before "I'm up") on its Ringing screen ([ringingScreen], the confirm sheet over it too) whose frozen config has "Bright
 * wake screen" on. Never the [emergency] ring or the wait before the session (no frozen config), nor any other screen;
 * after "I'm up" a session whose check has no screen of its own (a placeholder step) is at the phone's level too, and so
 * is a ring whose "I'm up" was tapped before the session existed ([imUpWaiting], until its replay lands; if that replay
 * cannot be saved, the ring stays at the phone's level, the safe side).
 */
private fun ringsBright(
    emergency: Boolean,
    state: SessionState,
    ringingScreen: Boolean,
    imUpWaiting: Boolean,
): Boolean = !emergency && !imUpWaiting && ringingScreen && state is SessionState.Ringing && state.session.config.brightWakeScreen

/** The confirm sheet and payment message over a wake screen, and the prices its snooze shows (Story 4.13). */
private class SheetOverlay(
    val sheet: SnoozeSheet?,
    val message: WakeMessage?,
    val priceOf: PriceLookup,
)

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

    /** "Snoozed. Next ring at {time}." for the snooze [key] (Story 4.15). */
    data class Snoozed(
        val state: SnoozedUiState,
        val key: SnoozedKey,
    ) : WakeScreen
}

/** One ring of [session]: its id and how many snoozes it has had, so a re-ring after a snooze is another ring (Story 5.2). */
private fun ringKey(session: SessionData): String = "${session.sessionId}#${session.snoozesGranted}"

/** One paid snooze of a session: its id and how many snoozes it has had ([SessionData.snoozesGranted]). */
private data class SnoozedKey(
    val sessionId: String,
    val snoozesGranted: Int,
) {
    companion object {
        fun of(session: SessionData): SnoozedKey = SnoozedKey(session.sessionId, session.snoozesGranted)
    }
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

        // No actions and no taps: a snooze has no interaction deadline, so a touch here sends nothing (Story 4.15).
        is WakeScreen.Snoozed -> SnoozedScreen(state = state.state, is24Hour = is24HourClock())
    }
}
