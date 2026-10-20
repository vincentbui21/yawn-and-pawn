package com.yawnandpawn.app.android.telemetry

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.telemetry.AnalyticsDataReset

/**
 * [AnalyticsDataReset] on the [AnalyticsSink] ("Delete all data", Story 5.9 with 5.6): `resetAnalyticsData` once
 * Firebase has started; before that (or without a Firebase configuration) there is nothing on the device to reset. Never
 * throws: a failing call, or Analytics missing from the classpath, is logged by type name.
 */
class SinkAnalyticsDataReset(
    private val sink: AnalyticsSink,
    private val logger: Logger,
) : AnalyticsDataReset {
    @Suppress("TooGenericExceptionCaught")
    override fun reset() {
        try {
            if (sink.isReady) sink.resetData()
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName.orEmpty()))
        } catch (e: LinkageError) {
            logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName.orEmpty()))
        }
    }

    companion object {
        const val OPERATION = "reset usage stats data"
    }
}
