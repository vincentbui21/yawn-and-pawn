package com.yawnandpawn.app.core.telemetry

import com.yawnandpawn.app.core.checks.CheckType
import kotlinx.serialization.Serializable
import com.yawnandpawn.app.core.history.SessionOutcome as HistoryOutcome

/**
 * Port for anonymous usage stats (AD-15, FR-SET-6, Story 5.6). Production binds [ConsentGatedTelemetry] over the
 * Firebase Analytics adapter, so nothing is sent without the user's consent; [None] where there is no Analytics.
 *
 * [log] never throws for an expected failure: an event that cannot be sent now is dropped, never queued for later.
 */
fun interface Telemetry {
    suspend fun log(event: TelemetryEvent)

    companion object {
        /** Sends nothing: no Analytics in this build, or a test that is not about telemetry. */
        val None: Telemetry = Telemetry { }
    }
}

/**
 * The only three events the app ever sends (EXPERIENCE.md, AD-15): each carries one integer or one enum, never a user
 * id, a label, a time or any other free text. `@Serializable` only so a test can read every field's kind from the
 * descriptor; nothing serializes it.
 */
@Serializable
sealed interface TelemetryEvent {
    /** How a real (non-test) session ended. */
    @Serializable
    data class SessionOutcome(
        val outcome: HistoryOutcome,
    ) : TelemetryEvent

    /** The paid snoozes of a session that ended. */
    @Serializable
    data class SnoozeCount(
        val count: Int,
    ) : TelemetryEvent

    /** A check type the session's plan used; one event per distinct type. */
    @Serializable
    data class CheckTypeUsed(
        val type: TelemetryCheckType,
    ) : TelemetryEvent
}

/**
 * The check types usage stats name. Only the checks a user can pick are here; any other [CheckType] (the test-only
 * Placeholder, a type added later) is not logged until it is added.
 */
enum class TelemetryCheckType {
    Math,
    WordUnscramble,
    MemorySequence,
    QrBarcode,
    ;

    companion object {
        /** The usage stats name of [type], or null when it is not logged. */
        fun of(type: CheckType): TelemetryCheckType? = entries.firstOrNull { it.name == type.id }
    }
}
