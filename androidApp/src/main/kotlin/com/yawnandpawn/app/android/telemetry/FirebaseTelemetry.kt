package com.yawnandpawn.app.android.telemetry

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.telemetry.Telemetry
import com.yawnandpawn.app.core.telemetry.TelemetryEvent

/**
 * The Firebase Analytics [Telemetry] (Story 5.6, AD-15), always behind `ConsentGatedTelemetry`. Each event becomes one
 * of the three Analytics events with one parameter: `session_outcome` (`outcome`), `snooze_count` (`count`) and
 * `check_type` (`type`); an outcome or a check type is sent as its enum name. Before Firebase has started an event is
 * dropped, never queued. Never throws: a failing Analytics is logged by type name.
 */
class FirebaseTelemetry(
    private val sink: AnalyticsSink,
    private val logger: Logger,
) : Telemetry {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun log(event: TelemetryEvent) {
        try {
            if (!sink.isReady) return
            val (name, params) = analyticsEventOf(event)
            sink.logEvent(name, params)
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName.orEmpty()))
        } catch (e: LinkageError) {
            logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName.orEmpty()))
        }
    }

    companion object {
        const val SESSION_OUTCOME = "session_outcome"
        const val SNOOZE_COUNT = "snooze_count"
        const val CHECK_TYPE = "check_type"
        const val PARAM_OUTCOME = "outcome"
        const val PARAM_COUNT = "count"
        const val PARAM_TYPE = "type"

        private const val OPERATION = "log usage stats"

        /** The Analytics event name and parameters of [event]. */
        fun analyticsEventOf(event: TelemetryEvent): Pair<String, Map<String, AnalyticsParam>> =
            when (event) {
                is TelemetryEvent.SessionOutcome -> SESSION_OUTCOME to mapOf(PARAM_OUTCOME to AnalyticsParam.Name(event.outcome.name))
                is TelemetryEvent.SnoozeCount -> SNOOZE_COUNT to mapOf(PARAM_COUNT to AnalyticsParam.Number(event.count.toLong()))
                is TelemetryEvent.CheckTypeUsed -> CHECK_TYPE to mapOf(PARAM_TYPE to AnalyticsParam.Name(event.type.name))
            }
    }
}
