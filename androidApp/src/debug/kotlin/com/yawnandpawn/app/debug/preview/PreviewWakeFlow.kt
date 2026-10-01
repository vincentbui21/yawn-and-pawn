package com.yawnandpawn.app.debug.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.FallbackPickerScreen
import com.yawnandpawn.app.ui.wake.GraceState
import com.yawnandpawn.app.ui.wake.MathOperator
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.RingingUiState
import com.yawnandpawn.app.ui.wake.SessionLine
import com.yawnandpawn.app.ui.wake.SnoozeOffer
import com.yawnandpawn.app.ui.wake.SnoozeSheet
import com.yawnandpawn.app.ui.wake.SnoozedScreen
import com.yawnandpawn.app.ui.wake.SnoozedUiState
import com.yawnandpawn.app.ui.wake.SuccessKind
import com.yawnandpawn.app.ui.wake.SuccessScreen
import com.yawnandpawn.app.ui.wake.SuccessUiState
import com.yawnandpawn.app.ui.wake.WakeIntent
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalTime

/** Where the fake wake session is. */
sealed interface WakeStep {
    data class Ringing(
        val state: RingingUiState,
    ) : WakeStep

    data class Check(
        val state: CheckUiState,
    ) : WakeStep

    data object Fallback : WakeStep

    data class Success(
        val state: SuccessUiState,
    ) : WakeStep

    data class Snoozed(
        val state: SnoozedUiState,
    ) : WakeStep
}

/**
 * The fake wake session of the tap-through: Ringing, "I'm up", a Math check (47 + 38, then 6 x 7) with a fixed grace
 * ring, Success. Snooze opens the confirm sheet; "Pay" shows Snoozed, and the alarm re-rings with the next price.
 * Nothing rings, times or charges.
 */
class PreviewWakeFlow {
    var step: WakeStep by mutableStateOf(WakeStep.Ringing(PreviewSamples.ringingFirst))
        private set
    private var test = false
    private var snoozes = 0
    private var ringTime = RING_START

    fun start(test: Boolean) {
        this.test = test
        snoozes = 0
        ringTime = RING_START
        reRing()
    }

    fun reRing() {
        step =
            WakeStep.Ringing(
                PreviewSamples.ringingFirst.copy(
                    time = ringTime,
                    label = if (test) null else PreviewSamples.ringingFirst.label,
                    snooze = offer(),
                    sessionLine = if (snoozes > 0) SessionLine(snoozes, MAX_SNOOZES, paid()) else null,
                ),
            )
    }

    fun onIntent(
        intent: WakeIntent,
        onFinished: () -> Unit,
    ) {
        when (val current = step) {
            is WakeStep.Ringing -> onRinging(current.state, intent)
            is WakeStep.Check -> onCheck(current.state, intent)
            WakeStep.Fallback -> if (intent is WakeIntent.FallbackChosen || intent == WakeIntent.FallbackPickerClosed) step = check()
            is WakeStep.Success -> if (intent == WakeIntent.DoneClicked) onFinished()
            is WakeStep.Snoozed -> Unit
        }
    }

    private fun offer(): SnoozeOffer = if (test) SnoozeOffer.TestMode else SnoozeOffer.Available(PreviewSamples.price(snoozes + 1))

    private fun paid() = PreviewSamples.price((1..snoozes).sum())

    private fun check() =
        WakeStep.Check(
            CheckUiState(
                grace = GraceState.Running(secondsLeft = GRACE_SECONDS, totalSeconds = GRACE_SECONDS),
                content = CheckContent.Math(problemNumber = 1, problemCount = 2, left = 47, right = 38, operator = MathOperator.Plus),
                snooze = offer(),
            ),
        )

    private fun sheet() =
        SnoozeSheet.Confirm(
            minutes = SNOOZE_MINUTES,
            price = PreviewSamples.price(snoozes + 1),
            nextPrice = if (snoozes + 1 < MAX_SNOOZES) PreviewSamples.price(snoozes + 2) else null,
        )

    private fun onRinging(
        state: RingingUiState,
        intent: WakeIntent,
    ) {
        step =
            when (intent) {
                WakeIntent.ImUpClicked -> {
                    check()
                }

                WakeIntent.SnoozeClicked -> {
                    if (state.snooze is SnoozeOffer.Available) {
                        WakeStep.Ringing(
                            state.copy(sheet = sheet()),
                        )
                    } else {
                        step
                    }
                }

                WakeIntent.SheetDismissed -> {
                    WakeStep.Ringing(state.copy(sheet = null))
                }

                WakeIntent.SheetUpperClicked -> {
                    snooze()
                }

                else -> {
                    step
                }
            }
    }

