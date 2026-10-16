package com.yawnandpawn.app.core.telemetry

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where `SessionEngine` hands the usage stats of a session that ended (Story 5.6, AD-15). It never suspends: the engine
 * calls it inside its Mutex, and a consent read or an Analytics call there would hold back every later step, a new
 * alarm's first ring included (review fix 1). The [Telemetry] calls themselves run elsewhere ([Launching]).
 */
fun interface TelemetryReporter {
    fun report(events: List<TelemetryEvent>)

    /**
     * Launches [telemetry] on [scope] (the app-wide scope) for [events], in order, and returns at once. A throwing
     * telemetry and a cancelled one are logged by type name, never thrown: the session is already committed.
     */
    class Launching(
        private val telemetry: Telemetry,
        private val scope: CoroutineScope,
        private val logger: Logger,
    ) : TelemetryReporter {
        override fun report(events: List<TelemetryEvent>) {
            scope.launch { send(events) }
        }

        @Suppress("TooGenericExceptionCaught") // An adapter call: whatever it throws is logged by type name only.
        private suspend fun send(events: List<TelemetryEvent>) {
            try {
                events.forEach { telemetry.log(it) }
            } catch (e: CancellationException) {
                // The scope ended (the process is going away): logged, and the cancellation ends this job only.
                logger.log(LogEvent.OperationFailed(OPERATION, "cancelled"))
                throw e
            } catch (e: Throwable) {
                logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName ?: "Throwable"))
            }
        }
    }

    companion object {
        /** Reports nothing (engines without usage stats, and tests). */
        val None: TelemetryReporter = TelemetryReporter {}

        /** The log operation of a failed report. */
        const val OPERATION = "log usage stats"
    }
}
