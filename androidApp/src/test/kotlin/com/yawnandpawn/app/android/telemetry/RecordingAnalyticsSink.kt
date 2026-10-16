package com.yawnandpawn.app.android.telemetry

/** An [AnalyticsSink] that records every call in order, in [calls]; [failure] makes every call throw it. */
internal class RecordingAnalyticsSink(
    override var isReady: Boolean = true,
) : AnalyticsSink {
    val calls = mutableListOf<String>()

    /** The logged events, as name and parameters. */
    val events = mutableListOf<Pair<String, Map<String, AnalyticsParam>>>()

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
