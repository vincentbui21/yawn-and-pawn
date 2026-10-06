package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.SeedDeriver
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
    private val directBootPlan: (CheckPlan) -> CheckPlan = DirectBootSubstitution::lockedPlan,
) {
    /** Grace / Loud + CheckAnswerSubmitted: advance, count a wrong answer, or complete on the last item of the last entry. */
    fun onAnswer(
        state: Ring,
        answer: CheckAnswer,
        now: TimeSnapshot,
    ): Transition? =
        if (state is Ringing) {
            null
        } else {
            val seeded = state.withAllSeeds()
            answered(seeded, validator.validate(seeded.session.checkRun, answer), now)
        }

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
                val seeded = state.withAllSeeds()
                answered(seeded, validator.validate(seeded.session.checkRun, CheckAnswer.ImageMatched), now)
            }

            else -> {
                failedAttempt(state, SessionEffect.ShowRetryPrompt)
            }
        }

    /**
     * [Ring] with a seed for every entry of its run: a damaged row with fewer seeds than entries gets the missing ones
     * from the session coordinates, so the validator does not fail every answer on it.
     */
    private fun Ring.withAllSeeds(): Ring {
        val run = session.checkRun.withMissingSeeds(session.sessionId, session.ringIndex)
        return if (run === session.checkRun) this else with(session.copy(checkRun = run))
    }

    /**
     * Grace / Loud + FallbackRequested(type, reason), when the policy allows it and it was not used (Story 3.9): the
     * fallback plan, resolved for this ring with its own seeds, replaces the rest of the check, with the Direct Boot
     * substitutions when the ring uses them (`directBootRing`, Stories 2.3 and 2.4). Its first entry starts with no failed
     * attempts and is shown ([SessionEffect.StartCheckStep]). Timers are unchanged: no new grace window starts. The run
     * keeps the replaced check's id for history (`fallback_from`).
     */
    fun onFallbackRequested(
        state: Ring,
        event: SessionEvent.FallbackRequested,
    ): Transition? {
        val session = state.session
        val decision = fallbackPolicy.fallback(session, FallbackRequest(event.type, event.reason))
        return if (state !is Ringing && !session.checkRun.fallbackUsed && decision is FallbackDecision.Allowed) {
            val fallback =
                CheckRun.forRing(decision.plan, session.sessionId, session.ringIndex, fallback = true) { plan ->
                    if (session.directBootRing) directBootPlan(plan) else plan
                }
            // The fallback's first entry starts with no failed attempts; the session's total stays (history).
            val run =
                fallback.copy(
                    totalFailedAttempts = session.checkRun.totalFailedAttempts,
                    fallbackFrom =
                        session.checkRun.currentEntry
                            ?.type
                            ?.id,
                )
            Transition(state.with(session.copy(checkRun = run)), listOf(SessionEffect.StartCheckStep(0)))
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
        val step = run.step
        return when (result) {
            StepResult.ValidNextItem -> {
                advanced(state, run.copy(step = step.copy(item = step.item + 1)))
            }

            // The next entry is shown through StartCheckStep, as "I'm up" shows the first (Story 3.2).
            StepResult.ValidNext -> {
                advanced(state, run.copy(step = StepPointer(entry = step.entry + 1), failedAttempts = 0))
                    .copy(effects = listOf(SessionEffect.StartCheckStep(step.entry + 1)))
            }

            StepResult.Invalid -> {
                failedAttempt(state, SessionEffect.WrongAnswerFeedback)
            }

            StepResult.InvalidRestart -> {
                failedAttempt(state, SessionEffect.WrongAnswerFeedback, restart = true)
            }

            StepResult.ValidLast -> {
                Transition(
                    SessionState.Completed(
                        session.withoutTimers().copy(checkRun = run.copy(step = StepPointer(entry = step.entry + 1)), ended = now),
                    ),
                    listOf(
                        SessionEffect.StopSound,
                        SessionEffect.CancelSlot,
                        SessionEffect.PlayMotivation,
                    ),
                )
            }
        }
    }

    private fun advanced(
        state: Ring,
        run: CheckRun,
    ): Transition = Transition(state.with(state.session.copy(checkRun = run)), emptyList())

    /**
     * One more failed attempt on the current entry and in the session. With [restart] the entry's puzzle starts over where
     * its type says (item 0, or the current Memory round, [restartItem]) with a new seed from [SeedDeriver], keyed by the
     * entry's new failed-attempt count, so each restart gets another puzzle (with no seed slot for the entry, only the
     * item starts over).
     */
    private fun failedAttempt(
        state: Ring,
        feedback: SessionEffect,
        restart: Boolean = false,
    ): Transition {
        val session = state.session
        val run = session.checkRun
        val attempts = run.failedAttempts + 1
        val counted = run.copy(failedAttempts = attempts, totalFailedAttempts = run.totalFailedAttempts + 1)
        val entry = run.step.entry
        val next =
            when {
                !restart -> {
                    counted
                }

                entry in run.seeds.indices -> {
                    val seed = run.seedOf(session.sessionId, session.ringIndex, entry, attempts)
                    counted.copy(
                        seeds = run.seeds.toMutableList().also { it[entry] = seed },
                        step = run.step.copy(item = run.restartItem()),
                    )
                }

                else -> {
                    counted.copy(step = run.step.copy(item = run.restartItem()))
                }
            }
        return Transition(state.with(session.copy(checkRun = next)), listOf(feedback))
    }
}

/** Where the current entry starts again after a wrong restart: item 0, or the current round's first tap (Memory, Story 3.8). */
private fun CheckRun.restartItem(): Int = currentEntry?.let { it.type.restartFrom(step.item, it.difficulty) } ?: 0
