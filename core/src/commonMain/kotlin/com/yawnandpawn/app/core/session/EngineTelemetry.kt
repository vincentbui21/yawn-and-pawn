package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.telemetry.Telemetry
import com.yawnandpawn.app.core.telemetry.sessionTelemetry
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [SessionEngine]'s side of usage stats (Story 5.6, AD-15): once `Recorded` has committed the end of a Completed or
 * Missed session, its [sessionTelemetry] goes to [telemetry] (consent is checked there). Telemetry never holds a session
 * up: whatever it throws is logged by type name and the engine goes on, and it is given at most [TIMEOUT], so a new
 * alarm waiting behind the lock never waits long for it.
 */
internal class EngineTelemetry(
    private val telemetry: Telemetry,
    private val logger: Logger,
) {
    /**
     * The step from [from] by [event] to [to] has committed; a `Recorded` that ended a session sends its stats. An
     * ignored `Recorded` (the state did not change) sends nothing.
     */
    suspend fun committed(
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
        if (events.isNotEmpty()) send { events.forEach { telemetry.log(it) } }
    }

    // An adapter call: whatever it throws (errors too) is logged by type name only; cancellation still propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun send(block: suspend () -> Unit) {
        try {
            if (withTimeoutOrNull(TIMEOUT) { block() } == null) logger.log(LogEvent.OperationFailed(OPERATION, "timed out"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName ?: "Throwable"))
        }
    }

    private companion object {
        const val OPERATION = "log usage stats"
        val TIMEOUT: Duration = 2.seconds
    }
}
