package com.yawnandpawn.app.android.telemetry

import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.core.telemetry.ConsentGatedTelemetry
import com.yawnandpawn.app.core.telemetry.TelemetryCheckType
import com.yawnandpawn.app.core.telemetry.TelemetryEvent
import com.yawnandpawn.app.testing.FakeConsentStore
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Story 5.6: the three Analytics events, one parameter each, and nothing before Firebase starts or without consent. */
@RunWith(RobolectricTestRunner::class)
class FirebaseTelemetryTest {
    private val sink = RecordingAnalyticsSink()
    private val logger = FakeLogger()

    @Test
    fun `each event is one Analytics event with one integer or enum-name parameter`() =
        runTest {
            val telemetry = FirebaseTelemetry(sink, logger)

            telemetry.log(TelemetryEvent.SessionOutcome(SessionOutcome.Snoozed))
            telemetry.log(TelemetryEvent.SnoozeCount(2))
            telemetry.log(TelemetryEvent.CheckTypeUsed(TelemetryCheckType.QrBarcode))

            assertEquals(
                listOf(
                    "session_outcome" to mapOf<String, AnalyticsParam>("outcome" to AnalyticsParam.Name("Snoozed")),
                    "snooze_count" to mapOf<String, AnalyticsParam>("count" to AnalyticsParam.Number(2)),
                    "check_type" to mapOf<String, AnalyticsParam>("type" to AnalyticsParam.Name("QrBarcode")),
                ),
                sink.events,
            )
            assertEquals(emptyList(), logger.events)
        }

    @Test
    fun `before Firebase has started an event is dropped, not queued`() =
        runTest {
            sink.isReady = false
            val telemetry = FirebaseTelemetry(sink, logger)

            telemetry.log(TelemetryEvent.SnoozeCount(1))
            sink.isReady = true
            telemetry.log(TelemetryEvent.SnoozeCount(2))

            assertEquals(listOf("log snooze_count"), sink.calls)
            assertEquals(
                listOf<Pair<String, Map<String, AnalyticsParam>>>("snooze_count" to mapOf("count" to AnalyticsParam.Number(2))),
                sink.events,
            )
        }

    @Test
    fun `a failing Analytics is logged by type and never thrown`() =
        runTest {
            sink.failure = IllegalStateException("not initialized")

            FirebaseTelemetry(sink, logger).log(TelemetryEvent.SnoozeCount(1))

            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("log usage stats", "IllegalStateException")), logger.events)
        }

    @Test
    fun `behind the consent gate nothing reaches Analytics until the user says yes`() =
        runTest {
            val consents = FakeConsentStore()
            val telemetry = ConsentGatedTelemetry(FirebaseTelemetry(sink, logger), consents)
            repeat(3) { telemetry.log(TelemetryEvent.SnoozeCount(it)) }

            consents.setConsent(true)
            telemetry.log(TelemetryEvent.SnoozeCount(7))

            assertEquals(AnalyticsConsent(consent = true, asked = true), consents.current)
            assertEquals(listOf("log snooze_count"), sink.calls)
        }
}
