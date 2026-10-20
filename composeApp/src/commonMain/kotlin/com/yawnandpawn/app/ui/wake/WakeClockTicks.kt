package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The current wall time for the Ringing screen's clock (Epic 4 device follow-up 1): [clock]'s time now, then again at
 * every minute boundary, so the clock turns over with the phone's. The app's injected [Clock], never the system's, so
 * tests stay deterministic. Runs until the collector stops.
 */
fun minuteTicks(clock: Clock): Flow<Instant> =
    flow {
        while (true) {
            val now = clock.now()
            emit(now)
            delay(untilNextMinute(now))
        }
    }

/** How long from [now] to the start of the next minute: a full minute when [now] is exactly on one. */
fun untilNextMinute(now: Instant): Duration = (MINUTE_MILLIS - now.toEpochMilliseconds().mod(MINUTE_MILLIS)).milliseconds

private const val MINUTE_MILLIS = 60_000L
