package com.yawnandpawn.app.android.wake

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import com.yawnandpawn.app.core.checks.AccessibilityState
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.session.FallbackPolicy
import com.yawnandpawn.app.core.session.FallbackReason
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.time.BootCounter
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.ui.wake.CheckInput
import com.yawnandpawn.app.ui.wake.CheckPosition
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.FallbackPickerUiState
import com.yawnandpawn.app.ui.wake.MemoryInput
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.ui.wake.WordAnswer
import com.yawnandpawn.app.ui.wake.checkPosition
import com.yawnandpawn.app.ui.wake.coreCheckType
import com.yawnandpawn.app.ui.wake.fallbackPickerUiState
import com.yawnandpawn.app.ui.wake.mathCheckUiState
import com.yawnandpawn.app.ui.wake.memoryCheckUiState
import com.yawnandpawn.app.ui.wake.memoryRound
import com.yawnandpawn.app.ui.wake.wordCheckUiState
import com.yawnandpawn.app.ui.wake.wordRound
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Check screen part of [WakeActivity] (Story 3.2): the digits typed so far (UI only, so a recreated screen starts
 * with an empty field), the grace countdown's clock and the number pad keys; and the Memory Sequence round with its
 * playback and tiles (Story 3.8). The engine decides every answer; the screen follows its next state (a new problem or
 * round, or more failed attempts for a wrong answer).
 *
 * The fallback (Story 3.9): "Can't do this check?" shows while the [fallbackPolicy] offers it and the fallback was not
 * used; the link opens the Fallback check picker, a card sends `FallbackRequested(type, reason)`, and "Back to check"
 * closes it without using the fallback. The picker closes by itself once the fallback is no longer offered. With
 * TalkBack on ([accessibility]) a picked Memory Sequence is its numbered variant, as a frozen plan would hold it.
 */
