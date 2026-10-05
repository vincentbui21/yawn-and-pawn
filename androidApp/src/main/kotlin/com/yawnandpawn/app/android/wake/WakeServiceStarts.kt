package com.yawnandpawn.app.android.wake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Lets the alarm receiver keep its broadcast open until [WakeService] took the start it asked for (device test round 1):
 * on some phones the process is frozen as soon as the receiver finishes, so a service start requested just before
 * waits seconds for `onStartCommand`. The fire handler takes a [newToken], puts it on its start intent
 * ([WakeService.EXTRA_START_TOKEN]) and waits in [awaitStart]; the service calls [onStartCommandReached] with the
 * intent's token once `startForeground` returned (or was refused). Only that start ends the wait, never a restore, a
 * slot or another fire's start. The wait is at most [wait] from the fire's start, well inside the receiver's 8 s
 * budget. A Koin `single`.
 */
class WakeServiceStarts(
    val wait: Duration = WAIT,
) {
    private val tokens = AtomicLong(0)

    /** The tokens whose start reached the service, the latest [KEPT] only. */
    private val reached = MutableStateFlow<List<Long>>(emptyList())

    /** A token for one start, unique in this process. */
    fun newToken(): Long = tokens.incrementAndGet()

    /** [WakeService.onStartCommand] is past `startForeground` (or the platform refused it) for the start [token]. */
    fun onStartCommandReached(token: Long) {
        reached.update { (it + token).takeLast(KEPT) }
    }

    /**
     * Suspends until the start [token] reached the service, at most [timeout]; true at once when it already did, false
     * when it did not in time.
     */
    suspend fun awaitStart(
        token: Long,
        timeout: Duration = wait,
    ): Boolean {
        if (token in reached.value) return true
        return withTimeoutOrNull(timeout) { reached.first { token in it } } != null
    }

    companion object {
        /** Leaves the re-arm and the broadcast's own work room inside the receiver's 8 s budget. */
        val WAIT: Duration = 6.seconds

        /** Starts that end a wait arrive within seconds; a few remembered ones cover any overlap. */
        private const val KEPT = 16
    }
}
