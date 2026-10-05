package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The wake session's rules (AD-2): the normative transition table as a pure function. Only `SessionEngine`
 * (Story 1.12) calls it. Guards that later epics make real come from the injected policies.
 *
 * Pure: no clock reads and no ids made here; "now" is the [TimeSnapshot] argument and everything else arrives in the
 * event. An event with no row for the current state returns the same state with only [SessionEffect.LogIgnored]; the
 * reducer never throws.
 *
 * Beyond the table, AD-2 rule 2 applies to `ProcessRestored` in a ringing state: the restored ring gets a fresh
 * 30-minute interaction deadline from now, and `paying` and the call pause are cleared, with no one-shot effects (the
 * engine applies entry effects).
 * The rows live in `IdleRules.kt`, [RingRules], [CheckRules], [PurchaseRules] and `SnoozedRules.kt`.
 */
class SessionReducer(
    private val availabilityPolicy: SnoozeAvailabilityPolicy,
    checkValidator: CheckValidator,
    fallbackPolicy: FallbackPolicy,
) {
    private val ringRules =
        RingRules(
            checks = CheckRules(checkValidator, fallbackPolicy),
            purchases = PurchaseRules(this::snoozeAvailability),
        )

    /**
     * The next state and one-shot effects for [event] in [state] at [now]. [userLocked] is the environment input "the
     * user has not unlocked since boot" (Story 2.3), read by `SessionEngine` with the time: a ring that starts or is
     * restored while locked is marked before the first unlock (Direct Boot substitutions, history `direct_boot`).
     */
    fun reduce(
        state: SessionState,
        event: SessionEvent,
        now: TimeSnapshot,
        userLocked: Boolean = false,
    ): Transition {
        val row =
            when (state) {
                SessionState.Idle -> idleRow(event, now, userLocked)
                is SessionState.Ring -> ringRules.row(state, event, now, userLocked)
                is SessionState.Snoozed -> snoozedRow(state, event, now, userLocked)
                is SessionState.Completed, is SessionState.Missed -> endedRow(state, event)
            }
        return row ?: Transition(state, listOf(SessionEffect.LogIgnored.of(event, (state as? SessionState.Active)?.session?.sessionId)))
    }

    /** Snooze availability for [session] (AD-7). A test session is never offered a snooze, whatever the policy says. */
    fun snoozeAvailability(session: SessionData): SnoozeAvailability =
        if (session.config.testMode) {
            SnoozeAvailability.Unavailable(UnavailableReason.TestMode)
        } else {
            availabilityPolicy.availability(session)
        }

    companion object {
        /** FR-ALM-9: a ring with no user event for this long is stopped as Missed. */
        val NO_INTERACTION_TIMEOUT: Duration = 30.minutes

        /** AD-4: the session slot's heartbeat while ringing. */
        val HEARTBEAT: Duration = 60.seconds
    }
}
