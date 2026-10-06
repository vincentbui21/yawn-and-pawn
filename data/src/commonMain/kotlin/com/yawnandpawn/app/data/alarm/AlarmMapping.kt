package com.yawnandpawn.app.data.alarm

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.checks.CheckMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlin.time.Instant

/** Timestamps are stored in whole milliseconds (the alarm use cases already truncate "now" to that). */
internal fun Alarm.toEntity(): AlarmEntity =
    AlarmEntity(
        id = id,
        timeNanoOfDay = time.toNanosecondOfDay(),
        repeatDays = repeatDays.toBitmask(),
        label = label,
        enabled = enabled,
        soundRef = soundRef,
        volumePercent = volumePercent,
        gradualVolume = gradualVolume,
        rampStartPercent = rampStartPercent,
        vibration = vibration,
        snoozeLengthMinutes = snoozeLengthMinutes,
        graceSeconds = graceSeconds,
        vibrateInGrace = vibrateInGrace,
        requestCode = requestCode,
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds(),
        checkMode = checkMode.name,
    )

internal fun AlarmEntity.toAlarm(): Alarm =
    Alarm(
        id = id,
        time = LocalTime.fromNanosecondOfDay(timeNanoOfDay),
        repeatDays = repeatDaysFromBitmask(repeatDays),
        label = label,
        enabled = enabled,
        soundRef = soundRef,
        volumePercent = volumePercent,
        gradualVolume = gradualVolume,
        rampStartPercent = rampStartPercent,
        vibration = vibration,
        snoozeLengthMinutes = snoozeLengthMinutes,
        graceSeconds = graceSeconds,
        vibrateInGrace = vibrateInGrace,
        requestCode = requestCode,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        updatedAt = Instant.fromEpochMilliseconds(updatedAt),
        // A mode this build does not know (a newer version's) rings as Random.
        checkMode = CheckMode.entries.firstOrNull { it.name == checkMode } ?: CheckMode.Random,
    )

internal fun Set<DayOfWeek>.toBitmask(): Int = fold(0) { mask, day -> mask or (1 shl (day.isoDayNumber - 1)) }

internal fun repeatDaysFromBitmask(mask: Int): Set<DayOfWeek> =
    DayOfWeek.entries.filterTo(mutableSetOf()) { day -> mask and (1 shl (day.isoDayNumber - 1)) != 0 }
