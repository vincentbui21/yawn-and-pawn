package com.yawnandpawn.app.core.session

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
 * A ring restored while the user is locked (Story 2.3) is before the first unlock: Direct Boot substitutions apply and
 * history records `direct_boot`. Nothing is cleared when unlocked (Story 2.4 handles the unlock).
 */
internal fun SessionData.lockedIf(userLocked: Boolean): SessionData =
    if (userLocked) copy(beforeFirstUnlock = true, startedBeforeUnlock = true) else this

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
