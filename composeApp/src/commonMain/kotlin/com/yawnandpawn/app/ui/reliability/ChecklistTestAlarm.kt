package com.yawnandpawn.app.ui.reliability

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmWithChecks
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.alarm.nextOccurrence
import com.yawnandpawn.app.core.alarm.orderedEntries
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * "Ring a test alarm" on the reliability checklist (Story 5.4, DEFAULT TAKEN 4): a Story 1.18 test through
 * [testAlarm], 10 seconds from now, that rings like the soonest enabled alarm (by its next occurrence): its sound,
 * volume, vibration, quiet time, snooze length and checks, built as the editor's draft of it would be. With no enabled
 * alarm (or alarms that cannot be read) it rings the built-in alarm defaults with the Settings defaults of Story 5.1
 * (quiet time, its vibration and the snooze length); settings that cannot be read count as the built-in ones. Read
 * failures are logged.
 */
class ChecklistTestAlarm(
    private val checks: CheckConfigRepository,
    private val settings: GlobalSettingsRepository,
    private val testAlarm: ScheduleTestAlarm,
    private val clock: Clock,
    private val zones: TimeZoneProvider,
    private val logger: Logger,
) {
    /** Schedules the test; returns when it will ring, or why it could not be armed. */
    suspend operator fun invoke(): Outcome<Instant, DomainError> = testAlarm(draft())

    /** The draft the test rings: the soonest enabled alarm's, else the defaults. */
    suspend fun draft(): AlarmDraft {
        val now = clock.now()
        val zone = zones.current()
        val soonest = alarms().filter { it.alarm.enabled }.minByOrNull { nextOccurrence(it.alarm.toRule(), now, zone) }
        if (soonest != null) return soonest.toTestDraft()
        val defaults = defaults()
        val time = now.toLocalDateTime(zone).time
        return AlarmDraft(
            time = LocalTime(time.hour, time.minute),
            snoozeLengthMinutes = defaults.defaultSnoozeLengthMinutes,
            graceSeconds = defaults.defaultGraceSeconds,
            vibrateInGrace = defaults.defaultVibrateInGrace,
        )
    }

    // A failing read of the alarms fails their flow (CheckConfigRepository): the test then rings the defaults.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun alarms(): List<AlarmWithChecks> =
        try {
            checks.observeAlarmsWithChecks().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(READ_ALARMS, e::class.simpleName.orEmpty()))
            emptyList()
        }

    private suspend fun defaults(): GlobalSettings =
        when (val read = settings.get()) {
            is Outcome.Success -> {
                read.value
            }

            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of(READ_SETTINGS, read.error))
                GlobalSettings()
            }
        }

    companion object {
        /** The log operation of an alarms read that failed. */
        const val READ_ALARMS = "read alarms for the checklist test"

        /** The log operation of a settings read that failed. */
        const val READ_SETTINGS = "read settings for the checklist test"
    }
}

/**
 * This alarm as the editor's draft of it rings a test (Story 1.18): the fixed ramp start, a quiet time inside its range,
 * a blank sound as the default, and its checks in order (an alarm without check rows rings the default checks).
 */
internal fun AlarmWithChecks.toTestDraft(): AlarmDraft =
    AlarmDraft(
        id = alarm.id,
        time = alarm.time,
        repeatDays = alarm.repeatDays,
        label = alarm.label,
        enabled = true,
        soundRef = alarm.soundRef.ifBlank { Alarm.DEFAULT_SOUND_REF },
        volumePercent = alarm.volumePercent,
        gradualVolume = alarm.gradualVolume,
        rampStartPercent = Alarm.DEFAULT_RAMP_START_PERCENT,
        vibration = alarm.vibration,
        snoozeLengthMinutes = alarm.snoozeLengthMinutes,
        graceSeconds = alarm.graceSeconds.coerceIn(Alarm.GRACE_SECONDS_RANGE),
        vibrateInGrace = alarm.vibrateInGrace,
        checks = checks.orderedEntries().ifEmpty { CheckConfig.DEFAULT_ENTRIES },
        checkMode = alarm.checkMode,
    )
