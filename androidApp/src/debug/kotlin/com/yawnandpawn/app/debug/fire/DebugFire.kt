package com.yawnandpawn.app.debug.fire

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The debug fire-now hook (Story 1.18, debug builds only): rings an alarm [FireRequest.seconds] from now through the
 * real [AlarmScheduler], as a real session or a test.
 * - Test: the stored alarm's values (or a synthetic default) through [ScheduleTestAlarm].
 * - Real, with an alarm id: that alarm armed under its own request code; when it fires, its schedule is put right again
 *   by the normal fire path. A disabled alarm does not ring (the fire path ignores it).
 * - Real, no alarm id: a one-time alarm "Debug fire" is saved first, then armed the same way.
 */
class DebugFire(
    private val repository: AlarmRepository,
    private val saveAlarm: SaveAlarm,
    private val scheduler: AlarmScheduler,
    private val scheduleTest: ScheduleTestAlarm,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
) {
    /** What `adb shell am broadcast -a com.yawnandpawn.app.debug.FIRE` asked for. */
    data class FireRequest(
        val seconds: Int = DEFAULT_SECONDS,
        val alarmId: String? = null,
        val test: Boolean = false,
    )

    /** Arms [request]; returns when it rings. */
    suspend fun fire(request: FireRequest): Outcome<Instant, DomainError> {
        val seconds = request.seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        val stored =
            request.alarmId?.let { id ->
                when (val read = repository.get(id)) {
                    is Outcome.Success -> read.value
                    is Outcome.Failure -> return read
                }
            }
        return if (request.test) {
            scheduleTest(stored?.toDraft() ?: syntheticDraft(), delay = seconds.seconds)
        } else {
            val alarm: Outcome<Alarm, DomainError> = stored?.let { Outcome.Success(it) } ?: saveAlarm(syntheticDraft())
            val ringsAt = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds() + seconds * MILLIS_PER_SECOND)
            alarm.flatMap { armed ->
                when (val result = scheduler.schedule(armed.id, armed.requestCode, ringsAt.toEpochMilliseconds())) {
                    is Outcome.Success -> Outcome.Success(ringsAt)
                    is Outcome.Failure -> result
                }
            }
        }
    }

    /** A one-time alarm with the defaults at the current minute, labelled so it is easy to spot (and delete). */
    private fun syntheticDraft(): AlarmDraft {
        val now = clock.now().toLocalDateTime(timeZoneProvider.current()).time
        return AlarmDraft(time = LocalTime(now.hour, now.minute), label = LABEL)
    }

    private fun Alarm.toDraft(): AlarmDraft =
        AlarmDraft(
            id = id,
            time = time,
            repeatDays = repeatDays,
            label = label,
            enabled = enabled,
            soundRef = soundRef,
            volumePercent = volumePercent,
            gradualVolume = gradualVolume,
            rampStartPercent = rampStartPercent,
            vibration = vibration,
            snoozeLengthMinutes = snoozeLengthMinutes,
            graceSeconds = graceSeconds,
        )

    companion object {
        const val ACTION = "com.yawnandpawn.app.debug.FIRE"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_ALARM_ID = "alarmId"
        const val EXTRA_TEST = "test"
        const val DEFAULT_SECONDS = 10
        const val LABEL = "Debug fire"
        private const val MIN_SECONDS = 1
        private const val MAX_SECONDS = 3_600
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
