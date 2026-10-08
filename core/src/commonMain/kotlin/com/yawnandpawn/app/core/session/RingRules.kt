package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.session.SessionState.Grace
import com.yawnandpawn.app.core.session.SessionState.Loud
import com.yawnandpawn.app.core.session.SessionState.Ring
import com.yawnandpawn.app.core.session.SessionState.Ringing
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** AD-2 rows from Ringing, Grace and Loud. A null result means no row matches. */
internal class RingRules(
    private val checks: CheckRules,
    private val purchases: PurchaseRules,
    private val directBootPlan: (CheckPlan) -> CheckPlan = DirectBootSubstitution::lockedPlan,
) {
    fun row(
        state: Ring,
        event: SessionEvent,
        now: TimeSnapshot,
        userLocked: Boolean = false,
        keyguardLocked: Boolean = false,
    ): Transition? =
        when (event) {
            is SessionEvent.UserEvent -> onUserEvent(state, event, now, keyguardLocked)
            is SessionEvent.PurchaseEvent -> purchases.onPurchase(state, event, now)
            is SessionEvent.UnlockEvent -> purchases.onUnlock(state, event)
            is SessionEvent.ImageMatchEvent -> checks.onImageMatch(state, event, now)
            is SessionEvent.CallEvent -> onCall(state, event, now)
            is SessionEvent.TimerEvent -> onTimer(state, event, now)
            SessionEvent.SlotFired -> Transition(state, listOf(heartbeat(now)))
            SessionEvent.ProcessRestored -> restored(state, now, userLocked)
            is SessionEvent.OverlapAlarmFired -> Transition(state, mergedEffects(state.session, event))
            SessionEvent.UserUnlocked -> unlocked(state)
            is SessionEvent.AlarmFired, is SessionEvent.TestAlarmFired, is SessionEvent.Recorded -> null
        }

    /**
     * The row for [event] if one matches, else only "any user event resets the interaction deadline" (and, for a Pay, the
     * log line that it was ignored).
     */
    private fun onUserEvent(
        state: Ring,
        event: SessionEvent.UserEvent,
        now: TimeSnapshot,
        keyguardLocked: Boolean,
    ): Transition {
        val reset = state.with(state.session.freshInteractionDeadline(now))
        val row =
            when (event) {
                SessionEvent.ImUpTapped -> imUp(reset, now)
                is SessionEvent.CheckAnswerSubmitted -> checks.onAnswer(reset, event.answer, now)
                is SessionEvent.FallbackRequested -> checks.onFallbackRequested(reset, event)
                SessionEvent.SnoozeTapped -> purchases.onSnoozeTapped(reset)
                is SessionEvent.PayConfirmed -> purchases.onPayConfirmed(reset, event, now, keyguardLocked)
                is SessionEvent.ReuseAccepted -> purchases.onPaidSnooze(reset, PaidWith(event.productId, event.token), now)
                is SessionEvent.ReuseDeclined -> purchases.onReuseDeclined(reset, event.productId)
                SessionEvent.UserInteracted -> null
            }
        // A Pay that fails its guards (unavailable, already paying, a price for another product) is logged (Story 4.8).
        val ignoredPay =
            if (event is SessionEvent.PayConfirmed) listOf(SessionEffect.LogIgnored.of(event, state.session.sessionId)) else emptyList()
        return row ?: Transition(reset, ignoredPay)
    }

    /** Ringing + ImUpTapped: Grace (muted) unless this ring has no grace window, then Loud. */
    private fun imUp(
        state: Ring,
        now: TimeSnapshot,
    ): Transition? {
        if (state !is Ringing) return null
        val session = state.session
        val startStep = SessionEffect.StartCheckStep(session.checkRun.step.entry)
        return if (session.noGraceThisRing) {
            Transition(Loud(session), listOf(startStep))
        } else {
            val graceEnd = session.deadlineAfter(now, session.config.graceSeconds.seconds)
            Transition(Grace(session.copy(graceEnd = graceEnd)), listOf(SessionEffect.Mute, startStep))
        }
    }

    /** CallStarted pauses the sound and the deadlines; CallEnded (while paused) resumes them, leaving the call out. */
    private fun onCall(
        state: Ring,
        event: SessionEvent.CallEvent,
        now: TimeSnapshot,
    ): Transition? {
        val session = state.session
        return when (event) {
            SessionEvent.CallStarted -> {
                if (session.paused) null else Transition(state.with(session.copy(pausedAt = now)), listOf(SessionEffect.PauseSound))
            }

            SessionEvent.CallEnded -> {
                session.pausedAt?.let { pausedAt -> resumed(state, durationBetween(pausedAt, now)) }
            }
        }
    }

    private fun resumed(
        state: Ring,
        pausedFor: Duration,
    ): Transition {
        val session = state.session
        val resumed =
            session.copy(
                graceEnd = session.graceEnd?.shiftedBy(pausedFor),
                interactionDeadline = session.interactionDeadline?.shiftedBy(pausedFor),
                pausedAt = null,
            )
        return Transition(state.with(resumed), listOf(SessionEffect.ResumeSound))
    }

    /** Grace + GraceElapsed: loud again. Ringing / Loud + NoInteractionTimeout (30 min, paused time excluded): Missed. */
    private fun onTimer(
        state: Ring,
        event: SessionEvent.TimerEvent,
        now: TimeSnapshot,
    ): Transition? {
        val session = state.session
        return when (event) {
            SessionEvent.GraceElapsed -> {
                if (state is Grace && !session.paused && session.graceEnd?.isDue(now) == true) {
                    Transition(
                        Loud(session.copy(graceEnd = null)),
                        listOf(
                            SessionEffect.UnmuteToVolume(Alarm.ringableVolume(session.config.volumePercent)),
                            SessionEffect.StrongHaptic,
                        ),
                    )
                } else {
                    null
                }
            }

            SessionEvent.NoInteractionTimeout -> {
                if (state !is Grace && !session.paused && session.interactionDeadline?.isDue(now) == true) missed(session, now) else null
            }
        }
    }

    private fun missed(
        session: SessionData,
        now: TimeSnapshot,
    ): Transition =
        Transition(
            SessionState.Missed(session.withoutTimers().withoutPayment().copy(ended = now)),
            listOf(SessionEffect.StopSound, SessionEffect.CancelSlot),
        )

    /**
     * AD-2 rule 2: a restored ring gets a fresh 30-minute deadline from now and `paying` (with `unlocking`) is cleared,
     * with no one-shot effects; the intent row stays.
     * The pause is cleared too: a call that ended during the crash or reboot sends no CallEnded, and the call adapter
     * sends CallStarted again if the call is still on. The restored ring is before the first unlock exactly when the user
     * is locked now ([userLocked], Story 2.3, for example after `LOCKED_BOOT_COMPLETED`): then its check plan gets the
     * Direct Boot substitutions and history says `direct_boot`, also for a session that started unlocked. Restored
     * unlocked, the ring plays the chosen sound again; its check plan stays as it is mid-ring.
     */
    private fun restored(
        state: Ring,
        now: TimeSnapshot,
        userLocked: Boolean,
    ): Transition =
        Transition(
            state.with(
                state.session
                    .copy(
                        paying = null,
                        unlocking = false,
                        pausedAt = null,
                        interactionDeadline = Deadline.after(now, SessionReducer.NO_INTERACTION_TIMEOUT),
                    ).newRing(userLocked, directBootPlan),
            ),
            emptyList(),
        )

    /** Ringing (before first unlock) + UserUnlocked. */
    private fun unlocked(state: Ring): Transition? =
        if (state is Ringing && state.session.beforeFirstUnlock) {
            Transition(
                state.with(state.session.copy(beforeFirstUnlock = false)),
                listOf(SessionEffect.LiftDirectBootSubstitutions, SessionEffect.InitBilling),
            )
        } else {
            null
        }
}
