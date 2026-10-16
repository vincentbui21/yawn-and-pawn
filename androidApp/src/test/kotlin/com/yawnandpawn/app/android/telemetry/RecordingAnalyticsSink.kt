package com.yawnandpawn.app.android.telemetry

import java.util.Collections

/**
 * An [AnalyticsSink] that records every call in order, in [calls]; [failure] makes every call throw it. Its lists are
 * synchronized: the app calls it from its own threads (the Firebase start, the consent gate, the launched telemetry).
 */
internal class RecordingAnalyticsSink(
    override var isReady: Boolean = true,
) : AnalyticsSink {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The logged events, as name and parameters. */
    val events: MutableList<Pair<String, Map<String, AnalyticsParam>>> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var failure: Exception? = null

    private fun record(call: String) {
        failure?.let { throw it }
        calls += call
    }

    override fun setCollectionEnabled(enabled: Boolean) = record("collection $enabled")

    override fun grantAnalyticsStorageOnly() = record("grant analytics storage only")

    override fun resetData() = record("reset")

    override fun logEvent(
        name: String,
        params: Map<String, AnalyticsParam>,
    ) {
        record("log $name")
        events += name to params
    }
}