internal class WakeCheck(
    private val clock: Clock,
    private val monotonicClock: MonotonicClock,
    private val bootCounter: BootCounter,
    fallbackPolicy: FallbackPolicy,
    accessibility: AccessibilityState,
) {
    private val fallback = WakeFallback(fallbackPolicy, accessibility, FALLBACK_REASON)

    private var input by mutableStateOf(CheckInput())

    /** The Memory Sequence round on screen and its playback (Story 3.8). */
    private var memory by mutableStateOf(MemoryInput())

    /** The Word Unscramble item on screen and the letters placed (Story 3.7). */
    private var word by mutableStateOf(WordAnswer())

    /**
     * The Check screen for [state] with [availability] for its snooze, or null when [state] waits on no Math or Memory
     * Sequence entry.
     */
    @Composable
    fun screen(
        state: SessionState,
        availability: SnoozeAvailability,
    ): CheckUiState? {
        val now by graceClock(running = state is SessionState.Grace)
        val position = checkPosition(state)
        val shown = inputAt(position)
        val shownMemory = memoryAt(state)
        val shownWord = wordAt(state)
        SideEffect {
            input = shown
            memory = shownMemory
            word = shownWord
        }
        // The sequence plays by itself: each step after its delay (350 ms lit, 150 ms gap), then the brief tap light.
        val playback = shownMemory.playback
        LaunchedEffect(playback) {
            val wait = playback?.nextTick ?: return@LaunchedEffect
            delay(wait)
            if (memory.playback == playback) memory = memory.ticked()
        }
        val offered = fallback.offered(state)
        // A picker left open when the fallback stops being offered is closed for good, so it never pops up uninvited later
        // (for example on the next ring, once the fallback is offered again).
        SideEffect { if (!offered) fallback.pickerOpen = false }
        return (
            mathCheckUiState(state, availability, now, shown)
                ?: memoryCheckUiState(state, availability, now, shownMemory)
                ?: wordCheckUiState(state, availability, now, shownWord)
        )?.copy(showFallbackLink = offered)
    }

    /** The Fallback check picker while it is open and the fallback is still offered for [state]; else null. */
    fun picker(state: SessionState): FallbackPickerUiState? =
        if (fallback.pickerOpen &&
            fallback.offered(state)
        ) {
            fallbackPickerUiState()
        } else {
            null
        }

    /**
     * The fallback intents: the link opens the picker while the fallback is offered for the current [state] (a tap from
     * a stale frame does not), a card asks for that check ([send]), the close button goes back to the check. Each is
     * also a user interaction ([interacted]).
     */
    fun onFallback(
        intent: WakeIntent,
        state: SessionState,
        send: (List<SessionEvent>) -> Unit,
        interacted: () -> Unit,
    ) {
        val chosen = (intent as? WakeIntent.FallbackChosen)?.let { coreCheckType(it.type) }?.let(fallback::asFrozen)
        fallback.pickerOpen = intent == WakeIntent.FallbackLinkClicked && fallback.offered(state)
        if (chosen != null) {
            send(listOf(SessionEvent.UserInteracted, SessionEvent.FallbackRequested(chosen, FALLBACK_REASON)))
        } else {
            interacted()
        }
    }

    /** The Word Unscramble item and its letters as the screen shows them for the engine's [state]. */
    internal fun wordAt(state: SessionState): WordAnswer = word.following(checkPosition(state), wordRound(state))

    /**
     * A Word Unscramble tile, "Shuffle" or "Clear" on the engine's [state] (Story 3.7 review): the letters change on
     * screen only, and once every slot is filled the word is sent as `UserInteracted` + `CheckAnswerSubmitted(Word)`;
     * the engine decides (a wrong word clears the slots). Any other tap only counts as interaction, through [interacted].
     */
    fun onWordKey(
        intent: WakeIntent,
        state: SessionState,
        send: (List<SessionEvent>) -> Unit,
        interacted: () -> Unit,
    ) {
        val current = wordAt(state)
        val edited = current.edited(intent)
        word = edited ?: current
        val answer = edited?.answer
        if (answer == null) {
            interacted()
        } else {
            send(listOf(SessionEvent.UserInteracted, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Word(answer))))
        }
    }

    /** The typed answer as the screen shows it at the engine's [position]. */
    internal fun inputAt(position: CheckPosition?): CheckInput = input.following(position)

    /** The Memory round and playback as the screen shows them for the engine's [state]. */
    internal fun memoryAt(state: SessionState): MemoryInput = memory.following(checkPosition(state), memoryRound(state))

    /**
     * A `memory-tile` tapped on the engine's [state] (Story 3.8): on the user's turn it lights briefly and is sent as
     * `UserInteracted` + `CheckAnswerSubmitted(Tile)`; the engine decides (a wrong tap restarts the round with a new
     * sequence, which [screen] then plays). While the sequence plays a tap only counts as interaction, through
     * [interacted].
     */
    fun onTile(
        tile: Int,
        state: SessionState,
        send: (List<SessionEvent>) -> Unit,
        interacted: () -> Unit,
    ) {
        val current = memoryAt(state)
        val tapped = current.tapped(tile)
        memory = tapped ?: current
        if (tapped == null) {
            interacted()
        } else {
            send(listOf(SessionEvent.UserInteracted, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Tile(tile))))
        }
    }

    /**
     * A `number-pad-key` at the engine's current [position]: a digit or backspace edits the typed answer (at most 5
     * digits); "Check" submits it as `CheckAnswerSubmitted` and clears the field, and does nothing while the field is
     * empty. Every key also sends `UserInteracted`, through [send] or [interacted].
     *
     * The input moves to [position] first, so a key tapped after the engine moved on, before the screen recomposed, is
     * typed into the new problem rather than dropped (Story 3.2 review). The field is cleared on submit, so a second
     * "Check" before the next problem shows sends nothing instead of the same digits against it.
     */
    fun onKey(
        intent: WakeIntent,
        position: CheckPosition?,
        send: (List<SessionEvent>) -> Unit,
        interacted: () -> Unit,
    ) {
        val typed = inputAt(position)
        input =
            when (intent) {
                is WakeIntent.DigitTapped -> typed.typed(intent.digit)
                WakeIntent.DeleteDigit -> typed.deleted()
                WakeIntent.SubmitAnswer -> typed.copy(digits = "")
                else -> typed
            }
        if (intent == WakeIntent.SubmitAnswer && typed.digits.isNotEmpty()) {
            send(listOf(SessionEvent.UserInteracted, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Number(typed.digits))))
        } else {
            interacted()
        }
    }

    /** "Now" on the time ports, read again every [GRACE_TICK] while [running] (the grace countdown), else once. */
    @Composable
    private fun graceClock(running: Boolean): State<TimeSnapshot> =
        produceState(initialValue = now(), running) {
            value = now()
            while (running) {
                delay(GRACE_TICK)
                value = now()
            }
        }

    private fun now(): TimeSnapshot = TimeSnapshot.of(clock, monotonicClock, bootCounter)

    companion object {
        /** How often the grace countdown reads the clock; the seconds shown round up, so a quarter second is exact enough. */
        val GRACE_TICK: Duration = 250.milliseconds

        /**
         * Why the fallback is offered: until the camera check reports its camera state (Stories 3.10 and 3.11), only after
         * 5 failed attempts.
         */
        val FALLBACK_REASON: FallbackReason = FallbackReason.FailedAttempts

        /** The fallback intents this class handles. */
        fun isFallback(intent: WakeIntent): Boolean =
            intent == WakeIntent.FallbackLinkClicked || intent is WakeIntent.FallbackChosen || intent == WakeIntent.FallbackPickerClosed

        /** The number pad keys this class handles. */
        fun isKey(intent: WakeIntent): Boolean =
            intent is WakeIntent.DigitTapped || intent == WakeIntent.DeleteDigit || intent == WakeIntent.SubmitAnswer

        /** The Word Unscramble taps [onWordKey] handles: a letter, a slot, "Shuffle" or "Clear". */
        fun isWordKey(intent: WakeIntent): Boolean =
            intent is WakeIntent.LetterTapped ||
                intent is WakeIntent.SlotTapped ||
                intent == WakeIntent.ShuffleLetters ||
                intent == WakeIntent.ClearLetters
    }
}
