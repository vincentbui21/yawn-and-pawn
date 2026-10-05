package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot

// AD-2 rows from Idle, Completed and Missed. A null result means no row matches.

/**
 * Idle + AlarmFired (alarm enabled) and Idle + TestAlarmFired: the first ring. It is before the first unlock when the
 * event says so or the user is locked now ([userLocked], Story 2.3).
 */
internal fun idleRow(
    event: SessionEvent,
    now: TimeSnapshot,
    userLocked: Boolean = false,
): Transition? =
    when (event) {
        is SessionEvent.AlarmFired -> {
            event.config?.let { startSession(event.sessionId, it, event.seeds, event.beforeFirstUnlock || userLocked, now) }
        }

        is SessionEvent.TestAlarmFired -> {
            startSession(event.sessionId, event.config.copy(testMode = true), event.seeds, event.beforeFirstUnlock || userLocked, now)
        }

        else -> {
            null
        }
    }

/** Completed / Missed + Recorded (the history row of this session was written): back to Idle. */
internal fun endedRow(
    state: SessionState.Active,
    event: SessionEvent,
): Transition? =
    if (event is SessionEvent.Recorded && event.sessionId == state.session.sessionId) {
        Transition(SessionState.Idle, listOf(SessionEffect.ClearRuntimeSession(event.sessionId)))
    } else {
        null
    }

/**
 * Freezes [config] and creates the `CheckRun`: Ringing(ringIndex = 1) with a fresh 30-minute interaction deadline and
 * the first ring time ([now]) and the boot state for the history row.
 */
private fun startSession(
    sessionId: String,
    config: SessionConfig,
    seeds: List<Long>,
    beforeFirstUnlock: Boolean,
    now: TimeSnapshot,
): Transition {
    val session =
        SessionData(
            sessionId = sessionId,
            config = config,
            ringIndex = 1,
            snoozesGranted = 0,
            // Before the first unlock the first check plan has the Direct Boot substitutions (Story 2.3).
            checkRun = CheckRun(plan = DirectBootSubstitution.apply(config, beforeFirstUnlock).checkPlan, seeds = seeds),
            firstRing = now,
            startedBeforeUnlock = beforeFirstUnlock,
            beforeFirstUnlock = beforeFirstUnlock,
            interactionDeadline = Deadline.after(now, SessionReducer.NO_INTERACTION_TIMEOUT),
        )
    return Transition(
        SessionState.Ringing(session),
        listOf(SessionEffect.StartWakeRuntime(sessionId), heartbeat(now), SessionEffect.RecordSessionStart(sessionId, config)),
    )
}
