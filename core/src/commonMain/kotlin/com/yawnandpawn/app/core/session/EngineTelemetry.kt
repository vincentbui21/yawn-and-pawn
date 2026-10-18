package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.telemetry.TelemetryReporter
import com.yawnandpawn.app.core.telemetry.sessionTelemetry

/**
 * [SessionEngine]'s side of usage stats (Story 5.6, AD-15): once `Recorded` has committed the end of a Completed or
 * Missed session, its pure [sessionTelemetry] is handed to [reporter] (consent is checked where it is sent). The report
 * never suspends ([TelemetryReporter]), so the engine's lock never waits for a consent read or an Analytics call
 * (review fix 1). A reporter that throws is logged by type name and the engine goes on.
 */
internal class EngineTelemetry(
    private val reporter: TelemetryReporter,
    private val logger: Logger,
) {
    /**
     * The step from [from] by [event] to [to] has committed; a `Recorded` that ended a session reports its stats. An
     * ignored `Recorded` (the state did not change) reports nothing.
     */
    fun committed(
        event: SessionEvent,
        from: SessionState,
        to: SessionState,
    ) {
        val ended = from.takeIf { event is SessionEvent.Recorded && to != from }
        val events =
            when (ended) {
                is SessionState.Completed -> sessionTelemetry(ended.session, SessionEnd.Completed)
                is SessionState.Missed -> sessionTelemetry(ended.session, SessionEnd.Missed)
                else -> emptyList()
            }
        if (events.isEmpty()) return
        runCatching { reporter.report(events) }.onFailure { e ->
            logger.log(LogEvent.OperationFailed(TelemetryReporter.OPERATION, e::class.simpleName ?: "Throwable"))
        }
    }
}
