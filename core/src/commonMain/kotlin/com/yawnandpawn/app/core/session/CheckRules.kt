package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.session.SessionState.Ring
import com.yawnandpawn.app.core.session.SessionState.Ringing
import com.yawnandpawn.app.core.time.TimeSnapshot

/**
 * AD-2 check rows from Grace and Loud (the check starts with "I'm up", so Ringing has none). A null result means no
 * row matches.
 */
internal class CheckRules(
    private val validator: CheckValidator,
    private val fallbackPolicy: FallbackPolicy,
) {
    /** Grace / Loud + CheckAnswerSubmitted: advance, count a wrong answer, or complete on the last step. */
    fun onAnswer(
        state: Ring,
        answer: CheckAnswer,
        now: TimeSnapshot,
    ): Transition? = if (state is Ringing) null else answered(state, validator.validate(state.session.checkRun, answer), now)

    /** Grace / Loud + ImageMatchCompleted (matched) counts as a valid answer; no match or a matcher error is a failed attempt. */
    fun onImageMatch(
        state: Ring,
        event: SessionEvent.ImageMatchEvent,
        now: TimeSnapshot,
    ): Transition? =
        when {
            state is Ringing -> {
                null
            }

            event is SessionEvent.ImageMatchCompleted && event.matched -> {
                answered(state, validator.validate(state.session.checkRun, CheckAnswer.ImageMatched), now)
            }

            else -> {
                failedAttempt(state, SessionEffect.ShowRetryPrompt)
            }
        }

    /** Grace / Loud + FallbackRequested, when the policy allows it and it was not used: the fallback plan replaces the check. */
    fun onFallbackRequested(state: Ring): Transition? {
        val session = state.session
        val decision = fallbackPolicy.fallback(session)
        return if (state !is Ringing && !session.checkRun.fallbackUsed && decision is FallbackDecision.Allowed) {
            val run = session.checkRun.copy(plan = decision.plan, step = 0, fallbackUsed = true)
            Transition(state.with(session.copy(checkRun = run)), emptyList())
        } else {
            null
        }
    }

    private fun answered(
        state: Ring,
        result: StepResult,
        now: TimeSnapshot,
    ): Transition {
        val session = state.session
        val run = session.checkRun
        return when (result) {
            StepResult.ValidNext -> {
                Transition(state.with(session.copy(checkRun = run.copy(step = run.step + 1))), emptyList())
            }

            StepResult.Invalid -> {
                failedAttempt(state, SessionEffect.WrongAnswerFeedback)
            }

            StepResult.ValidLast -> {
                Transition(
                    SessionState.Completed(session.withoutTimers().copy(checkRun = run.copy(step = run.step + 1), ended = now)),
                    listOf(
                        SessionEffect.StopSound,
                        SessionEffect.CancelSlot,
                        SessionEffect.PlayMotivation,
                    ),
                )
            }
        }
    }

    private fun failedAttempt(
        state: Ring,
        feedback: SessionEffect,
    ): Transition {
        val run = state.session.checkRun
        return Transition(state.with(state.session.copy(checkRun = run.copy(failedAttempts = run.failedAttempts + 1))), listOf(feedback))
    }
}
