package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.log.WakeStage
import kotlin.time.Instant

/**
 * Times the ring start (device test round 1, NFR-1: sound within 2 s): an alarm (or test) fire sets the time it was
 * armed for ([fired]), and each later [stage] of that ring start is logged once as [LogEvent.WakeTiming] with the
 * milliseconds since then, so `adb logcat -s YawnAndPawn` shows where the time goes. Nothing is logged outside a timed
 * fire: not before the first one, not for the 60 s heartbeat slot, and not after the session ended ([sessionEnded]),
 * so a later re-ring (snooze end, restore) never logs a stale time. A Koin `single`; [None] logs nothing.
 */
class WakeTimings(
    private val now: () -> Instant,
    private val logger: Logger,
) {
    private val lock = Any()
    private var scheduledAt: Instant? = null
    private val logged = mutableSetOf<WakeStage>()

    /** An alarm (or test) fire armed for [scheduledAt] arrived: its stages count from it. */
    fun fired(scheduledAt: Instant) =
        synchronized(lock) {
            this.scheduledAt = scheduledAt
            logged.clear()
        }

    /** The ring start of the timed fire reached [stage]; logged the first time only. */
    fun stage(stage: WakeStage) {
        val since =
            synchronized(lock) {
                val at = scheduledAt ?: return
                if (!logged.add(stage)) return
                (now() - at).inWholeMilliseconds
            }
        logger.log(LogEvent.WakeTiming(stage, since))
    }

    /** The session ended: nothing is timed until the next fire. */
    fun sessionEnded() =
        synchronized(lock) {
            scheduledAt = null
            logged.clear()
        }

    companion object {
        /** Logs nothing. */
        val None = WakeTimings(now = { Instant.DISTANT_PAST }, logger = {})
    }
}
