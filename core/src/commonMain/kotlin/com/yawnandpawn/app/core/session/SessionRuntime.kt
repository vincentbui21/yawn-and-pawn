package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlin.time.Duration

/**
 * The runtime [state] wants (AD-2 rule 1). Idempotent and pure: `SessionEngine` applies it after every dispatch and on
 * `ProcessRestored`, where one-shot effects are never replayed.
 * - Ringing, Loud: sound at the set volume (paused during a call), vibration if the alarm vibrates, slot armed, wake UI.
 * - Grace: muted, vibration only with "vibrate in grace", slot armed, wake UI.
 * - Snoozed: sound off, slot armed at the snooze end.
 * - Completed, Missed: history write requested.
 * - Idle: nothing.
 */
fun entryEffects(state: SessionState): List<EntryEffect> =
    when (state) {
        SessionState.Idle -> {
            emptyList()
        }

        is SessionState.Ringing, is SessionState.Loud -> {
            ringing(state.session, sound = soundOf(state.session), vibrate = state.session.config.vibration)
        }

        is SessionState.Grace -> {
            ringing(state.session, sound = EntryEffect.Muted, vibrate = state.session.config.vibrateInGrace)
        }

        is SessionState.Snoozed -> {
            listOfNotNull(EntryEffect.SoundOff, state.session.snoozeEnd?.let { EntryEffect.SlotArmedAt(it) })
        }

        is SessionState.Completed, is SessionState.Missed -> {
            listOf(EntryEffect.HistoryWriteRequested(state.session.sessionId))
        }
    }

/**
 * The timer events due at [now] (FR-ALM-9): [SessionEvent.GraceElapsed] once the grace window ends, and
 * [SessionEvent.NoInteractionTimeout] once a ringing or loud session has had no user event for 30 minutes. Deadlines
 * compare monotonic time on the same boot and wall time after a reboot ([com.yawnandpawn.app.core.time.Deadline]).
 * Nothing is due while a call pauses the ring, or while snoozed (the slot ends a snooze).
 */
fun dueEvents(
    state: SessionState,
    now: TimeSnapshot,
): List<SessionEvent> {
    val deadline = timerDeadline(state)
    if (deadline == null || !deadline.isDue(now)) return emptyList()
    return listOf(if (state is SessionState.Grace) SessionEvent.GraceElapsed else SessionEvent.NoInteractionTimeout)
}

/**
 * How long until [dueEvents] has an event for [state] (Story 1.16): the time left to the grace end in Grace, to the
 * interaction deadline in Ringing and Loud; zero once due. Null when no timer runs (no ring, paused by a call, or
 * snoozed), so a caller waits for the next state instead of polling. [Deadline.remaining] compares monotonic time on
 * the same boot, so a wall-clock change never moves it. A deadline from an earlier boot compares wall time, which can
 * still move (a network time sync after the reboot), so the wait is then at most one [SessionReducer.HEARTBEAT].
 */
fun nextTickIn(
    state: SessionState,
    now: TimeSnapshot,
): Duration? {
    val deadline = timerDeadline(state) ?: return null
    val left = deadline.remaining(now)
    return if (deadline.sameBoot(now)) left else minOf(left, SessionReducer.HEARTBEAT)
}

/** The one deadline the session's timer events wait for, shared by [dueEvents] and [nextTickIn]. */
private fun timerDeadline(state: SessionState): Deadline? {
    val session = (state as? SessionState.Ring)?.session
    return when {
        session == null || session.paused -> null
        state is SessionState.Grace -> session.graceEnd
        else -> session.interactionDeadline
    }
}

/** The ring's sound: before the first unlock a sound that needs normal storage is the default one (Story 2.3). */
private fun soundOf(session: SessionData): EntryEffect =
    if (session.paused) {
        EntryEffect.SoundPaused
    } else {
        // At least 10% (Epic 3 review): a session stored with 0% before that minimum never re-rings silently.
        EntryEffect.SoundAt(
            DirectBootSubstitution.apply(session.config, session.directBootRing).soundRef,
            Alarm.ringableVolume(session.config.volumePercent),
        )
    }

private fun ringing(
    session: SessionData,
    sound: EntryEffect,
    vibrate: Boolean,
): List<EntryEffect> =
    listOfNotNull(
        sound,
        EntryEffect.Vibrating.takeIf { vibrate && !session.paused },
        EntryEffect.HeartbeatSlotArmed,
        EntryEffect.WakeUiShown,
    )
