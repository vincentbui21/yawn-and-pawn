package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot

// AD-2 rows from Snoozed. A null result means no row matches.

/**
 * - SlotFired once the snooze is over: on the same boot the slot fired on time (next ring, arm the heartbeat); on
 *   another boot the snooze ran out while the phone was off (ring immediately).
 * - ProcessRestored once the snooze is over: ring immediately.
 * - CallStarted / CallEnded: no change, the snooze keeps counting.
 * - OverlapAlarmFired: the snooze ends early with no fee and the next ring has no grace window.
 *
 * Each next ring is before the first unlock exactly when the user is locked now ([userLocked], Story 2.3).
 */
internal fun snoozedRow(
    state: SessionState.Snoozed,
    event: SessionEvent,
    now: TimeSnapshot,
    userLocked: Boolean = false,
    directBootPlan: (CheckPlan) -> CheckPlan = DirectBootSubstitution::lockedPlan,
): Transition? {
    val session = state.session
    val snoozeEnd = session.snoozeEnd
    val over = snoozeEnd == null || snoozeEnd.isDue(now)
    val next = NextRing(now, userLocked, directBootPlan)
    return when (event) {
        SessionEvent.SlotFired -> {
            when {
                !over -> null
                snoozeEnd?.sameBoot(now) == true -> Transition(next.of(session, noGrace = false), listOf(heartbeat(now)))
                else -> ringImmediately(next.of(session, noGrace = false), now)
            }
        }

        SessionEvent.ProcessRestored -> {
            if (over) ringImmediately(next.of(session, noGrace = false), now) else null
        }

        is SessionEvent.CallEvent -> {
            Transition(state, emptyList())
        }

        is SessionEvent.OverlapAlarmFired -> {
            Transition(next.of(session, noGrace = true), listOf(heartbeat(now)) + mergedEffects(session, event))
        }

        else -> {
            null
        }
    }
}

private fun ringImmediately(
    ringing: SessionState.Ringing,
    now: TimeSnapshot,
): Transition = Transition(ringing, listOf(SessionEffect.StartWakeRuntime(ringing.session.sessionId), heartbeat(now)))

/**
 * A new ring after a snooze or a merge at [now]: Ringing(ringIndex + 1) with a fresh 30-minute interaction deadline.
 * It is before the first unlock exactly when [userLocked] (Story 2.3). Its check plan is the session's own (the
 * fallback plan once used, else the frozen config's) with the Direct Boot substitutions while locked, so a ring after
 * the unlock gets the chosen check back.
 */
private class NextRing(
    private val now: TimeSnapshot,
    private val userLocked: Boolean,
    private val directBootPlan: (CheckPlan) -> CheckPlan,
) {
    fun of(
        session: SessionData,
        noGrace: Boolean,
    ): SessionState.Ringing {
        val plan = if (session.checkRun.fallbackUsed) session.checkRun.plan else session.config.checkPlan
        return SessionState.Ringing(
            session.withoutTimers().newRing(userLocked, directBootPlan, plan).copy(
                ringIndex = session.ringIndex + 1,
                noGraceThisRing = noGrace,
                paying = null,
                interactionDeadline = Deadline.after(now, SessionReducer.NO_INTERACTION_TIMEOUT),
            ),
        )
    }
}
