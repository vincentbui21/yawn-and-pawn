package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckType
import kotlin.time.Instant

/**
 * A Play purchase token. Never logged (Architecture Conventions: Logging), so [toString] hides it; events that carry
 * one can be logged as ignored safely.
 */
class PurchaseToken(
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is PurchaseToken && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "PurchaseToken(redacted)"
}

/** What `PurchaseReconciler` (AD-7, Epic 4) decided about a purchase update; it arrives inside the event. */
enum class PurchaseVerdict {
    Grant,
    ConsumeOnly,
    LeaveForAutoRefund,
    OfferReuse,
    Ignore,
}

/**
 * The AD-2 event set: everything that can happen to a wake session. Events are facts in the past tense. Inputs the
 * reducer cannot compute purely (session id, resolved config, purchase intent id, reconciler verdict) are
 * filled in by `SessionEngine` (Story 1.12) before dispatch.
 *
 * Distinct from [com.yawnandpawn.app.core.alarm.AlarmFired], the scheduler's fire of a stored alarm.
 */
sealed interface SessionEvent {
    /**
     * A stored alarm fired and starts a session. [config] is null when the alarm is missing or disabled, which fails
     * the "alarm enabled" guard.
     */
    data class AlarmFired(
        val sessionId: String,
        val config: SessionConfig?,
        val beforeFirstUnlock: Boolean,
    ) : SessionEvent

    /** The test alarm fired (FR-ALM-12); the session always runs in test mode. */
    data class TestAlarmFired(
        val sessionId: String,
        val config: SessionConfig,
        val beforeFirstUnlock: Boolean,
    ) : SessionEvent

    /** The session slot fired: the 60 s heartbeat while ringing, the re-ring while snoozed (AD-4). */
    data object SlotFired : SessionEvent

    /** The process restarted with a persisted session (crash, kill or reboot). */
    data object ProcessRestored : SessionEvent

    /** Another stored alarm fired during this session and is merged into it (FR-SES-7). */
    data class OverlapAlarmFired(
        val alarmId: String,
        val scheduledAt: Instant,
    ) : SessionEvent

    /** A timer of the session ran out; produced by `dueEvents`. */
    sealed interface TimerEvent : SessionEvent

    /** The grace window ended (from `dueEvents`). */
    data object GraceElapsed : TimerEvent

    /** 30 minutes passed without a user event (FR-ALM-9, from `dueEvents`). */
    data object NoInteractionTimeout : TimerEvent

    /** The phone's call state changed (AD-5: detected from the audio mode, no `READ_PHONE_STATE`). */
    sealed interface CallEvent : SessionEvent

    /**
     * A call took the audio mode (ringtone, in-call or in-communication). Call contract (Story 2.7, hook in Story 1.14):
     * the call adapter sends it again after every `ProcessRestored` and at every new ring (first ring, re-ring after a
     * snooze, merged alarm) while a call is still active, because a restore and a new ring both start unpaused. The wake
     * runtime only pauses on `SoundPaused`; it never detects calls itself.
     */
    data object CallStarted : CallEvent

    /** The call ended. */
    data object CallEnded : CallEvent

    /** The user unlocked the phone for the first time since boot. */
    data object UserUnlocked : SessionEvent

    /** The history row of the session [sessionId] was written (AD-18). */
    data class Recorded(
        val sessionId: String,
    ) : SessionEvent

    /** A result of the `ImageMatcher` port (AD-9), run as an effect for a photo check step. */
    sealed interface ImageMatchEvent : SessionEvent

    /** The image matcher finished; [matched] says whether the photo matched the reference. */
    data class ImageMatchCompleted(
        val matched: Boolean,
    ) : ImageMatchEvent

    /** The image matcher failed with an error. */
    data object ImageMatchFailed : ImageMatchEvent

    /** A billing result for the session (AD-7), fed back by the billing adapter through the reconciler. */
    sealed interface PurchaseEvent : SessionEvent

    /** A stranded payment for [productId] can be reused for this snooze when [verdict] is [PurchaseVerdict.OfferReuse]. */
    data class ReuseOffered(
        val productId: String,
        val verdict: PurchaseVerdict,
    ) : PurchaseEvent

    /**
     * A purchase of [productId] arrived; it grants a snooze when [verdict] is [PurchaseVerdict.Grant]. The check starts
     * over at the next ring, with that ring's seeds.
     */
    data class PurchaseGranted(
        val productId: String,
        val token: PurchaseToken,
        val verdict: PurchaseVerdict,
    ) : PurchaseEvent

    /** The purchase failed. */
    data object PurchaseFailed : PurchaseEvent

    /** The user cancelled the payment sheet. */
    data object PurchaseCancelled : PurchaseEvent

    /** Play reported the payment as pending. */
    data object PurchasePending : PurchaseEvent

    /** Sent by the wake UI. In a ringing state every user event also resets the 30-minute interaction deadline. */
    sealed interface UserEvent : SessionEvent

    /** The user tapped "I'm up". */
    data object ImUpTapped : UserEvent

    /** The user submitted [answer] for the current check step. */
    data class CheckAnswerSubmitted(
        val answer: CheckAnswer,
    ) : UserEvent

    /**
     * The user picked [type] in the Fallback check picker (FR-PWK-11, Story 3.9), offered for [reason]. The
     * `FallbackPolicy` decides whether it replaces the check.
     */
    data class FallbackRequested(
        val type: CheckType,
        val reason: FallbackReason,
    ) : UserEvent

    /** The user tapped Snooze. */
    data object SnoozeTapped : UserEvent

    /** The user confirmed paying for the snooze; [intentId] names the `PurchaseIntent` to persist. */
    data class PayConfirmed(
        val intentId: PurchaseIntentId,
    ) : UserEvent

    /** The user accepted reusing the stranded payment [token] for [productId]; the check starts over at the next ring. */
    data class ReuseAccepted(
        val productId: String,
        val token: PurchaseToken,
    ) : UserEvent

    /** The user declined reusing the stranded payment for [productId]. */
    data class ReuseDeclined(
        val productId: String,
    ) : UserEvent

    /** Any other touch on the wake screen. */
    data object UserInteracted : UserEvent
}
