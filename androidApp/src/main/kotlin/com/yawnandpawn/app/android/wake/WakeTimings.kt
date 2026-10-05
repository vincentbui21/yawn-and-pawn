package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.log.WakeStage
import kotlin.time.Instant

/**
 * Times the ring start (device test round 1, NFR-1: sound within 2 s): the alarm receiver sets the time the fired alarm
 * was armed for ([fired]), and every later [stage] in this process is logged as [LogEvent.WakeTiming] with the
 * milliseconds since then, so `adb logcat -s YawnAndPawn` shows where the time goes. Only alarm and test fires are
 * timed, never the 60 s heartbeat slot. A Koin `single`; [None] logs nothing (for adapters built without one).
 */
class WakeTimings(
    private val now: () -> Instant,
    private val logger: Logger,
) {
    @Volatile
    private var scheduledAt: Instant? = null

    /** An alarm (or test) fire armed for [scheduledAt] arrived: the later stages count from it. */
    fun fired(scheduledAt: Instant) {
        this.scheduledAt = scheduledAt
    }

    /** The ring start reached [stage]. */
    fun stage(stage: WakeStage) {
        logger.log(LogEvent.WakeTiming(stage, scheduledAt?.let { (now() - it).inWholeMilliseconds }))
    }

    companion object {
        /** Logs nothing. */
        val None = WakeTimings(now = { Instant.DISTANT_PAST }, logger = {})
    }
}
