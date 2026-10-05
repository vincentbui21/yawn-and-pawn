package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.WakeStage
import com.yawnandpawn.app.testing.FakeLogger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Device test round 1 review: the ring-start timing logs only the stages of a fire being timed, each once. */
class WakeTimingsTest {
    private val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")
    private var now = scheduledAt + 120.milliseconds
    private val logger = FakeLogger()
    private val timings = WakeTimings(now = { now }, logger = logger)

    @Test
    fun `each stage of a fire is logged once, and a later re-ring of the same session logs no stale time`() {
        timings.fired(scheduledAt)
        timings.stage(WakeStage.SessionCommitted)
        timings.stage(WakeStage.SoundRequested)
        timings.stage(WakeStage.SoundStarted)
        timings.stage(WakeStage.SoundRequested)

        // The snooze ends an hour later: the same session rings again.
        now = scheduledAt + 1.hours
        timings.stage(WakeStage.SoundRequested)
        timings.stage(WakeStage.SoundStarted)

        assertEquals(
            listOf<LogEvent>(
                LogEvent.WakeTiming(WakeStage.SessionCommitted, 120),
                LogEvent.WakeTiming(WakeStage.SoundRequested, 120),
                LogEvent.WakeTiming(WakeStage.SoundStarted, 120),
            ),
            logger.events,
        )
    }

    @Test
    fun `nothing is logged before a fire or after the session ended, and the next fire is timed again`() {
        timings.stage(WakeStage.ServiceCreated)
        timings.fired(scheduledAt)
        timings.stage(WakeStage.SoundRequested)
        timings.sessionEnded()

        // A restore or a heartbeat slot later on: not timed.
        timings.stage(WakeStage.SessionCommitted)
        timings.stage(WakeStage.SoundRequested)

        val next = scheduledAt + 24.hours
        now = next + 50.milliseconds
        timings.fired(next)
        timings.stage(WakeStage.SoundRequested)

        assertEquals(
            listOf<LogEvent>(LogEvent.WakeTiming(WakeStage.SoundRequested, 120), LogEvent.WakeTiming(WakeStage.SoundRequested, 50)),
            logger.events,
        )
    }
}
