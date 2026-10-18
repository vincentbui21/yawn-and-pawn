package com.yawnandpawn.app.core.telemetry

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionLockGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

/**
 * The user's usage stats choice (FR-ONB-6, FR-SET-6): [consent] to share anonymous usage stats, off until the user
 * says yes, and whether the user was [asked] (any choice, in onboarding or Settings, marks it), so onboarding asks once.
 */
data class AnalyticsConsent(
    val consent: Boolean = false,
    val asked: Boolean = false,
)

/**
 * Port for the usage stats choice, kept in the device-protected settings DataStore by `:data`. A read failure reads
 * as the default [AnalyticsConsent] (no consent): stats are never sent because a file could not be read.
 */
interface ConsentStore {
    /** The stored choice; emits again after every change. */
    fun observe(): Flow<AnalyticsConsent>

    /** Stores [on] as the choice and marks the user as asked, in one write. */
    suspend fun setConsent(on: Boolean): Outcome<Unit, DomainError>
}

/**
 * Turns usage stats on or off (Settings switch now, the onboarding step in Story 5.11). Applies at once, no Save. Like
 * every user-facing write it runs inside [SessionLockGuard.whenIdle] (Story 2.6): during a session it changes nothing
 * and returns [DomainError.SessionActive] (Settings and onboarding are behind the session lock then anyway).
 */
class SetAnalyticsConsent(
    private val store: ConsentStore,
    private val sessionLock: SessionLockGuard,
) {
    suspend operator fun invoke(on: Boolean): Outcome<Unit, DomainError> = sessionLock.whenIdle { store.setConsent(on) }
}

/**
 * [Telemetry] that passes an event to [delegate] only while the stored consent is on (AD-15). An event logged without
 * consent is dropped for good, never queued for a later yes. A consent that cannot be read counts as no consent.
 */
class ConsentGatedTelemetry(
    private val delegate: Telemetry,
    private val store: ConsentStore,
) : Telemetry {
    override suspend fun log(event: TelemetryEvent) {
        if (consented()) delegate.log(event)
    }

    // A store that throws (an IO error, a bug) means no consent; cancellation still propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun consented(): Boolean =
        try {
            store.observe().first().consent
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            false
        }
}
