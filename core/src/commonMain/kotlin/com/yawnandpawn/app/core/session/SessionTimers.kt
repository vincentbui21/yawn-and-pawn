package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// Deadline and effect helpers shared by the reducer's rule files.

/** Arm the session slot one heartbeat from [now] (AD-4). */
internal fun heartbeat(now: TimeSnapshot): SessionEffect.ArmSlot = SessionEffect.ArmSlot(Deadline.after(now, SessionReducer.HEARTBEAT))

/**
 * A deadline [duration] after now. While a call pauses the ring, the time counts from the pause instead, because
 * `CallEnded` adds the whole pause to every deadline: so the deadline lands [duration] after the call ends.
 */
internal fun SessionData.deadlineAfter(
    now: TimeSnapshot,
    duration: Duration,
): Deadline = Deadline.after(pausedAt ?: now, duration)

/** A fresh 30-minute interaction deadline (FR-ALM-9). */
internal fun SessionData.freshInteractionDeadline(now: TimeSnapshot): SessionData =
    copy(interactionDeadline = deadlineAfter(now, SessionReducer.NO_INTERACTION_TIMEOUT))

/** Time from [from] to [to]: monotonic on the same boot, wall time across a reboot; never negative. */
internal fun durationBetween(
    from: TimeSnapshot,
    to: TimeSnapshot,
): Duration {
    val millis = if (Deadline.sameBoot(from, to)) to.elapsedMillis - from.elapsedMillis else to.wallMillis - from.wallMillis
    return millis.coerceAtLeast(0).milliseconds
}

/**
 * A new ring of a running session (a restore, or the ring after a snooze; Story 2.3) is before the first unlock exactly
 * when the user is locked now ([userLocked]), so a ring after the unlock plays the chosen sound again. The per-ring
 * `directBootRing` (Story 2.4) takes the same lock state, so the ring's sound and its check plan agree, and an unlock
 * later in the ring changes neither. `startedBeforeUnlock` only goes from false to true (history `direct_boot`).
 * Locked, the run's plan gets the Direct Boot substitutions, entry for entry, so the run's pointer and seeds stay valid
 * (a substituted current entry starts again at its first item).
 */
internal fun SessionData.newRing(
    userLocked: Boolean,
    directBootPlan: (CheckPlan) -> CheckPlan,
): SessionData =
    copy(
        beforeFirstUnlock = userLocked,
        directBootRing = userLocked,
        startedBeforeUnlock = startedBeforeUnlock || userLocked,
        checkRun = if (userLocked) checkRun.withPlan(directBootPlan(checkRun.plan)) else checkRun,
    )

/** No ring timers left: grace, interaction, snooze and pause cleared. */
internal fun SessionData.withoutTimers(): SessionData = copy(graceEnd = null, interactionDeadline = null, snoozeEnd = null, pausedAt = null)

/** The effects of merging the occurrence [event] into [session] (FR-SES-7). */
internal fun mergedEffects(
    session: SessionData,
    event: SessionEvent.OverlapAlarmFired,
): List<SessionEffect> =
    listOf(
        SessionEffect.RecordMergedOccurrence(session.sessionId, event.alarmId, event.scheduledAt),
        SessionEffect.RescheduleAlarm(event.alarmId),
    )
