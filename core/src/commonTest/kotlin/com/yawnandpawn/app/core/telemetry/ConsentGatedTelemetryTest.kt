package com.yawnandpawn.app.core.telemetry

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Story 5.6 (AD-15): no event without consent, none queued for later, and an unreadable consent is no consent. */
class ConsentGatedTelemetryTest {
    /** In memory, like `FakeConsentStore` (core cannot depend on :testing). */
    private class MemoryConsentStore : ConsentStore {
        val stored = MutableStateFlow(AnalyticsConsent())
        var failure: DomainError? = null

        override fun observe(): Flow<AnalyticsConsent> = stored

        override suspend fun setConsent(on: Boolean): Outcome<Unit, DomainError> {
            failure?.let { return Outcome.Failure(it) }
            stored.value = AnalyticsConsent(consent = on, asked = true)
            return Outcome.Success(Unit)
        }
    }

    private val delivered = mutableListOf<TelemetryEvent>()
    private val delegate = Telemetry { delivered += it }
    private val store = MemoryConsentStore()

    @Test
    fun `three events before consent and one after deliver only the one after`() =
        runTest {
            val telemetry = ConsentGatedTelemetry(delegate, store)
            repeat(3) { telemetry.log(TelemetryEvent.SnoozeCount(it)) }

            SetAnalyticsConsent(store)(true)
            telemetry.log(TelemetryEvent.SnoozeCount(9))

            assertEquals(listOf<TelemetryEvent>(TelemetryEvent.SnoozeCount(9)), delivered)
        }

    @Test
    fun `turning consent off stops delivery at once`() =
        runTest {
            val telemetry = ConsentGatedTelemetry(delegate, store)
            SetAnalyticsConsent(store)(true)
            telemetry.log(TelemetryEvent.SnoozeCount(1))

            SetAnalyticsConsent(store)(false)
            telemetry.log(TelemetryEvent.SnoozeCount(2))

            assertEquals(listOf<TelemetryEvent>(TelemetryEvent.SnoozeCount(1)), delivered)
        }

    @Test
    fun `a consent that cannot be read counts as no consent`() =
        runTest {
            val broken =
                object : ConsentStore {
                    override fun observe(): Flow<AnalyticsConsent> = flow { throw IllegalStateException("disk unreadable") }

                    override suspend fun setConsent(on: Boolean): Outcome<Unit, DomainError> = Outcome.Success(Unit)
                }

            ConsentGatedTelemetry(delegate, broken).log(TelemetryEvent.SnoozeCount(1))

            assertEquals(emptyList(), delivered)
        }

    @Test
    fun `any choice marks the user as asked, and a failed write is returned and stores nothing`() =
        runTest {
            assertEquals(Outcome.Success(Unit), SetAnalyticsConsent(store)(false))
            assertEquals(AnalyticsConsent(consent = false, asked = true), store.stored.value)

            store.failure = DomainError.StorageFailure("disk full")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), SetAnalyticsConsent(store)(true))
            assertEquals(AnalyticsConsent(consent = false, asked = true), store.stored.value)
        }

    @Test
    fun `None sends nothing and never fails`() =
        runTest {
            Telemetry.None.log(TelemetryEvent.SnoozeCount(1))
        }
}
