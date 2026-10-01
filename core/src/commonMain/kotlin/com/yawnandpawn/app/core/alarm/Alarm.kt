package com.yawnandpawn.app.core.alarm

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * A stored alarm (FR-ALM-1, FR-ALM-2). Check configuration, pending changes and motivation arrive with their own
 * tables in later epics. Ranges are enforced by the alarm use cases ([validate]), not by this class.
 *
 * @property id UUID v4 string.
 * @property repeatDays empty means one-time.
 * @property label optional, at most [Alarm.MAX_LABEL_LENGTH] characters.
 * @property requestCode stable, unique PendingIntent request code (AD-4), see [RequestCodes].
 */
data class Alarm(
    val id: String,
    val time: LocalTime,
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val label: String? = null,
    val enabled: Boolean = true,
    val soundRef: String = DEFAULT_SOUND_REF,
    val volumePercent: Int = DEFAULT_VOLUME_PERCENT,
    val gradualVolume: Boolean = true,
    val rampStartPercent: Int = DEFAULT_RAMP_START_PERCENT,
    val vibration: Boolean = true,
    val snoozeLengthMinutes: Int = DEFAULT_SNOOZE_LENGTH_MINUTES,
    val graceSeconds: Int = DEFAULT_GRACE_SECONDS,
    val requestCode: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** When this alarm rings, for [nextOccurrence] (Story 1.6). */
    fun toRule(): AlarmRule = AlarmRule(time, repeatDays)

    companion object {
        /** The default built-in sound, `SoundCatalog.default` ("Sunrise", Story 1.17); see `SoundRef` for the format. */
        const val DEFAULT_SOUND_REF = "builtin:default"
        const val DEFAULT_VOLUME_PERCENT = 80
        const val DEFAULT_RAMP_START_PERCENT = 20
        const val DEFAULT_SNOOZE_LENGTH_MINUTES = 9
        const val DEFAULT_GRACE_SECONDS = 20
        const val MAX_LABEL_LENGTH = 40
        val SNOOZE_LENGTHS_MINUTES: Set<Int> = setOf(5, 9, 10, 15)
        val GRACE_SECONDS_RANGE: IntRange = 15..30
        val PERCENT_RANGE: IntRange = 0..100
    }
}

/** The alarm fields input validation can reject, reported in `DomainError.InvalidAlarm`. */
enum class AlarmField {
    Label,
    SoundRef,
    VolumePercent,
    RampStartPercent,
    SnoozeLengthMinutes,
    GraceSeconds,
}

/**
 * Order of the alarm list and of `AlarmRepository.observeAll`: time of day, then creation time, then id so that
 * the order is total.
 */
val AlarmListOrder: Comparator<Alarm> =
    compareBy<Alarm> { it.time }
        .thenBy { it.createdAt }
        .thenBy { it.id }
