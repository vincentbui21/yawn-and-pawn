package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.time.TimeSnapshot

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
    val session = (state as? SessionState.Ring)?.session
    val deadline =
        when {
            session == null || session.paused -> null
            state is SessionState.Grace -> session.graceEnd
            else -> session.interactionDeadline
        }
    if (deadline == null || !deadline.isDue(now)) return emptyList()
    return listOf(if (state is SessionState.Grace) SessionEvent.GraceElapsed else SessionEvent.NoInteractionTimeout)
}

private fun soundOf(session: SessionData): EntryEffect =
    if (session.paused) EntryEffect.SoundPaused else EntryEffect.SoundAt(session.config.soundRef, session.config.volumePercent)

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