    private fun snooze(): WakeStep {
        snoozes++
        val minutes = ringTime.hour * MINUTES_PER_HOUR + ringTime.minute + SNOOZE_MINUTES
        ringTime = LocalTime((minutes / MINUTES_PER_HOUR) % HOURS_PER_DAY, minutes % MINUTES_PER_HOUR)
        return WakeStep.Snoozed(SnoozedUiState(nextRingAt = ringTime))
    }

    private fun onCheck(
        state: CheckUiState,
        intent: WakeIntent,
    ) {
        step =
            when (intent) {
                WakeIntent.SnoozeClicked -> if (state.snooze is SnoozeOffer.Available) WakeStep.Check(state.copy(sheet = sheet())) else step
                WakeIntent.SheetDismissed -> WakeStep.Check(state.copy(sheet = null))
                WakeIntent.SheetUpperClicked -> snooze()
                WakeIntent.FallbackLinkClicked -> WakeStep.Fallback
                else -> mathStep(state, intent) ?: success()
            }
    }

    private fun success(): WakeStep {
        val kind =
            when {
                test -> SuccessKind.Test
                snoozes == 0 -> SuccessKind.OnTime(streakDays = STREAK)
                else -> SuccessKind.AfterSnooze(paidThisMorning = paid())
            }
        return WakeStep.Success(SuccessUiState(kind))
    }
}

/** The Math check's own inputs; `null` when the last problem was solved (the check is done). */
private fun mathStep(
    state: CheckUiState,
    intent: WakeIntent,
): WakeStep? {
    val math = state.content as? CheckContent.Math ?: return WakeStep.Check(state)
    val next =
        when (intent) {
            is WakeIntent.DigitTapped -> math.copy(answer = (math.answer + intent.digit).take(MAX_DIGITS), wrong = false)
            WakeIntent.DeleteDigit -> math.copy(answer = math.answer.dropLast(1))
            WakeIntent.SubmitAnswer -> submitted(math)
            else -> math
        }
    return next?.let { WakeStep.Check(state.copy(content = it)) }
}

/** A wrong answer clears the field; a right one moves to problem 2, or ends the check (`null`). */
private fun submitted(math: CheckContent.Math): CheckContent.Math? {
    val expected = if (math.operator == MathOperator.Plus) math.left + math.right else math.left * math.right
    return when {
        math.answer.toIntOrNull() != expected -> {
            math.copy(answer = "", wrong = true)
        }

        math.problemNumber < math.problemCount -> {
            math.copy(
                problemNumber = 2,
                left = 6,
                right = 7,
                operator = MathOperator.Times,
                answer = "",
            )
        }

        else -> {
            null
        }
    }
}

/** The wake screens of [flow]; Snoozed stays 3 s, then the alarm rings again. */
@Composable
fun PreviewWakeScreens(
    flow: PreviewWakeFlow,
    is24Hour: Boolean,
    onFinished: () -> Unit,
) {
    val onIntent: (WakeIntent) -> Unit = { flow.onIntent(it, onFinished) }
    when (val step = flow.step) {
        is WakeStep.Ringing -> {
            RingingScreen(state = step.state, is24Hour = is24Hour, onIntent = onIntent)
        }

        is WakeStep.Check -> {
            CheckScreen(state = step.state, onIntent = onIntent)
        }

        WakeStep.Fallback -> {
            FallbackPickerScreen(state = PreviewSamples.fallbackPicker, onIntent = onIntent)
        }

        is WakeStep.Success -> {
            SuccessScreen(state = step.state, onIntent = onIntent)
        }

        is WakeStep.Snoozed -> {
            SnoozedScreen(state = step.state, is24Hour = is24Hour)
            LaunchedEffect(step) {
                delay(SNOOZED_SHOWN_MILLIS)
                flow.reRing()
            }
        }
    }
}

private val RING_START = LocalTime(hour = 7, minute = 30)
private const val MAX_SNOOZES = 5
private const val SNOOZE_MINUTES = 9
private const val GRACE_SECONDS = 20
private const val STREAK = 12
private const val MAX_DIGITS = 4
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val SNOOZED_SHOWN_MILLIS = 3_000L
