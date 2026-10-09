package com.yawnandpawn.app.core.log

import com.yawnandpawn.app.core.error.DomainError
import kotlin.time.Instant

/**
 * Port for the app's log (Architecture Conventions: Logging). `:core` never prints; the adapter decides where an event
 * goes (`AndroidLogger` in the app, `FakeLogger` in tests). Never log purchase tokens, photos or recordings.
 */
fun interface Logger {
    fun log(event: LogEvent)
}

/** Which system alarm fired (AD-4): a stored alarm, the session slot or the test alarm. */
enum class FireKind {
    Alarm,
    SessionSlot,
    TestAlarm,
}

/** Everything the app logs. Events are past tense. */
sealed interface LogEvent {
    /** The user deleted the alarm [alarmId] at [at] (the delete dialog says "This is logged."). */
    data class AlarmDeleted(
        val alarmId: String,
        val at: Instant,
    ) : LogEvent

    /**
     * An expected failure the user can only retry: [operation] says what failed ("load alarms"), [cause] is diagnostic
     * text for the log only and is never shown to users.
     */
    data class OperationFailed(
        val operation: String,
        val cause: String,
    ) : LogEvent {
        companion object {
            /** [operation] failed with [error]. */
            fun of(
                operation: String,
                error: DomainError,
            ): OperationFailed = OperationFailed(operation, error.diagnostic())
        }
    }

    /**
     * A system alarm of [kind] fired and nothing acted on it, because of [reason] (for example the alarm [alarmId] was
     * deleted or disabled, or the slot is not bound yet).
     */
    data class FireIgnored(
        val kind: FireKind,
        val alarmId: String?,
        val reason: String,
    ) : LogEvent

    /**
     * `rescheduleAll()` ran: [scheduled] enabled alarms armed, [disabled] disabled alarms whose cancel call returned
     * (not necessarily a system alarm removed), [failed] scheduler calls that failed.
     */
    data class AlarmsRescheduled(
        val scheduled: Int,
        val disabled: Int,
        val failed: Int,
    ) : LogEvent

    /**
     * The enabled one-time alarm [alarmId] was meant to ring at [missedAt], which passed while the phone was off or the
     * clock jumped; `rescheduleAll()` switched it off instead of moving it to the next day.
     */
    data class OneTimeAlarmPassed(
        val alarmId: String,
        val missedAt: Instant,
    ) : LogEvent

    /**
     * A session effect of type [effectType] reached a runner that only logs it: the wake runtime (Story 1.14) logs the
     * effects whose adapters arrive in later stories (checks UI, billing, motivation, purchase messages). [entry] is true
     * for an entry effect, false for a one-shot effect. Only the type name is logged.
     */
    data class SessionEffectLogged(
        val effectType: String,
        val entry: Boolean,
    ) : LogEvent

    /** The session event of type [eventType] had no AD-2 row in session [sessionId] (null when Idle) and was ignored. */
    data class SessionEventIgnored(
        val eventType: String,
        val sessionId: String?,
    ) : LogEvent

    /**
     * The alarm sound asked for could not play, because of [reason] (it could not be opened, failed to prepare or failed
     * while ringing, or the sound library is not there yet); the default sound plays instead in the same ring, so the
     * alarm is never silent (FR-SND). The sound reference itself is never logged (it can name a user's file).
     */
    data class SoundFellBack(
        val reason: String,
    ) : LogEvent

    /**
     * The session could not start (or the wake flow failed before it did), because of [cause]: the emergency ring plays
     * the default sound with vibration, the notification and the wake screen until "I'm up" or 30 minutes (NFR-2).
     */
    data class EmergencyRingStarted(
        val cause: String,
    ) : LogEvent

    /** The emergency ring stopped: [reason] is "I'm up" or the 30-minute limit. */
    data class EmergencyRingStopped(
        val reason: String,
    ) : LogEvent

    /**
     * The session slot was armed outside the engine (Story 2.1) because of [reason] (a system event or a refused
     * wake-service start), [inMillis] from now, for the session [sessionId] and/or the alarm [alarmId] it stands for.
     */
    data class SessionSlotRearmed(
        val reason: String,
        val inMillis: Long,
        val sessionId: String?,
        val alarmId: String?,
    ) : LogEvent

    /**
     * The one-time alarm [alarmId] fired (armed for [scheduledAt]) and `RearmOnFire` switched it off, so nothing stays
     * armed for it.
     */
    data class OneTimeAlarmDisabled(
        val alarmId: String,
        val scheduledAt: Instant,
    ) : LogEvent

    /**
     * The ring start reached [stage], [msSinceScheduled] milliseconds after the time the alarm was armed for (null when
     * this process has seen no alarm fire yet). NFR-1 wants the sound within 2 s; `adb logcat -s YawnAndPawn` shows
     * each stage.
     */
    data class WakeTiming(
        val stage: WakeStage,
        val msSinceScheduled: Long?,
    ) : LogEvent
}

/** The steps of a ring start that [LogEvent.WakeTiming] times, in the order they normally happen. */
enum class WakeStage {
    /** The alarm receiver got the fire (`onReceive`). */
    ReceiverReceived,

    /** The fire handler asked for the wake service (`startForegroundService` returned). */
    ServiceStartRequested,

    /** `WakeService.onCreate`. */
    ServiceCreated,

    /** `WakeService.onStartCommand` began. */
    StartCommand,

    /** `startForeground` returned: the ringing notification is posted. */
    InForeground,

    /** The engine committed the new session (its first effect runs). */
    SessionCommitted,

    /** The wake runtime applied `SoundAt`: the player was asked to play. */
    SoundRequested,

    /** The sound is prepared, so it starts playing. */
    SoundStarted,

    /** `WakeActivity.onCreate`. */
    WakeScreenCreated,
}

/** Log text for [this] error: the storage cause, the missing id or the rejected field. */
fun DomainError.diagnostic(): String =
    when (this) {
        is DomainError.InvalidAlarm -> "invalid alarm field $field"
        is DomainError.InvalidSetting -> "invalid setting $field"
        is DomainError.NotFound -> "not found: $id"
        is DomainError.RecordNotReusable -> "record not reusable: $status"
        is DomainError.BillingUnavailable -> "billing unavailable: $cause"
        is DomainError.StorageFailure -> "storage failure: $cause"
        DomainError.ExactAlarmNotPermitted -> "exact alarms not permitted"
        is DomainError.SchedulerFailure -> "scheduler failure: $cause"
        DomainError.SessionActive -> "a session is active"
        is DomainError.CurrencyMismatch -> "currency mismatch: $left and $right"
        is DomainError.InvalidCurrency -> "invalid currency: $currency"
        is DomainError.InvalidFee -> "invalid fee: tier $baseFeeTier, snooze $snoozeNumber"
        is DomainError.ProductDetailsFailed -> "product details failed (${persistence()}): $cause"
        is DomainError.BackgroundWorkFailure -> "background work failure: $cause"
    }

/** "transient" or "permanent": kept out of [diagnostic], which lists every error kind. */
private fun DomainError.ProductDetailsFailed.persistence(): String = if (transient) "transient" else "permanent"
