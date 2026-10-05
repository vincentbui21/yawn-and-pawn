package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.log.WakeStage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

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
 * - The session slot: start the service with the slot action.
 * - The test alarm (Story 1.18): start the service with the test action; it rings the pending test config. Nothing is
 *   re-armed (a test rings once).
 * - After a start, the fire is not handled until [WakeService] took it ([WakeServiceStarts], bounded): the receiver's
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
) : AlarmFiredHandler {
    override suspend fun onAlarmFired(fired: AlarmFired) {
        val mark = starts.mark()
        var rings = true
        var started = false
        try {
            rings =
                when (val stored = repository.get(fired.alarmId)) {
                    is Outcome.Success -> stored.value.enabled
                    is Outcome.Failure -> stored.error !is DomainError.NotFound
                }
        } finally {
            if (rings) started = start { starter.startAlarm(fired) }
        }
        try {
            schedule.onAlarmFired(fired)
        } finally {
            // Not after a cancellation (the budget is spent): the broadcast finishes now.
            if (started && currentCoroutineContext().isActive) awaitService(mark)
        }
    }

    override suspend fun onSessionSlotFired() {
        val mark = starts.mark()
        if (starter.startSlot()) awaitService(mark)
    }

    override suspend fun onTestAlarmFired() {
        val mark = starts.mark()
        // No session slot backs a test up: a refused start means this test does not ring.
        if (start { starter.startTest() }) {
            awaitService(mark)
        } else {
            logger.log(LogEvent.OperationFailed("start test alarm", "service start refused; the test does not ring"))
        }
    }

    private inline fun start(request: () -> Boolean): Boolean = request().also { timings.stage(WakeStage.ServiceStartRequested) }

    private suspend fun awaitService(mark: Long) {
        if (!starts.awaitStartAfter(mark)) {
            logger.log(LogEvent.OperationFailed("wait for wake service", "no onStartCommand within ${starts.wait}; the broadcast finishes"))
        }
    }
}
