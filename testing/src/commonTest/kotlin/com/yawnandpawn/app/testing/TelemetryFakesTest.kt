package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.core.telemetry.TelemetryEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TelemetryFakesTest {
    @Test
    fun `the fake telemetry records every event in order`() =
        runTest {
            val telemetry = FakeTelemetry()

            telemetry.log(TelemetryEvent.SnoozeCount(1))
            telemetry.log(TelemetryEvent.SnoozeCount(2))

            assertEquals(listOf<TelemetryEvent>(TelemetryEvent.SnoozeCount(1), TelemetryEvent.SnoozeCount(2)), telemetry.events)
        }

    @Test
    fun `the fake consent store starts off, stores a choice as asked, and a failure stores nothing`() =
        runTest {
            val store = FakeConsentStore()
            assertEquals(AnalyticsConsent(), store.observe().first())

            assertEquals(Outcome.Success(Unit), store.setConsent(true))
            assertEquals(AnalyticsConsent(consent = true, asked = true), store.current)

            store.setFailure = DomainError.StorageFailure("disk full")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), store.setConsent(false))

            assertEquals(AnalyticsConsent(consent = true, asked = true), store.observe().first())
            assertEquals(listOf(true, false), store.requests)
        }
}
