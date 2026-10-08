package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.billing.GrantLedgerEntry
import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.time.Deadline
import kotlin.time.Instant

/** The result of one [SessionReducer.reduce]: the next state and the one-shot effects to run once it is committed. */
data class Transition(
    val state: SessionState,
    val effects: List<SessionEffect>,
)

/** How a session ended (Completed or Missed), for its history row. */
enum class SessionEnd {
    Completed,
    Missed,
}

/** Which payment message the wake screen shows after a failed or cancelled purchase. */
enum class PurchaseOutcome {
    Failed,
    Cancelled,

    /** The unlock before Play was cancelled or failed (Spike S1): "Phone still locked. No charge." */
    UnlockFailed,
}

/**
 * One-shot effects of a transition (the AD-2 "one-shot effects" column). They run once, after the transition is
 * committed, and are never replayed on restore. Table entries that only change the state (freeze the config, create,
 * advance or restart the `CheckRun`, count attempts or snoozes, reset or shift deadlines) are part of the next state,
 * not effects.
 */
sealed interface SessionEffect {
    /** Start the wake runtime (`WakeService`, Story 1.14) for [sessionId]. */
    data class StartWakeRuntime(
        val sessionId: String,
    ) : SessionEffect

    /** Arm the session slot (AD-4) at [at], replacing any earlier arming. */
    data class ArmSlot(
        val at: Deadline,
    ) : SessionEffect

    /** Cancel the session slot. */
    data object CancelSlot : SessionEffect

    /**
     * Write the session's start row (AD-18). `SessionEngine` hands it to its `SessionRecorder`, never to the
     * [EffectRunner]. How the session ended is written by the entry effect [EntryEffect.HistoryWriteRequested].
     */
    data class RecordSessionStart(
        val sessionId: String,
        val config: SessionConfig,
    ) : SessionEffect

    /**
     * Record the occurrence of [alarmId] at [scheduledAt] as merged into [sessionId] (FR-SES-7): `SessionEngine` hands it
     * to its `SessionRecorder` (one `session_merge` row, Story 2.9), never to the [EffectRunner].
     */
    data class RecordMergedOccurrence(
        val sessionId: String,
        val alarmId: String,
        val scheduledAt: Instant,
    ) : SessionEffect

    /** Schedule the next occurrence of the merged alarm [alarmId]. */
    data class RescheduleAlarm(
        val alarmId: String,
    ) : SessionEffect

    /** Silence the alarm for the grace window. */
    data object Mute : SessionEffect

    /** End the grace window: back to [volumePercent] of the alarm stream. */
    data class UnmuteToVolume(
        val volumePercent: Int,
    ) : SessionEffect

    /** One strong vibration when the grace window ends. */
    data object StrongHaptic : SessionEffect

    /** Stop the alarm sound. */
    data object StopSound : SessionEffect

    /** A call started: pause the sound. */
    data object PauseSound : SessionEffect

    /** The call ended: resume the sound. */
    data object ResumeSound : SessionEffect

    /** Show entry [stepIndex] of the ring's resolved check plan, at the run's current item. */
    data class StartCheckStep(
        val stepIndex: Int,
    ) : SessionEffect

    /** The answer was wrong. */
    data object WrongAnswerFeedback : SessionEffect

    /** The image did not match or the matcher failed: ask for another photo. */
    data object ShowRetryPrompt : SessionEffect

    /** Play the motivation clip after the check is passed. */
    data object PlayMotivation : SessionEffect

    /** Ask the user to confirm paying for [offer]. */
    data class ShowSnoozeConfirm(
        val offer: SnoozeOffer,
    ) : SessionEffect

    /**
     * Persist [intent] (AD-7) before billing launches. `SessionEngine` writes it in the same `runtime.db` transaction as
     * the transition (`RuntimeWrite.PutPurchaseIntent`, Story 4.8), never through the [EffectRunner].
     */
    data class PersistPurchaseIntent(
        val intent: PurchaseIntent,
    ) : SessionEffect

    /**
     * Launch Play Billing for the committed intent [intentId] with `obfuscatedProfileId = sessionId` (AD-7). The runner
     * reads the intent from the `PurchaseIntentStore`; it is committed before this runs.
     */
    data class LaunchBilling(
        val intentId: PurchaseIntentId,
        val sessionId: String,
    ) : SessionEffect

