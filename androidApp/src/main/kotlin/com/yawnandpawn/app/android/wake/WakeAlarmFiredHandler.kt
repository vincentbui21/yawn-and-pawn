package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome

/**
 * The production [AlarmFiredHandler] (Story 1.14): scheduling first, then the ring.
 *
 * - A stored alarm: read it, let [schedule] (`RearmOnFire`) re-arm its next occurrence or switch a one-time alarm off,
 *   then, if it existed and was enabled, start [WakeService] with its id and scheduled time. A disabled or deleted alarm
 *   does not ring (`RearmOnFire` logs it). An alarm that cannot be read (storage failure) still starts the service,
 *   which rings the emergency default rather than stay silent (NFR-2).
 * - The service start never depends on the scheduling: it runs in `finally`, so a read or a re-arm that throws or
 *   overruns the receiver's budget (and is cancelled) still rings. Until the read says otherwise the alarm rings.
 * - The session slot: start the service with the slot action.
 * - The test alarm: [schedule] logs it and nothing rings until Story 1.18.
 */
class WakeAlarmFiredHandler(
    private val repository: AlarmRepository,
    private val schedule: AlarmFiredHandler,
    private val starter: WakeServiceStarter,
) : AlarmFiredHandler {
    override suspend fun onAlarmFired(fired: AlarmFired) {
        var rings = true
        try {
            rings =
                when (val stored = repository.get(fired.alarmId)) {
                    is Outcome.Success -> stored.value.enabled
                    is Outcome.Failure -> stored.error !is DomainError.NotFound
                }
            schedule.onAlarmFired(fired)
        } finally {
            if (rings) starter.startAlarm(fired)
        }
    }

    override suspend fun onSessionSlotFired() {
        starter.startSlot()
    }

    override suspend fun onTestAlarmFired() = schedule.onTestAlarmFired()
}
