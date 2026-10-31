package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.checks.CheckMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * A stored alarm (FR-ALM-1, FR-ALM-2). Its checks are stored apart as [CheckConfig] rows (Story 3.5) and pending changes
 * in their own table (Story 4.4); the motivation message is a field since Story 7.2. Ranges are enforced by the alarm
 * use cases ([validate]), not by this class.
 *
 * @property id UUID v4 string.
 * @property repeatDays empty means one-time.
 * @property label optional, at most [Alarm.MAX_LABEL_LENGTH] characters.
 * @property requestCode stable, unique PendingIntent request code (AD-4), see [RequestCodes].
 * @property checkMode how a ring uses the alarm's checks (FR-PWK-2): one picked at random, or all in order.
 * @property offerSkip "Offer to skip 2 h before" (Story 7.11, FR-ALM-10): the pre-alarm notification with "Skip this
 *   one". Off by default; turning it on is a weakening change under the commitment lock (`LockedField.OfferSkip`).
 * @property skippedDate the local date (in the zone of the time of the skip) whose occurrence is skipped (Story 7.11), or
 *   null. A past date never matches a later occurrence, so it is never cleared after its day ([nextRing]).
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
    /**
     * The alarm's own quiet time (Story 3.4). Kept, but no longer used by sessions (0.1.7, owner decision 2026-10-30): quiet
     * time is one global setting, `GlobalSettings.graceSeconds`.
     */
    val graceSeconds: Int = DEFAULT_GRACE_SECONDS,
    /**
     * The alarm's own "Vibrate during quiet time" (Story 3.4). Kept, but no longer used by sessions (0.1.7): the global
     * `GlobalSettings.vibrateInGrace` applies to every alarm.
     */
    val vibrateInGrace: Boolean = DEFAULT_VIBRATE_IN_GRACE,
    val requestCode: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val checkMode: CheckMode = CheckMode.Random,
    /** The motivation message (Story 7.2): none, random or one recording; never a locked field. */
    val motivation: Motivation = Motivation.None,
    /** When the message plays (Story 7.5): after the check or mixed into the ring; never a locked field. */
    val motivationMode: MotivationMode = MotivationMode.AfterImUp,
    val offerSkip: Boolean = false,
    val skippedDate: LocalDate? = null,
) {
    /** When this alarm rings, for [nextOccurrence] (Story 1.6). */
    fun toRule(): AlarmRule = AlarmRule(time, repeatDays)

    companion object {
        /** The default built-in sound, `SoundCatalog.default` ("Sunrise", Story 1.17); see `SoundRef` for the format. */
        const val DEFAULT_SOUND_REF = "builtin:default"
        const val DEFAULT_VOLUME_PERCENT = 80
        const val DEFAULT_RAMP_START_PERCENT = 20

        /** 10 min (owner decision 2026-10-30, was 9). */
        const val DEFAULT_SNOOZE_LENGTH_MINUTES = 10
        const val DEFAULT_GRACE_SECONDS = 20

        /** Owner-approved default 2026-09-26: vibration continues during quiet time. */
        const val DEFAULT_VIBRATE_IN_GRACE = true
        const val MAX_LABEL_LENGTH = 40

        /** 5 / 10 / 15 / 30 min (owner decision 2026-10-30, was 5 / 9 / 10 / 15). */
        val SNOOZE_LENGTHS_MINUTES: Set<Int> = setOf(5, 10, 15, 30)
        val GRACE_SECONDS_RANGE: IntRange = 15..30
        val PERCENT_RANGE: IntRange = 0..100

        /**
         * The lowest alarm volume (Epic 3 device check, bug 3): 0% made a silent alarm possible. 10% is the default taken
         * for the unattended fix (no spec sets one; the slider moves in 5% steps); the owner can change it.
         */
        const val MIN_VOLUME_PERCENT = 10

        /** The volumes an alarm may have: [MIN_VOLUME_PERCENT] to 100%. */
        val VOLUME_PERCENT_RANGE: IntRange = MIN_VOLUME_PERCENT..100

        /** [percent] as a ring may use it: a stored value below [MIN_VOLUME_PERCENT] (saved before the minimum) is raised to it. */
        fun ringableVolume(percent: Int): Int = percent.coerceIn(VOLUME_PERCENT_RANGE)
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

    /** A check that needs a registered code has none (QR/Barcode, Story 3.10): "Scan a code to use this check.". */
    CheckCode,
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
 * sound, volume, gradual volume, vibration, snooze length, check mode, motivation and when it plays, "Offer to skip"). The
 * id, request code, on/off state, timestamps, a skipped date, the ramp start (fixed, not a user setting) and the alarm's
 * own quiet time and its vibration (unused since 0.1.7, not shown) do not count. The checks themselves are stored apart,
 * so `SaveAlarm` compares them too. Saving a new alarm identical to a stored one switches that one on instead of storing
 * a second (owner decision 2026-10-05, like Samsung Clock).
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
        checkMode == other.checkMode &&
        motivation == other.motivation &&
        motivationMode == other.motivationMode &&
        offerSkip == other.offerSkip
