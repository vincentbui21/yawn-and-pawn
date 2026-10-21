package com.yawnandpawn.app.ui.wake

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeChangeSignal
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** What the wake screen's clock shows: the time [now] and the [zone] it is shown in. */
data class WallTime(
    val now: Instant,
    val zone: TimeZone,
)

/**
 * The wall time for the Ringing screen's clock (Epic 4 device follow-up 1): [clock]'s time and [zones]' zone now, then
 * again at every [changes] signal (the system's minute tick, a clock set, a zone change; as Home does) and, as a
 * backstop, at every minute boundary of [clock]. The app's injected ports, never the system's, so tests stay
 * deterministic. Runs until the collector stops.
 */
fun wallTimes(
    clock: Clock,
    zones: TimeZoneProvider,
    changes: TimeChangeSignal,
): Flow<WallTime> =
    merge(changes.changes(), minuteBoundaries(clock))
        .onStart { emit(Unit) }
        .map { WallTime(clock.now(), zones.current()) }

/** [wallTimes] as Compose state, starting from the time now (the first frame never shows a stale time). */
@Composable
fun rememberWallTime(
    clock: Clock,
    zones: TimeZoneProvider,
    changes: TimeChangeSignal,
): WallTime =
    remember { wallTimes(clock, zones, changes) }
        .collectAsState(initial = WallTime(clock.now(), zones.current()))
        .value

/** A signal at each minute boundary of [clock]: the backstop when no system tick arrives. */
private fun minuteBoundaries(clock: Clock): Flow<Unit> =
    flow {
        while (true) {
            delay(untilNextMinute(clock.now()))
            emit(Unit)
        }
    }

/** How long from [now] to the start of the next minute: a full minute when [now] is exactly on one. */
fun untilNextMinute(now: Instant): Duration = (MINUTE_MILLIS - now.toEpochMilliseconds().mod(MINUTE_MILLIS)).milliseconds

private const val MINUTE_MILLIS = 60_000L
