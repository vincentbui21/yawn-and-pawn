package com.yawnandpawn.app.android.wake

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.time.BootCounter
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.ui.wake.CheckInput
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.ui.wake.checkPosition
import com.yawnandpawn.app.ui.wake.mathCheckUiState
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Check screen part of [WakeActivity] (Story 3.2): the digits typed so far (UI only, so a recreated screen starts
 * with an empty field), the grace countdown's clock and the number pad keys. The engine decides every answer; the
 * screen follows its next state (a new problem, or more failed attempts for a wrong answer).
 */
internal class WakeCheck(
    private val clock: Clock,
    private val monotonicClock: MonotonicClock,
    private val bootCounter: BootCounter,
) {
    private var input by mutableStateOf(CheckInput())

    /** The Check screen for [state] with [availability] for its snooze, or null when [state] waits on no Math entry. */
    @Composable
    fun screen(
        state: SessionState,
        availability: SnoozeAvailability,
    ): CheckUiState? {
        val now by graceClock(running = state is SessionState.Grace)
        val shown = input.following(checkPosition(state))
        SideEffect { input = shown }
        return mathCheckUiState(state, availability, now, shown)
    }

    /**
     * A `number-pad-key`: a digit or backspace edits the typed answer (at most 5 digits); "Check" submits it as
     * `CheckAnswerSubmitted`, and does nothing while the field is empty. Every key also sends `UserInteracted`, through
     * [send] or [interacted].
     */
    fun onKey(
        intent: WakeIntent,
        send: (List<SessionEvent>) -> Unit,
        interacted: () -> Unit,
    ) {
        val typed = input
        when (intent) {
            is WakeIntent.DigitTapped -> input = typed.typed(intent.digit)
            WakeIntent.DeleteDigit -> input = typed.deleted()
            else -> Unit
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

        /** The number pad keys this class handles. */
        fun isKey(intent: WakeIntent): Boolean =
            intent is WakeIntent.DigitTapped || intent == WakeIntent.DeleteDigit || intent == WakeIntent.SubmitAnswer
    }
}
