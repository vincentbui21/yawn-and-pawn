package com.yawnandpawn.app.core.recording

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The default length of the alarm-sound block of a mixed ring: 30 s, so the volume ramp (`RAMP_DURATION`) completes
 * before the message plays for the first time.
 */
val MIX_SOUND_BLOCK: Duration = 30.seconds

/**
 * What a "Mix into alarm" ring plays [elapsed] after its mix anchor (Story 7.5, FR-SND-7): [soundBlock] of alarm sound,
 * then the recording once ([recordingDuration]), repeating. With `cycle = soundBlock + recordingDuration` and
 * `t = elapsed mod cycle`, `t < soundBlock` is [MixSource.Alarm], the rest [MixSource.Recording]. A recording without a
 * known length ([recordingDuration] <= 0) never plays: always [MixSource.Alarm]. The anchor is the ring's start, and it
 * restarts when grace ends, after a call and whenever the recording finished early. Pure.
 */
fun mixSchedule(
    elapsed: Duration,
    soundBlock: Duration = MIX_SOUND_BLOCK,
    recordingDuration: Duration,
): MixSource {
    if (recordingDuration <= Duration.ZERO || soundBlock < Duration.ZERO) return MixSource.Alarm
    val cycle = (soundBlock + recordingDuration).inWholeMilliseconds
    val t = elapsed.inWholeMilliseconds.coerceAtLeast(0).mod(cycle)
    return if (t < soundBlock.inWholeMilliseconds) MixSource.Alarm else MixSource.Recording
}
