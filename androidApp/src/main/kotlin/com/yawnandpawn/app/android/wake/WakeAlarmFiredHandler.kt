package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.log.WakeStage
import com.yawnandpawn.app.core.session.SessionSlotRearm
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The production [AlarmFiredHandler] (Story 1.14): the ring first, then the scheduling (device test round 1: the ring
 * start no longer waits for the re-arm).
 *
 * - A stored alarm: read whether it is enabled and, if it exists and is, start [WakeService] with its id and scheduled
 *   time; then let [schedule] (`RearmOnFire`) re-arm its next occurrence or switch a one-time alarm off. A disabled or
 *   deleted alarm does not ring (`RearmOnFire` logs it). An alarm that cannot be read (storage failure) still starts the
 *   service, which rings the emergency default rather than stay silent (NFR-2).
 * - The service start never depends on the read going well: it runs in `finally`, so a read that throws or overruns the
 *   receiver's budget (and is cancelled) still rings. Until the read says otherwise the alarm rings.
 * - The session slot: start the service with the slot action and the alarm the slot stands for, if any.
 * - A refused start of an alarm or the slot (Story 2.1): [rearm] arms the session slot one heartbeat later carrying the
 *   alarm, so its fire tries the start again (`SessionSlotRearm.afterRefusedStart`).
 * - The test alarm (Story 1.18): start the service with the test action; it rings the pending test config. Nothing is
 *   re-armed (a test rings once).
 * - The re-arm runs even when the read or the start throws.
 * - After a start, the fire is not handled until [WakeService] took that start (its [WakeServiceStarts] token), at
 *   most [WakeServiceStarts.wait] from the fire's start, so the receiver's budget never cuts the wait: the receiver's
 *   broadcast stays open meanwhile, so the phone does not freeze the process between the start and `onStartCommand`.
 *   A wait that times out is logged; the ring still comes (the service, or the session slot).
 */
class WakeAlarmFiredHandler(
    private val repository: AlarmRepository,
    private val schedule: AlarmFiredHandler,
    private val starter: WakeServiceStarter,
    private val logger: Logger,
    private val starts: WakeServiceStarts,
    private val timings: WakeTimings = WakeTimings.None,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val rearm: SessionSlotRearm,
) : AlarmFiredHandler {
    override suspend fun onAlarmFired(fired: AlarmFired) {
        val begin = timeSource.markNow()
        val token = starts.newToken()
        var started = false
        try {
            var rings = true
            try {
                rings = rings(fired)
            } finally {
                if (rings) started = startOrRearm(fired, token)
            }
        } finally {
            // The re-arm never depends on the read or the start: a start that throws still re-arms the next occurrence.
            try {
                schedule.onAlarmFired(fired)
            } finally {
                // Not after a cancellation (the budget is spent): the broadcast finishes now.
                if (started && currentCoroutineContext().isActive) awaitService(token, begin)
            }
        }
    }

    override suspend fun onSessionSlotFired(
        alarm: AlarmFired?,
        retrySince: Instant?,
    ) {
        val begin = timeSource.markNow()
        val token = starts.newToken()
        if (starter.startSlot(token, alarm, retrySince)) awaitService(token, begin) else rearm.afterRefusedStart(alarm, retrySince)
    }

    override suspend fun onTestAlarmFired() {
        val begin = timeSource.markNow()
        val token = starts.newToken()
        // No session slot backs a test up: a refused start means this test does not ring.
        if (start { starter.startTest(token) }) {
            awaitService(token, begin)
        } else {
            logger.log(LogEvent.OperationFailed("start test alarm", "service start refused; the test does not ring"))
        }
    }

    /**
     * Starts the service for [fired]; a refused start (Story 2.1) arms the session slot carrying the alarm, so its fire
     * starts the service again. The re-arm runs even after the read threw or ran out of budget, and is not cancellable.
     */
    private suspend fun startOrRearm(
        fired: AlarmFired,
        token: Long,
    ): Boolean {
        var started = false
        try {
            started = start { starter.startAlarm(fired, token) }
        } finally {
            if (!started) withContext(NonCancellable) { rearm.afterRefusedStart(fired) }
        }
        return started
    }

    /** Whether the stored alarm rings: it is enabled, or it cannot be read (the service then rings the default). */
    private suspend fun rings(fired: AlarmFired): Boolean =
        when (val stored = repository.get(fired.alarmId)) {
            is Outcome.Success -> stored.value.enabled
            is Outcome.Failure -> stored.error !is DomainError.NotFound
        }

    private inline fun start(request: () -> Boolean): Boolean = request().also { timings.stage(WakeStage.ServiceStartRequested) }

    /** Waits for the start [token] until [WakeServiceStarts.wait] after the fire began at [begin] (the re-arm counts). */
    private suspend fun awaitService(
        token: Long,
        begin: TimeMark,
    ) {
        if (!starts.awaitStart(token, starts.wait - begin.elapsedNow())) {
            logger.log(LogEvent.OperationFailed("wait for wake service", "no onStartCommand within ${starts.wait}; the broadcast finishes"))
        }
    }
}
