package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.checks.CheckMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * A stored alarm (FR-ALM-1, FR-ALM-2). Its checks are stored apart as [CheckConfig] rows (Story 3.5); pending changes
 * and motivation arrive with their own tables in later epics. Ranges are enforced by the alarm use cases ([validate]),
 * not by this class.
 *
 * @property id UUID v4 string.
 * @property repeatDays empty means one-time.
 * @property label optional, at most [Alarm.MAX_LABEL_LENGTH] characters.
 * @property requestCode stable, unique PendingIntent request code (AD-4), see [RequestCodes].
 * @property checkMode how a ring uses the alarm's checks (FR-PWK-2): one picked at random, or all in order.
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
    /** "Vibrate during quiet time" (Story 3.4): vibration continues while the grace window mutes the alarm. */
    val vibrateInGrace: Boolean = DEFAULT_VIBRATE_IN_GRACE,
    val requestCode: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val checkMode: CheckMode = CheckMode.Random,
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

        /** Owner-approved default 2026-09-26: vibration continues during quiet time. */
        const val DEFAULT_VIBRATE_IN_GRACE = true
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

    /** The checks (Story 3.5): none, the same type twice, a count outside the type's range, or a type no one may pick. */
    Checks,
}

/**
 * Order of the alarm list and of `AlarmRepository.observeAll`: time of day, then creation time, then id so that
 * the order is total.
 */
val AlarmListOrder: Comparator<Alarm> =
    compareBy<Alarm> { it.time }
        .thenBy { it.createdAt }
        .thenBy { it.id }

/**
 * True when [other] rings exactly like this alarm: every setting the user chooses is equal (time, repeat days, label,
 * sound, volume, gradual volume, vibration, snooze length, quiet time and its vibration, check mode). The id, request
 * code, on/off state, timestamps and the ramp start (fixed, not a user setting) do not count. The checks themselves are
 * stored apart, so `SaveAlarm` compares them too. Saving a new alarm identical to a stored one switches that one on
 * instead of storing a second (owner decision 2026-10-05, like Samsung Clock).
 */
fun Alarm.hasSameSettingsAs(other: Alarm): Boolean =
    time == other.time &&
        repeatDays == other.repeatDays &&
        label == other.label &&
        soundRef == other.soundRef &&
        volumePercent == other.volumePercent &&
        gradualVolume == other.gradualVolume &&
        vibration == other.vibration &&
        snoozeLengthMinutes == other.snoozeLengthMinutes &&
        graceSeconds == other.graceSeconds &&
        vibrateInGrace == other.vibrateInGrace &&
        checkMode == other.checkMode
