package com.yawnandpawn.app.android.wake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Lets the alarm receiver keep its broadcast open until [WakeService] took the start it asked for (device test round 1):
 * on some phones the process is frozen as soon as the receiver finishes, so a service start requested just before
 * waits seconds for `onStartCommand`. The fire handler takes a [mark] before it starts the service and then waits in
 * [awaitStartAfter]; the service calls [onStartCommandReached] once `startForeground` returned (or was refused). The
 * wait is bounded by [wait], well inside the receiver's 8 s budget. A Koin `single`.
 */
class WakeServiceStarts(
    val wait: Duration = WAIT,
) {
    private val reached = MutableStateFlow(0L)

    /** How many starts reached the service so far: taken before a start, then passed to [awaitStartAfter]. */
    fun mark(): Long = reached.value

    /** [WakeService.onStartCommand] is past `startForeground` (or the platform refused it). */
    fun onStartCommandReached() {
        reached.update { it + 1 }
    }

    /** Suspends until a start reached the service after [mark], at most [wait]; false when it did not in time. */
    suspend fun awaitStartAfter(mark: Long): Boolean = withTimeoutOrNull(wait) { reached.first { it > mark } } != null

    companion object {
        /** Leaves the re-arm and the broadcast's own work room inside the receiver's 8 s budget. */
        val WAIT: Duration = 6.seconds
    }
}
