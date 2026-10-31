package com.yawnandpawn.app.data.alarm

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.alarm.MotivationMode
import com.yawnandpawn.app.core.checks.CheckMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
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
        motivation = motivation.encode(),
        // Story 7.11's "Offer to skip" is gone since 0.1.8 (one global Skip reminder): the column stays, always written off.
        offerSkip = false,
        skippedDate = skippedDate?.toString(),
        motivationMode = motivationMode.text,
    )

internal fun AlarmEntity.toAlarm(): Alarm =
    Alarm(
        id = id,
        time = LocalTime.fromNanosecondOfDay(timeNanoOfDay),
        repeatDays = repeatDaysFromBitmask(repeatDays),
        label = label,
        enabled = enabled,
        soundRef = soundRef,
        // Stored below the minimum (before the Epic 3 device-check fix): read as the minimum, never a silent alarm.
        volumePercent = Alarm.ringableVolume(volumePercent),
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
        // Unknown text (a newer version's) plays no message.
        motivation = Motivation.parse(motivation),
        // A date this build cannot read skips nothing: the alarm rings.
        skippedDate = skippedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        // Unknown text (a newer version's) plays the message after the check.
        motivationMode = MotivationMode.parse(motivationMode),
    )

internal fun Set<DayOfWeek>.toBitmask(): Int = fold(0) { mask, day -> mask or (1 shl (day.isoDayNumber - 1)) }

internal fun repeatDaysFromBitmask(mask: Int): Set<DayOfWeek> =
    DayOfWeek.entries.filterTo(mutableSetOf()) { day -> mask and (1 shl (day.isoDayNumber - 1)) != 0 }
