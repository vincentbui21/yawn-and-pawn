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
 */
internal fun snoozedRow(
    state: SessionState.Snoozed,
    event: SessionEvent,
    now: TimeSnapshot,
    userLocked: Boolean = false,
): Transition? {
    // Every ring that starts from a snooze while the user is locked is before the first unlock (Story 2.3).
    val session = state.session.lockedIf(userLocked)
    val snoozeEnd = session.snoozeEnd
    val over = snoozeEnd == null || snoozeEnd.isDue(now)
    return when (event) {
        SessionEvent.SlotFired -> {
            when {
                !over -> null
                snoozeEnd?.sameBoot(now) == true -> Transition(nextRing(session, now, noGrace = false), listOf(heartbeat(now)))
                else -> ringImmediately(session, now)
            }
        }

        SessionEvent.ProcessRestored -> {
            if (over) ringImmediately(session, now) else null
        }

        is SessionEvent.CallEvent -> {
            Transition(state, emptyList())
        }

        is SessionEvent.OverlapAlarmFired -> {
            Transition(nextRing(session, now, noGrace = true), listOf(heartbeat(now)) + mergedEffects(session, event))
        }

        else -> {
            null
        }
    }
}

private fun ringImmediately(
    session: SessionData,
    now: TimeSnapshot,
): Transition =
    Transition(
        nextRing(session, now, noGrace = false),
        listOf(SessionEffect.StartWakeRuntime(session.sessionId), heartbeat(now)),
    )

/** A new ring after a snooze or a merge: Ringing(ringIndex + 1) with a fresh 30-minute interaction deadline. */
private fun nextRing(
    session: SessionData,
    now: TimeSnapshot,
    noGrace: Boolean,
): SessionState.Ringing =
    SessionState.Ringing(
        session.withoutTimers().copy(
            ringIndex = session.ringIndex + 1,
            noGraceThisRing = noGrace,
            paying = null,
            interactionDeadline = Deadline.after(now, SessionReducer.NO_INTERACTION_TIMEOUT),
        ),
    )
