package com.yawnandpawn.app.core.alarm

/** The first field of [alarm] outside its allowed range, or `null` when every field is valid. */
fun validate(alarm: Alarm): AlarmField? =
    when {
        alarm.label != null && alarm.label.characterCount() > Alarm.MAX_LABEL_LENGTH -> AlarmField.Label

        alarm.soundRef.isBlank() -> AlarmField.SoundRef

        alarm.volumePercent !in Alarm.PERCENT_RANGE -> AlarmField.VolumePercent

        alarm.rampStartPercent !in Alarm.PERCENT_RANGE -> AlarmField.RampStartPercent

        // A gradual ramp must go up (or stay level), never down.
        alarm.gradualVolume && alarm.rampStartPercent > alarm.volumePercent -> AlarmField.RampStartPercent

        alarm.snoozeLengthMinutes !in Alarm.SNOOZE_LENGTHS_MINUTES -> AlarmField.SnoozeLengthMinutes

        alarm.graceSeconds !in Alarm.GRACE_SECONDS_RANGE -> AlarmField.GraceSeconds

        else -> null
    }

/**
 * Unicode code points: a surrogate pair (most emoji) counts once, not twice. This is not the number of
 * user-perceived characters (a flag or a skin-tone emoji is several code points).
 */
internal fun String.characterCount(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        index += if (this[index].isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}
