package com.yawnandpawn.app.android.telemetry

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeLogger
import org.junit.Test
import kotlin.test.assertEquals

/** Story 5.9 with 5.6: "Delete all data" resets the usage stats data and id once Firebase has started, and never throws. */
class SinkAnalyticsDataResetTest {
    private val logger = FakeLogger()

    @Test
    fun `before Firebase has started nothing is called, after it the data is reset`() {
        val sink = RecordingAnalyticsSink(isReady = false)
        val reset = SinkAnalyticsDataReset(sink, logger)

        reset.reset()
        assertEquals(emptyList<String>(), sink.calls)

        sink.isReady = true
        reset.reset()
        assertEquals(listOf("reset"), sink.calls)
        assertEquals(emptyList<LogEvent>(), logger.events)
    }

    @Test
    fun `a failing Analytics call is logged by type, never thrown`() {
        val sink = RecordingAnalyticsSink().apply { failure = IllegalStateException("not initialized") }

        SinkAnalyticsDataReset(sink, logger).reset()

        assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(SinkAnalyticsDataReset.OPERATION, "IllegalStateException")), logger.events)
    }
}