    /**
     * The keyguard is up: ask the user to unlock before Play opens (Spike S1). The runner calls
     * `UnlockPort.requestUnlock` outside the engine's Mutex and dispatches `UnlockSucceeded` or `UnlockFailed`.
     */
    data class RequestKeyguardDismiss(
        val intentId: PurchaseIntentId,
    ) : SessionEffect

    /**
     * Write the grant ledger row of a paid snooze (AD-7, Story 4.10). `SessionEngine` writes it in the same `runtime.db`
     * transaction as the Snoozed state (`RuntimeWrite.PutGrant`), never through the [EffectRunner].
     */
    data class PersistGrant(
        val grant: GrantLedgerEntry,
    ) : SessionEffect

    /**
     * Settle the purchase [token] that granted a snooze: record it, consume it, then drop its ledger row
     * (`PurchaseLedger.settle`, Story 4.10). The runner starts it outside the engine's Mutex and never waits for it: the
     * snooze already started at the commit.
     */
    data class Consume(
        val token: PurchaseToken,
    ) : SessionEffect

    /** Show the payment result message; the sound continues. */
    data class ShowPurchaseOutcome(
        val outcome: PurchaseOutcome,
    ) : SessionEffect

    /** Show the "payment pending" message; the sound continues. */
    data object ShowPaymentPending : SessionEffect

    /** Offer to reuse the stranded payment for [productId]. */
    data class ShowReuseSheet(
        val productId: String,
    ) : SessionEffect

    /** Close the reuse sheet. */
    data object HideReuseSheet : SessionEffect

    /** The phone was unlocked: lift the Direct Boot substitutions at the next check step (Epic 2). */
    data object LiftDirectBootSubstitutions : SessionEffect

    /** The phone was unlocked: initialise billing (AD-15). */
    data object InitBilling : SessionEffect

    /** Delete the finished session [sessionId] from `runtime.db`. */
    data class ClearRuntimeSession(
        val sessionId: String,
    ) : SessionEffect

    /**
     * An event of type [eventType] has no AD-2 row for the current state; it was ignored. Only the type name and the
     * session id go to the log, never the event's contents (labels, sounds, tokens).
     */
    data class LogIgnored(
        val eventType: String,
        val sessionId: String?,
    ) : SessionEffect {
        companion object {
            /** The log entry for ignoring [event] in a session [stateSessionId] (null when Idle). */
            fun of(
                event: SessionEvent,
                stateSessionId: String?,
            ): LogIgnored = LogIgnored(event::class.simpleName ?: "SessionEvent", event.sessionIdOrNull() ?: stateSessionId)

            private fun SessionEvent.sessionIdOrNull(): String? =
                when (this) {
                    is SessionEvent.AlarmFired -> sessionId
                    is SessionEvent.TestAlarmFired -> sessionId
                    is SessionEvent.Recorded -> sessionId
                    else -> null
                }
        }
    }
}

/**
 * The runtime a state wants, from [entryEffects]. Idempotent: `SessionEngine` applies them after every dispatch and on
 * restore, and applying them twice changes nothing.
 */
sealed interface EntryEffect {
    /** The alarm sound [soundRef] plays at [volumePercent] of the alarm stream. */
    data class SoundAt(
        val soundRef: String,
        val volumePercent: Int,
    ) : EntryEffect

    /** The sound is paused by a call. */
    data object SoundPaused : EntryEffect

    /** The sound is muted (grace window). */
    data object Muted : EntryEffect

    /** No sound plays. */
    data object SoundOff : EntryEffect

    /** The phone vibrates. */
    data object Vibrating : EntryEffect

    /** The 60 s heartbeat slot is armed. */
    data object HeartbeatSlotArmed : EntryEffect

    /** The session slot is armed at [at] (the end of the snooze). */
    data class SlotArmedAt(
        val at: Deadline,
    ) : EntryEffect

    /** The wake screen is shown. */
    data object WakeUiShown : EntryEffect

    /**
     * The history row of [sessionId] is written with how it ended (AD-18). The one place the outcome is written:
     * `SessionEngine` hands it to its `SessionRecorder` (never to the [EffectRunner]), and once the write succeeded it
     * reduces `Recorded` in the same lock. Idempotent: writing it again leaves the same one row.
     */
    data class HistoryWriteRequested(
        val sessionId: String,
    ) : EntryEffect
}
