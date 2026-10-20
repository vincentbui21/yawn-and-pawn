package com.yawnandpawn.app.core.telemetry

/**
 * Port: deletes the usage stats data and its id on the device ("Delete all data", Story 5.9 with 5.6): Firebase's
 * `resetAnalyticsData`, so later stats can never be linked to earlier ones. It does nothing before Firebase has started
 * or without a Firebase configuration, and never throws (a failure is logged).
 */
fun interface AnalyticsDataReset {
    fun reset()
}
