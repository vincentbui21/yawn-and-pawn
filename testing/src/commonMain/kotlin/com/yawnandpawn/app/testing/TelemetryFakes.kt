package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.core.telemetry.ConsentStore
import com.yawnandpawn.app.core.telemetry.Telemetry
import com.yawnandpawn.app.core.telemetry.TelemetryEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [Telemetry] that keeps every logged event in order, in [events]. */
class FakeTelemetry : Telemetry {
    private val received = mutableListOf<TelemetryEvent>()

    val events: List<TelemetryEvent>
        get() = received.toList()

    override suspend fun log(event: TelemetryEvent) {
        received += event
    }
}

/**
 * In-memory [ConsentStore] holding [initial]. Set [setFailure] to make [setConsent] fail (nothing is stored); every
 * requested value is kept in [requests], failed or not.
 */
class FakeConsentStore(
    initial: AnalyticsConsent = AnalyticsConsent(),
) : ConsentStore {
    private val stored = MutableStateFlow(initial)
    private val asked = mutableListOf<Boolean>()

    var setFailure: DomainError? = null

    /** The stored choice right now. */
    val current: AnalyticsConsent
        get() = stored.value

    val requests: List<Boolean>
        get() = asked.toList()

    override fun observe(): Flow<AnalyticsConsent> = stored

    override suspend fun setConsent(on: Boolean): Outcome<Unit, DomainError> {
        asked += on
        setFailure?.let { return Outcome.Failure(it) }
        stored.value = AnalyticsConsent(consent = on, asked = true)
        return Outcome.Success(Unit)
    }
}
